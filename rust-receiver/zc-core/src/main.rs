mod renderer;
mod ui;
mod kiosk;

use winit::{
    event::{Event, WindowEvent, ElementState, MouseScrollDelta},
    event_loop::{ControlFlow, EventLoop},
    window::WindowBuilder,
    keyboard::{KeyCode, PhysicalKey},
};
use zc_protocol::video::{HybridFrame, hybrid_frame};
use std::collections::{HashSet, VecDeque};
use std::sync::{Arc, Mutex, atomic::{AtomicBool, AtomicU32, Ordering}};
use prost::Message;

const INPUT_BUFFER_MAX: usize = 1000;

/// Ceiling on a single framed message from the phone: a 4K IDR plus csd is well
/// under 2 MiB. The 4-byte length prefix must never be trusted for an
/// unbounded allocation — one hostile or corrupt length is an instant OOM.
const MAX_FRAME_BYTES: usize = 8 * 1024 * 1024;

/// Depth of the video frame queue between the QUIC task and the render loop.
const VIDEO_QUEUE_DEPTH: usize = 4;

/// True when the frame is an IDR (keyframe), matching the receiver's
/// `is_keyframe` semantics everywhere else.
fn frame_is_keyframe(frame: &HybridFrame) -> bool {
    matches!(&frame.payload, Some(zc_protocol::video::hybrid_frame::Payload::Video(v)) if v.is_keyframe)
}

/// Bounded video queue between the tokio task and the render loop.
///
/// The push NEVER blocks: this replaced a `sync_channel(4)` whose blocking
/// `send` ran inside the tokio task — with the queue full and the render loop
/// stalled, the worker thread froze and audio (processed in the same select
/// loop) stalled behind video. On a full queue the push evicts the OLDEST
/// non-keyframe (P-frames are recoverable via a keyframe request; a queued IDR
/// is not); it never blocks and returns whether a frame was dropped so the
/// pusher can raise the keyframe flag.
struct VideoFrameQueue {
    inner: Mutex<VecDeque<HybridFrame>>,
    capacity: usize,
}

impl VideoFrameQueue {
    fn new(capacity: usize) -> Self {
        Self {
            inner: Mutex::new(VecDeque::with_capacity(capacity)),
            capacity,
        }
    }

    /// Pushes [frame] without ever blocking. Returns true when a frame was
    /// dropped (an evicted P-frame, or the newcomer when the queue holds only
    /// keyframes) — the caller then raises the keyframe flag.
    fn push(&self, frame: HybridFrame) -> bool {
        let mut q = match self.inner.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let mut dropped = false;
        if q.len() >= self.capacity {
            if let Some(idx) = q.iter().position(|f| !frame_is_keyframe(f)) {
                q.remove(idx);
                dropped = true;
            } else if !frame_is_keyframe(&frame) {
                // All queued frames are keyframes and the newcomer is a P-frame:
                // drop the newcomer rather than a queued IDR.
                return true;
            } else {
                // All keyframes plus a new keyframe: the bound wins.
                q.pop_front();
                dropped = true;
            }
        }
        q.push_back(frame);
        dropped
    }

    /// Takes every queued frame (the render loop's batch catch-up then skips to
    /// the newest keyframe in the batch — the §3.1 invariant).
    fn drain(&self) -> Vec<HybridFrame> {
        let mut q = match self.inner.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        q.drain(..).collect()
    }
}

/// True while either kiosk lock is engaged: the registry-detected assigned-access
/// profile, or the receiver's own UI-toggled lock. While locked, the window close
/// button and Escape-to-exit do nothing; Ctrl+Alt+Esc releases the soft lock.
fn kiosk_locked(registry: bool, soft: bool) -> bool {
    registry || soft
}

/// Serializes [ev] and queues it for the QUIC input stream, dropping the oldest
/// event when the buffer is full.
fn push_input_event(
    ev: &zc_protocol::protocol::InputEvent,
    buf: &Arc<Mutex<VecDeque<Vec<u8>>>>,
) {
    let mut serialized = Vec::new();
    if ev.encode(&mut serialized).is_ok() {
        if let Ok(mut buf) = buf.lock() {
            if buf.len() >= INPUT_BUFFER_MAX {
                buf.pop_front();
            }
            buf.push_back(serialized);
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|arg| arg == "--forget-pairing") {
        zc_network::cleanup_legacy_trust();
        zc_network::delete_trust_data();
        println!("Stored pairing and trust data cleared successfully.");
        return;
    }
    zc_network::cleanup_legacy_trust();

    // Port override for dev setups (e.g. an emulator behind a UDP relay):
    // ANDROIDDEX_HOST skips discovery, ANDROIDDEX_PORT changes the port.
    let port: u16 = std::env::var("ANDROIDDEX_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(4433);

    // Video frames from the QUIC task to the render loop: a bounded, non-blocking
    // queue. `frame_dropped_flag` is the latch the task raises when the queue
    // dropped a P-frame; the render loop folds it into its need_keyframe state.
    let video_queue = Arc::new(VideoFrameQueue::new(VIDEO_QUEUE_DEPTH));
    let frame_dropped_flag = Arc::new(AtomicBool::new(false));
    
    // Initialize audio player on main thread so stream lives forever
    let (_audio_player, audio_sender) = match zc_audio::AudioPlayer::new() {
        Ok((player, sender)) => (Some(player), Some(sender)),
        Err(e) => {
            eprintln!("Failed to initialize audio player: {}", e);
            (None, None)
        }
    };

    // Shared state for input sending: holds serialized InputEvent bytes
    // The input polling thread pushes here; the QUIC sender drains from here.
    let input_buffer: Arc<Mutex<VecDeque<Vec<u8>>>> = Arc::new(Mutex::new(VecDeque::with_capacity(INPUT_BUFFER_MAX)));
    
    let connection_phase = Arc::new(Mutex::new(zc_network::ConnectionPhase::Idle));
    let is_connected = Arc::new(AtomicBool::new(false));
    let is_audio_muted = Arc::new(AtomicBool::new(false));
    let audio_volume_pct = Arc::new(AtomicU32::new(100));
    // Bumped by the UI's Reconnect action; the live connection's watcher future
    // sees the change and closes the connection, which loops into a reconnect.
    let reconnect_gen = Arc::new(std::sync::atomic::AtomicU64::new(0));
    let reconnect_gen_for_ui = reconnect_gen.clone();

    let rt = tokio::runtime::Runtime::new().expect("Failed to build tokio runtime");

    let window_for_callback: Arc<Mutex<Option<Arc<winit::window::Window>>>> = Arc::new(Mutex::new(None));

    // Clone for the QUIC task
    let input_buffer_for_quic = input_buffer.clone();
    let phase_for_quic = connection_phase.clone();
    let is_connected_quic = is_connected.clone();
    let window_for_quic = window_for_callback.clone();
    let is_muted_for_quic = is_audio_muted.clone();
    let audio_vol_for_quic = audio_volume_pct.clone();
    
    // Clones for the QUIC task.
    let video_queue_for_quic = video_queue.clone();
    let frame_dropped_for_quic = frame_dropped_flag.clone();

    rt.spawn(async move {
        let mut attempt_backoff: u32 = 0;
        loop {
            let phase_clone = phase_for_quic.clone();
            let video_queue_loop = video_queue_for_quic.clone();
            let frame_dropped_loop = frame_dropped_for_quic.clone();
            let audio_sender_loop = audio_sender.clone();
            let input_buffer_loop = input_buffer_for_quic.clone();
            let is_connected_loop = is_connected_quic.clone();
            let window_for_callback_loop = window_for_quic.clone();
            let is_muted_loop = is_muted_for_quic.clone();
            let audio_vol_loop = audio_vol_for_quic.clone();
            let window_for_frames = window_for_quic.clone();
            let reconnect_gen_for_task = reconnect_gen.clone();
            let my_generation = reconnect_gen_for_task.load(Ordering::SeqCst);

            match zc_network::connect(port, move |phase| {
                if let Ok(mut p) = phase_clone.lock() {
                    *p = phase.clone();
                }
                if let Ok(w_guard) = window_for_callback_loop.lock() {
                    if let Some(w) = w_guard.as_ref() {
                        w.request_redraw();
                    }
                }
                if matches!(phase, zc_network::ConnectionPhase::Connected) {
                    println!("ConnectionPhase updated to Connected");
                }
            }).await {
                Ok(conn) => {
                    println!("Connected to Android server");
                    is_connected_loop.store(true, Ordering::SeqCst);

                    let ev = zc_input::create_keyframe_request();
                    push_input_event(&ev, &input_buffer_loop);

                    // All futures run as siblings inside tokio::select!
                    // When ANY exits, we close the connection and loop back.
                    let exit_reason = tokio::select! {
                        biased;

                        // Future 1: Connection closed by peer or QUIC timeout
                        reason = conn.closed() => {
                            format!("Connection closed by peer: {:?}", reason)
                        }

                        // Future 4: UI Reconnect action — a generation bump closes
                        // the connection so the outer loop reconnects immediately.
                        reason = async {
                            loop {
                                if reconnect_gen_for_task.load(Ordering::SeqCst) != my_generation {
                                    conn.close(0u32.into(), b"reconnect requested by UI");
                                    return "reconnect requested by UI".to_string();
                                }
                                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                            }
                        } => { reason }

                        // Future 2: Video/audio receiver worker
                        reason = async {
                            loop {
                                match conn.accept_uni().await {
                                    Err(e) => {
                                        return format!("accept_uni failed: {}", e);
                                    }
                                    Ok(mut stream) => {

                                        let video_queue_inner = video_queue_loop.clone();
                                        let drop_flag_inner = frame_dropped_loop.clone();
                                        let ap_inner = audio_sender_loop.clone();

                                        // Process this stream inline (no detached spawn)
                                        // Loop on this stream as long as sender keeps it open
                                        loop {
                                            let mut len_buf = [0u8; 4];
                                            if stream.read_exact(&mut len_buf).await.is_err() {
                                                break; // Stream closed or error, break inner loop to accept next stream
                                            }
                                            let len = u32::from_le_bytes(len_buf) as usize;
                                            if len == 0 { continue; }
                                            // The length prefix is never trusted for an
                                            // allocation: a hostile or corrupt length would
                                            // OOM the receiver instantly.
                                            if len > MAX_FRAME_BYTES {
                                                eprintln!("frame claims {} bytes (cap {}); dropping the stream", len, MAX_FRAME_BYTES);
                                                break;
                                            }

                                            let mut frame_buf = vec![0u8; len];
                                            if stream.read_exact(&mut frame_buf).await.is_err() {
                                                break; // Stream closed midway
                                            }

                                            if frame_buf.is_empty() { continue; }

                                            let msg_type = frame_buf[0];
                                            let payload = &frame_buf[1..];

                                            if msg_type == 0x01 {
                                                // Video. The push never blocks
                                                // (this replaced a sync_channel
                                                // whose blocking send froze the
                                                // tokio worker — and audio in the
                                                // same loop — behind video). On a
                                                // full queue the oldest P-frame is
                                                // evicted and the render loop is
                                                // told to wait for a keyframe; the
                                                // render side keeps its §3.1
                                                // batch catch-up.
                                                if let Ok(frame) = HybridFrame::decode(payload) {
                                                    let dropped = video_queue_inner.push(frame);
                                                    if dropped {
                                                        drop_flag_inner.store(true, Ordering::SeqCst);
                                                    }
                                                    // The render loop sleeps in ControlFlow::Wait;
                                                    // only an explicit request wakes it, so a
                                                    // frame arrival must raise one.
                                                    if let Ok(w_guard) = window_for_frames.lock() {
                                                        if let Some(w) = w_guard.as_ref() {
                                                            w.request_redraw();
                                                        }
                                                    }
                                                } else {
                                                    eprintln!("Failed to decode HybridFrame");
                                                }
                                            } else if msg_type == 0x02 {
                                                // Audio
                                                if !is_muted_loop.load(Ordering::Relaxed) {
                                                    let vol_scale = audio_vol_loop.load(Ordering::Relaxed) as f32 / 100.0;
                                                    if let Ok(audio_packet) = zc_protocol::audio::AudioPacket::decode(payload) {
                                                        if let Some(player) = ap_inner.as_ref() {
                                                            let pcm_bytes = &audio_packet.pcm_data;
                                                            if pcm_bytes.len() % 2 == 0 {
                                                                let mut i16_samples = Vec::with_capacity(pcm_bytes.len() / 2);
                                                                for chunk in pcm_bytes.chunks_exact(2) {
                                                                    let orig = i16::from_le_bytes([chunk[0], chunk[1]]) as f32;
                                                                    let sample = (orig * vol_scale).clamp(i16::MIN as f32, i16::MAX as f32) as i16;
                                                                    i16_samples.push(sample);
                                                                }
                                                                player.play_pcm(&i16_samples);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } => { reason }

                        // Future 3: Input sender worker
                        reason = async {
                            match conn.open_uni().await {
                                Ok(mut input_stream) => {
                                    println!("Opened input stream to server");
                                    loop {
                                        let events: Vec<Vec<u8>> = {
                                            if let Ok(mut buf) = input_buffer_loop.lock() {
                                                buf.drain(..).collect()
                                            } else {
                                                Vec::new()
                                            }
                                        };

                                        if events.is_empty() {
                                            // Phase 4: reduced from 8ms to 1ms for lower input latency
                                            tokio::time::sleep(std::time::Duration::from_millis(1)).await;
                                            continue;
                                        }

                                        for serialized in events {
                                            let len = serialized.len() as u32;
                                            if input_stream.write_all(&len.to_le_bytes()).await.is_err() {
                                                return "Input stream write failed (length)".to_string();
                                            }
                                            if input_stream.write_all(&serialized).await.is_err() {
                                                return "Input stream write failed (data)".to_string();
                                            }
                                        }
                                    }
                                }
                                Err(e) => {
                                    format!("Failed to open input stream: {}", e)
                                }
                            }
                        } => { reason }
                    };

                    // One of the three futures exited. Tear down everything.
                    eprintln!("Connection ended: {}", exit_reason);
                    conn.close(0u32.into(), b"worker_exited");
                    is_connected_loop.store(false, Ordering::SeqCst);
                    attempt_backoff = 0;
                }
                Err(e) => {
                    eprintln!("Connection failed: {}", e);
                    attempt_backoff = (attempt_backoff + 1).min(4);
                }
            }
            // Exponential backoff: 1, 2, 3, 4, 5 s — a dead phone should not be
            // hammered at a fixed 3 s cadence forever, and a quick reconnect after
            // a healthy session should stay instant.
            tokio::time::sleep(std::time::Duration::from_secs(attempt_backoff as u64 + 1)).await;
        }
    });

    let input_buffer_for_poll = input_buffer.clone();

    let event_loop = EventLoop::new()
        .unwrap_or_else(|e| {
            renderer::show_fatal_error("AndroidDex Receiver", &format!("cannot create the event loop: {e}"));
            std::process::exit(1);
        });

    let window = match WindowBuilder::new()
        .with_title("AndroidDex Receiver")
        .with_decorations(true)
        .with_min_inner_size(winit::dpi::LogicalSize::new(420.0, 320.0))
        .build(&event_loop)
    {
        Ok(w) => std::sync::Arc::new(w),
        Err(e) => {
            renderer::show_fatal_error("AndroidDex Receiver", &format!("cannot create the window: {e}"));
            std::process::exit(1);
        }
    };

    if let Ok(mut w) = window_for_callback.lock() {
        *w = Some(window.clone());
    }

    let mut renderer = match pollster::block_on(renderer::Renderer::new(window.clone())) {
        Ok(r) => r,
        Err(e) => {
            renderer::show_fatal_error("AndroidDex Receiver", &e);
            return;
        }
    };
    
    // Initialize egui overlay
    let mut overlay_ui = ui::overlay::OverlayUi::new(
        renderer.device(),
        renderer.config().format,
        &window,
    );

    // --- H.264 decoder and YUV render pipeline ---
    let mut h264_decoder = match zc_video::Decoder::new() {
        Ok(d) => Some(d),
        Err(e) => {
            eprintln!("Failed to initialize H.264 decoder: {}", e);
            None
        }
    };

    // YUV texture state — created on first decoded frame
    struct YuvTextures {
        y_texture: wgpu::Texture,
        u_texture: wgpu::Texture,
        v_texture: wgpu::Texture,
        bind_group: wgpu::BindGroup,
        width: u32,
        height: u32,
    }
    let mut yuv_textures: Option<YuvTextures> = None;

    // Until the first keyframe is decoded, P-frames reference pictures the decoder
    // has never seen. Also set after every decode error: OpenH264 does not recover
    // a wedged reference state on its own, so the decoder is rebuilt from scratch
    // when the next keyframe arrives.
    let mut need_keyframe = true;

    // Create the YUV shader pipeline
    let yuv_shader = renderer.device().create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("YUV Shader"),
        source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(include_str!("shader.wgsl"))),
    });

    let yuv_bind_group_layout = renderer.device().create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some("YUV Bind Group Layout"),
        entries: &[
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    multisampled: false,
                    view_dimension: wgpu::TextureViewDimension::D2,
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 1,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    multisampled: false,
                    view_dimension: wgpu::TextureViewDimension::D2,
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 2,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    multisampled: false,
                    view_dimension: wgpu::TextureViewDimension::D2,
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 3,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 4,
                visibility: wgpu::ShaderStages::VERTEX,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            },
        ],
    });

    let yuv_pipeline_layout = renderer.device().create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("YUV Pipeline Layout"),
        bind_group_layouts: &[&yuv_bind_group_layout],
        push_constant_ranges: &[],
    });

    let yuv_pipeline = renderer.device().create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("YUV Render Pipeline"),
        layout: Some(&yuv_pipeline_layout),
        vertex: wgpu::VertexState {
            module: &yuv_shader,
            entry_point: "vs_main",
            buffers: &[], // Full-screen triangle from vertex_index, no buffer needed
        },
        fragment: Some(wgpu::FragmentState {
            module: &yuv_shader,
            entry_point: "fs_main",
            targets: &[Some(wgpu::ColorTargetState {
                format: renderer.config().format,
                blend: None,
                write_mask: wgpu::ColorWrites::ALL,
            })],
        }),
        primitive: wgpu::PrimitiveState {
            topology: wgpu::PrimitiveTopology::TriangleList,
            strip_index_format: None,
            front_face: wgpu::FrontFace::Ccw,
            cull_mode: None,
            polygon_mode: wgpu::PolygonMode::Fill,
            unclipped_depth: false,
            conservative: false,
        },
        depth_stencil: None,
        multisample: wgpu::MultisampleState {
            count: 1,
            mask: !0,
            alpha_to_coverage_enabled: false,
        },
        multiview: None,
    });

    let yuv_sampler = renderer.device().create_sampler(&wgpu::SamplerDescriptor {
        address_mode_u: wgpu::AddressMode::ClampToEdge,
        address_mode_v: wgpu::AddressMode::ClampToEdge,
        address_mode_w: wgpu::AddressMode::ClampToEdge,
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        mipmap_filter: wgpu::FilterMode::Nearest,
        ..Default::default()
    });

    // Letterbox rect uniform: [offset.x, offset.y, scale.x, scale.y] in NDC.
    let rect_uniform = renderer.device().create_buffer(&wgpu::BufferDescriptor {
        label: Some("Letterbox Rect Uniform"),
        size: 16,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });

    // Helper to create a R8Unorm texture of the given size
    let create_r8_texture = |device: &wgpu::Device, label: &str, w: u32, h: u32| -> wgpu::Texture {
        device.create_texture(&wgpu::TextureDescriptor {
            label: Some(label),
            size: wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        })
    };

    let mut mouse_pos = (0.0, 0.0);
    let is_kiosk = kiosk::is_kiosk_mode();
    // Software kiosk lock, toggled by the UI's Kiosk action and released by the
    // Ctrl+Alt+Esc escape chord. The registry check (above) locks a real
    // assigned-access profile; this one is the receiver's own lock on top.
    let mut kiosk_soft = false;
    let mut is_focused = true;
    let mut mouse_buttons = 0u32;
    let mut current_modifiers = winit::keyboard::ModifiersState::empty();
    // Physical keys the phone currently believes are held down. Used to flush
    // releases when the window loses focus (alt-tab mid-drag/mid-keystroke) and
    // to suppress OS key auto-repeat (the phone synthesises its own repeats).
    let mut pressed_keys: HashSet<KeyCode> = HashSet::new();
    // Decoded frame size, used both for the letterbox rect (render + input) and
    // updated whenever a stream appears at a different resolution. 1920x1080 is
    // the wire/capture default before the first frame arrives.
    let mut video_dims: (u32, u32) = (zc_input::VIRTUAL_WIDTH, zc_input::VIRTUAL_HEIGHT);

    // Stats for the overlay
    let mut decode_fps_counter = 0u32;
    let mut decode_fps_last_time = std::time::Instant::now();
    let mut decode_fps_display = 0u32;
    let mut last_decode_us = 0u64;
    let mut has_video = false;

    let window_id = window.id();
    event_loop.run(move |event, elwt| {
        elwt.set_control_flow(ControlFlow::Wait);

        match &event {
            Event::WindowEvent { window_id: id, event: w_event } if *id == window_id => {
                if overlay_ui.handle_event(&window, w_event) {
                    window.request_redraw();
                    return; // event consumed by egui
                }

                match w_event {
                    WindowEvent::CloseRequested => {
                        if !kiosk_locked(is_kiosk, kiosk_soft) {
                            elwt.exit();
                        }
                    }
                    WindowEvent::Focused(focused) => {
                        is_focused = *focused;
                        if !*focused {
                            // The phone will never see the releases of whatever was
                            // held at the moment of the alt-tab, so flush them:
                            // a stuck mouse button leaves a drag running forever on
                            // the streamed desktop, a stuck key repeats in apps.
                            if mouse_buttons != 0 {
                                mouse_buttons = 0;
                                let inner_size = window.inner_size();
                                let ev = zc_input::create_mouse_event(
                                    mouse_pos.0, mouse_pos.1,
                                    inner_size.width, inner_size.height,
                                    video_dims.0, video_dims.1,
                                    0, 0,
                                );
                                push_input_event(&ev, &input_buffer_for_poll);
                            }
                            let held: Vec<KeyCode> = pressed_keys.drain().collect();
                            for code in held {
                                let ev = zc_input::create_keyboard_event(code as u32, false, 0);
                                push_input_event(&ev, &input_buffer_for_poll);
                            }
                        }
                    }
                    WindowEvent::ModifiersChanged(modifiers) => {
                        current_modifiers = modifiers.state();
                    }
                    WindowEvent::MouseInput { state, button, .. } => {
                        if is_focused {
                            let bit = match button {
                                winit::event::MouseButton::Left => 1,
                                winit::event::MouseButton::Right => 2,
                                winit::event::MouseButton::Middle => 4,
                                _ => 0,
                            };
                            if *state == ElementState::Pressed {
                                mouse_buttons |= bit;
                            } else {
                                mouse_buttons &= !bit;
                            }
                            let inner_size = window.inner_size();
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_mouse_event(
                                mouse_pos.0, mouse_pos.1,
                                inner_size.width, inner_size.height,
                                video_dims.0, video_dims.1,
                                mouse_buttons, wire_mods,
                            );
                            push_input_event(&ev, &input_buffer_for_poll);
                        }
                    }
                    WindowEvent::MouseWheel { delta, .. } => {
                        if is_focused {
                            let (v_scroll, h_scroll) = match delta {
                                MouseScrollDelta::LineDelta(h, v) => (*v, *h),
                                MouseScrollDelta::PixelDelta(pos) => {
                                    // Convert pixel delta to line delta (approximate)
                                    (pos.y as f32 / 120.0, pos.x as f32 / 120.0)
                                }
                            };
                            let inner_size = window.inner_size();
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_scroll_event(
                                mouse_pos.0, mouse_pos.1,
                                inner_size.width, inner_size.height,
                                video_dims.0, video_dims.1,
                                v_scroll, h_scroll, wire_mods,
                            );
                            push_input_event(&ev, &input_buffer_for_poll);
                        }
                    }
                    WindowEvent::KeyboardInput {
                        event: key_event,
                        ..
                    } => {
                        let pressed = key_event.state == ElementState::Pressed;
                        let text_str = key_event.text.as_ref().map(|s| s.as_str());
                        let routed_as_text = zc_input::should_route_as_text(pressed, text_str, &current_modifiers);

                        // Escape is an application key first and an exit chord
                        // second. Overlays close; while streaming the key is
                        // forwarded to the phone (apps use it); only on the idle
                        // wallpaper does it exit, preserving the quick-quit path.
                        // egui has already consumed the event earlier if one of
                        // its text fields has focus.
                        if key_event.physical_key == PhysicalKey::Code(KeyCode::Escape) && pressed {
                            let closed_overlay = {
                                let ui = &mut overlay_ui.ui_state;
                                if ui.diagnostics_open {
                                    ui.diagnostics_open = false;
                                    true
                                } else if ui.settings_open {
                                    ui.settings_open = false;
                                    true
                                } else if ui.volume_flyout_open {
                                    ui.volume_flyout_open = false;
                                    true
                                } else if ui.network_flyout_open {
                                    ui.network_flyout_open = false;
                                    true
                                } else if ui.calendar_flyout_open {
                                    ui.calendar_flyout_open = false;
                                    true
                                } else if ui.action_center_open {
                                    ui.action_center_open = false;
                                    true
                                } else if ui.start_menu_open {
                                    ui.start_menu_open = false;
                                    true
                                } else {
                                    false
                                }
                            };
                            if closed_overlay {
                                window.request_redraw();
                                return;
                            }
                            // The escape hatch: Ctrl+Alt+Esc releases the receiver's
                            // own kiosk lock (the registry lock stays until the
                            // Assigned Access profile is actually removed).
                            let ctrl_alt = current_modifiers.control_key() && current_modifiers.alt_key();
                            if kiosk_soft && ctrl_alt {
                                kiosk_soft = false;
                                println!("Kiosk lock released via Ctrl+Alt+Esc");
                                window.request_redraw();
                                return;
                            }
                            let connected = connection_phase
                                .lock()
                                .map(|p| matches!(*p, zc_network::ConnectionPhase::Connected))
                                .unwrap_or(false);
                            if !kiosk_locked(is_kiosk, kiosk_soft) && !connected {
                                println!("Escape pressed while idle, exiting gracefully...");
                                elwt.exit();
                                return;
                            }
                            // Kiosk or connected: fall through and forward Escape
                            // to the phone like any other key.
                        }

                        if is_focused {
                            let physical = key_event.physical_key;
                            if let PhysicalKey::Code(code) = physical {
                                if pressed {
                                    pressed_keys.insert(code);
                                    if key_event.repeat && !routed_as_text {
                                        // OS key auto-repeat for a keyboard-routed key:
                                        // the phone synthesises its own repeats from the
                                        // initial press, so forwarding these would only
                                        // flood the stream and double-repeat. Text-routed
                                        // keys keep OS repeats — they carry no keycode the
                                        // phone could synthesise from.
                                        return;
                                    }
                                } else {
                                    pressed_keys.remove(&code);
                                }
                            }

                            let ev = if routed_as_text {
                                zc_input::create_text_event(text_str.unwrap().to_string())
                            } else {
                                let mut keycode = 0u32;
                                if let PhysicalKey::Code(code) = key_event.physical_key {
                                    keycode = code as u32;
                                }
                                let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                                zc_input::create_keyboard_event(keycode, pressed, wire_mods)
                            };
                            push_input_event(&ev, &input_buffer_for_poll);
                        }
                    }
                    WindowEvent::CursorMoved { position, .. } => {
                        mouse_pos = (position.x, position.y);
                        if is_focused {
                            let inner_size = window.inner_size();
                            let wire_mods = zc_input::winit_modifiers_to_wire(&current_modifiers);
                            let ev = zc_input::create_mouse_event(
                                mouse_pos.0, mouse_pos.1,
                                inner_size.width, inner_size.height,
                                video_dims.0, video_dims.1,
                                mouse_buttons, wire_mods,
                            );
                            push_input_event(&ev, &input_buffer_for_poll);
                        }
                    }
                    WindowEvent::Resized(physical_size) => {
                        renderer.resize(*physical_size);
                    }
                    WindowEvent::RedrawRequested => {
                        let inner_size = window.inner_size();
                        if inner_size.width == 0 || inner_size.height == 0 {
                            return;
                        }

                        // Drain all pending frames. When a backlog has built up (the
                        // decoder is slower than the sender), decode only from the
                        // newest keyframe in the batch: the dropped prefix is stale,
                        // and nothing after a keyframe references frames before it.
                        // A queue-side P-frame drop (VideoFrameQueue::push on a full
                        // queue) folds in here: skip until the next IDR instead of
                        // feeding the decoder a gap it must error on first.
                        if frame_dropped_flag.swap(false, Ordering::SeqCst) {
                            need_keyframe = true;
                        }
                        let batch: Vec<HybridFrame> = video_queue.drain();
                        let start_at = batch
                            .iter()
                            .rposition(|h| {
                                matches!(&h.payload, Some(hybrid_frame::Payload::Video(vf)) if vf.is_keyframe)
                            })
                            .unwrap_or(0);

                        for hybrid in batch.into_iter().skip(start_at) {
                            match hybrid.payload {
                                Some(hybrid_frame::Payload::Video(frame)) => {
                                    if need_keyframe {
                                        if !frame.is_keyframe {
                                            continue;
                                        }
                                        match zc_video::Decoder::new() {
                                            Ok(d) => h264_decoder = Some(d),
                                            Err(e) => {
                                                eprintln!("Failed to recreate H.264 decoder: {}", e);
                                                continue;
                                            }
                                        }
                                        need_keyframe = false;
                                    }
                                    if let Some(ref mut decoder) = h264_decoder {
                                        let decode_start = std::time::Instant::now();
                                        match decoder.decode(&frame.nal_data) {
                                            Ok(Some(decoded)) => {
                                                last_decode_us = decode_start.elapsed().as_micros() as u64;
                                                has_video = true;

                                                let w = decoded.width;
                                                let h = decoded.height;
                                                if w == 0 || h == 0 {
                                                    continue;
                                                }
                                                let cw = (w + 1) / 2; // chroma width
                                                let ch = (h + 1) / 2; // chroma height

                                                // Recreate textures if size changed
                                                let needs_recreate = match &yuv_textures {
                                                    Some(t) => t.width != w || t.height != h,
                                                    None => true,
                                                };

                                                if needs_recreate {
                                                    let y_tex = create_r8_texture(renderer.device(), "Y Plane", w, h);
                                                    let u_tex = create_r8_texture(renderer.device(), "U Plane", cw, ch);
                                                    let v_tex = create_r8_texture(renderer.device(), "V Plane", cw, ch);

                                                    let y_view = y_tex.create_view(&wgpu::TextureViewDescriptor::default());
                                                    let u_view = u_tex.create_view(&wgpu::TextureViewDescriptor::default());
                                                    let v_view = v_tex.create_view(&wgpu::TextureViewDescriptor::default());

                                                    let bind_group = renderer.device().create_bind_group(&wgpu::BindGroupDescriptor {
                                                        label: Some("YUV Bind Group"),
                                                        layout: &yuv_bind_group_layout,
                                                        entries: &[
                                                            wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&y_view) },
                                                            wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&u_view) },
                                                            wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(&v_view) },
                                                            wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::Sampler(&yuv_sampler) },
                                                            wgpu::BindGroupEntry { binding: 4, resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding { buffer: &rect_uniform, offset: 0, size: None }) },
                                                        ],
                                                    });

                                                    yuv_textures = Some(YuvTextures {
                                                        y_texture: y_tex,
                                                        u_texture: u_tex,
                                                        v_texture: v_tex,
                                                        bind_group,
                                                        width: w,
                                                        height: h,
                                                    });
                                                    // The letterbox rect and input mapping must
                                                    // track the frame size, not the window.
                                                    video_dims = (w, h);
                                                }

                                                // Upload YUV planes
                                                if let Some(ref textures) = yuv_textures {
                                                    // Y plane
                                                    renderer.queue().write_texture(
                                                        wgpu::ImageCopyTexture {
                                                            texture: &textures.y_texture,
                                                            mip_level: 0,
                                                            origin: wgpu::Origin3d::ZERO,
                                                            aspect: wgpu::TextureAspect::All,
                                                        },
                                                        &decoded.y,
                                                        wgpu::ImageDataLayout {
                                                            offset: 0,
                                                            bytes_per_row: Some(w),
                                                            rows_per_image: Some(h),
                                                        },
                                                        wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
                                                    );
                                                    // U plane
                                                    renderer.queue().write_texture(
                                                        wgpu::ImageCopyTexture {
                                                            texture: &textures.u_texture,
                                                            mip_level: 0,
                                                            origin: wgpu::Origin3d::ZERO,
                                                            aspect: wgpu::TextureAspect::All,
                                                        },
                                                        &decoded.u,
                                                        wgpu::ImageDataLayout {
                                                            offset: 0,
                                                            bytes_per_row: Some(cw),
                                                            rows_per_image: Some(ch),
                                                        },
                                                        wgpu::Extent3d { width: cw, height: ch, depth_or_array_layers: 1 },
                                                    );
                                                    // V plane
                                                    renderer.queue().write_texture(
                                                        wgpu::ImageCopyTexture {
                                                            texture: &textures.v_texture,
                                                            mip_level: 0,
                                                            origin: wgpu::Origin3d::ZERO,
                                                            aspect: wgpu::TextureAspect::All,
                                                        },
                                                        &decoded.v,
                                                        wgpu::ImageDataLayout {
                                                            offset: 0,
                                                            bytes_per_row: Some(cw),
                                                            rows_per_image: Some(ch),
                                                        },
                                                        wgpu::Extent3d { width: cw, height: ch, depth_or_array_layers: 1 },
                                                    );
                                                }

                                                // FPS counter
                                                decode_fps_counter += 1;
                                                let now = std::time::Instant::now();
                                                if now.duration_since(decode_fps_last_time).as_secs() >= 1 {
                                                    decode_fps_display = decode_fps_counter;
                                                    decode_fps_counter = 0;
                                                    decode_fps_last_time = now;
                                                }
                                            }
                                            Ok(None) => { /* NAL consumed, no picture */ }
                                            Err(e) => {
                                                eprintln!("H.264 decode error: {}; waiting for the next keyframe", e);
                                                need_keyframe = true;
                                                let ev = zc_input::create_keyframe_request();
                                                push_input_event(&ev, &input_buffer_for_poll);
                                            }
                                        }
                                    }
                                }
                                // Tile and Cursor variants no longer exist in the proto,
                                // but handle gracefully if they ever appear.
                                _ => {}
                            }
                        }

                        // Aspect-preserving letterbox rect for the frame, in NDC:
                        // xy = rect centre, zw = half-extent. A 16:9 frame in a
                        // 16:9 window degenerates to the old full-screen quad.
                        {
                            let win_w = inner_size.width as f64;
                            let win_h = inner_size.height as f64;
                            let scale = (win_w / video_dims.0 as f64).min(win_h / video_dims.1 as f64);
                            let disp_w = video_dims.0 as f64 * scale;
                            let disp_h = video_dims.1 as f64 * scale;
                            let ox = (win_w - disp_w) / 2.0;
                            let oy = (win_h - disp_h) / 2.0;
                            // Window pixels -> NDC (y flips: window y grows down).
                            let x0 = 2.0 * ox / win_w - 1.0;
                            let x1 = 2.0 * (ox + disp_w) / win_w - 1.0;
                            let y0 = 1.0 - 2.0 * oy / win_h;
                            let y1 = 1.0 - 2.0 * (oy + disp_h) / win_h;
                            let rect = [
                                ((x0 + x1) / 2.0) as f32,
                                ((y0 + y1) / 2.0) as f32,
                                ((x1 - x0) / 2.0) as f32,
                                ((y0 - y1) / 2.0) as f32,
                            ];
                            renderer.queue().write_buffer(&rect_uniform, 0, bytemuck::cast_slice(&rect));
                        }

                        match renderer.get_target_view() {
                            Ok((frame, view)) => {
                                // One encoder, two render passes: the video pass
                                // (clears to black, draws the letterboxed frame) and
                                // the egui overlay pass (loads, draws on top) — a
                                // single submit instead of two.
                                let mut encoder = renderer.device().create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("Frame Encoder") });
                                {
                                    let mut rpass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                                        label: Some("Video Render Pass"),
                                        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                                            view: &view,
                                            resolve_target: None,
                                            ops: wgpu::Operations {
                                                load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                                                store: wgpu::StoreOp::Store,
                                            },
                                        })],
                                        depth_stencil_attachment: None,
                                        timestamp_writes: None,
                                        occlusion_query_set: None,
                                    });
                                    if let Some(ref textures) = yuv_textures {
                                        rpass.set_pipeline(&yuv_pipeline);
                                        rpass.set_bind_group(0, &textures.bind_group, &[]);
                                        rpass.draw(0..3, 0..1); // Full-screen triangle
                                    }
                                }

                                let phase = connection_phase.lock().map(|p| p.clone()).unwrap_or(zc_network::ConnectionPhase::Connected);
                                let actions = overlay_ui.render(
                                    &window,
                                    renderer.device(),
                                    renderer.queue(),
                                    &view,
                                    &mut encoder,
                                    &phase,
                                    mouse_pos,
                                    has_video,
                                    is_kiosk,
                                    decode_fps_display,
                                    last_decode_us,
                                );
                                renderer.queue().submit(std::iter::once(encoder.finish()));

                                    if actions.request_keyframe {
                                        let ev = zc_input::create_keyframe_request();
                                        push_input_event(&ev, &input_buffer_for_poll);
                                    }

                                    if actions.toggle_fullscreen {
                                        let is_fullscreen = window.fullscreen().is_some();
                                        if is_fullscreen {
                                            window.set_fullscreen(None);
                                        } else {
                                            window.set_fullscreen(Some(winit::window::Fullscreen::Borderless(None)));
                                        }
                                    }

                                    if actions.toggle_mute {
                                        let curr = is_audio_muted.load(Ordering::Relaxed);
                                        is_audio_muted.store(!curr, Ordering::Relaxed);
                                    }

                                    if let Some(vol) = actions.volume {
                                        let vol_pct = (vol * 100.0).clamp(0.0, 100.0) as u32;
                                        audio_volume_pct.store(vol_pct, Ordering::Relaxed);
                                    }

                                    if actions.forget_pairing {
                                        zc_network::delete_trust_data();
                                        println!("Trust data erased via UI. Reconnecting to trigger re-pairing...");
                                    }

                                    if let Some(nav) = actions.nav_action {
                                        let ev = zc_input::create_nav_event(nav);
                                        push_input_event(&ev, &input_buffer_for_poll);
                                    }

                                    // ui_state.is_muted mirrors the atomic: every control
                                    // emits toggle_mute and the atomic is the truth.
                                    overlay_ui.ui_state.is_muted = is_audio_muted.load(Ordering::Relaxed);

                                    if let Some(pkg) = actions.launch_app {
                                        // Sent as a QUIC OpenApp event only — the
                                        // phone validates it against its app
                                        // registry. No adb fallback (G8/G9).
                                        let ev = zc_input::create_open_app_event(pkg.to_string());
                                        push_input_event(&ev, &input_buffer_for_poll);
                                        window.request_redraw();
                                    }

                                    if actions.toggle_kiosk {
                                        kiosk_soft = !kiosk_soft;
                                        println!("Kiosk lock toggled via UI: {}", kiosk_soft);
                                    }

                                    if actions.reconnect {
                                        reconnect_gen_for_ui.fetch_add(1, Ordering::SeqCst);
                                        println!("Reconnect requested via UI");
                                    }

                                    if actions.exit_app && !kiosk_locked(is_kiosk, kiosk_soft) {
                                        elwt.exit();
                                    }

                                    // egui wants a repaint (an open panel's animation,
                                    // the clock's next minute): wake the loop for it.
                                    // Without this the redraw loop is event-driven and
                                    // everything the UI animates would freeze.
                                    if let Some(delay) = actions.repaint_after {
                                        elwt.set_control_flow(ControlFlow::WaitUntil(
                                            std::time::Instant::now() + delay,
                                        ));
                                    }

                                frame.present();
                            }
                            Err(wgpu::SurfaceError::Lost | wgpu::SurfaceError::Outdated) => {
                                eprintln!("SurfaceError: Lost or Outdated, resizing...");
                                renderer.resize(window.inner_size());
                            }
                            Err(wgpu::SurfaceError::OutOfMemory) => {
                                eprintln!("SurfaceError: OutOfMemory, exiting...");
                                elwt.exit();
                            }
                            Err(e) => eprintln!("SurfaceError: {:?}", e),
                        }
                    }
                    _ => (),
                }
            }
            Event::AboutToWait => {}
            _ => (),
        }
    }).unwrap_or_else(|e| {
        renderer::show_fatal_error("AndroidDex Receiver", &format!("event loop failed: {e}"));
        std::process::exit(1);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(key: bool) -> HybridFrame {
        HybridFrame {
            payload: Some(zc_protocol::video::hybrid_frame::Payload::Video(
                zc_protocol::video::VideoFrame {
                    is_keyframe: key,
                    ..Default::default()
                },
            )),
        }
    }

    #[test]
    fn video_queue_evicts_the_oldest_non_keyframe_on_a_full_queue() {
        let q = VideoFrameQueue::new(3);
        assert!(!q.push(frame(false))); // P1
        assert!(!q.push(frame(false))); // P2
        assert!(!q.push(frame(false))); // P3 (full now)
        assert!(q.push(frame(false)), "the 4th P-frame must evict P1, not block");
        let drained = q.drain();
        assert_eq!(drained.len(), 3);
        // P1 was evicted: the queue holds P2, P3, P4.
        assert!(drained.iter().all(|f| !frame_is_keyframe(f)));
    }

    #[test]
    fn video_queue_keeps_a_keyframe_when_evicting() {
        let q = VideoFrameQueue::new(2);
        assert!(!q.push(frame(true)));  // IDR
        assert!(!q.push(frame(false))); // P (full)
        // A new frame must evict the P-frame, never the IDR.
        assert!(q.push(frame(false)));
        let drained = q.drain();
        assert_eq!(drained.len(), 2);
        assert!(frame_is_keyframe(&drained[0]), "the queued IDR must survive");
    }

    #[test]
    fn video_queue_drops_an_incoming_p_frame_over_queued_idrs() {
        let q = VideoFrameQueue::new(2);
        assert!(!q.push(frame(true)));
        assert!(!q.push(frame(true)));
        // Queue holds only IDRs; an incoming P-frame is dropped itself.
        assert!(q.push(frame(false)));
        assert_eq!(q.drain().len(), 2);
    }

    #[test]
    fn video_queue_never_blocks_or_grows_past_its_bound() {
        let q = VideoFrameQueue::new(4);
        for i in 0..1000 {
            q.push(frame(i % 10 == 0));
        }
        assert!(q.drain().len() <= 4, "the queue must stay bounded");
    }
}

