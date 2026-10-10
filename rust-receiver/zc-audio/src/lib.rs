use anyhow::{anyhow, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream, SupportedStreamConfig};
use rubato::{Resampler, FftFixedIn};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::thread;

/// Audio queued for the resampler, as normalized [-1..1] f32 samples. Bounded:
/// the push drops the OLDEST samples when the queue exceeds ~200 ms, so a stalled
/// or dead resampler can never grow the heap without bound.
const INPUT_QUEUE_MAX_SAMPLES: usize = 48_000 * 2; // ~200 ms of stereo

/// Locks a mutex, recovering from poisoning instead of panicking: every lock in
/// the audio paths runs either in the realtime output callback (a panic there is
/// UB/crash territory) or on the resampler thread (a panic dies silently and the
/// audio just stops). A poisoned VecDeque is still consistent to use.
fn lock_or_recover<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

pub struct AudioPlayer {
    _stream: Stream,
    _config: SupportedStreamConfig,
}

#[derive(Clone)]
pub struct AudioSender {
    // Interleaved stereo samples from network
    input_queue: Arc<Mutex<VecDeque<f32>>>,
}

impl AudioSender {
    pub fn play_pcm(&self, pcm_16bit_stereo_48khz: &[i16]) {
        let mut queue = lock_or_recover(&self.input_queue);
        for &sample in pcm_16bit_stereo_48khz {
            queue.push_back(sample as f32 / i16::MAX as f32);
        }
        // Drop the oldest samples when the queue runs long: stale audio is
        // worthless, and the bound keeps memory flat no matter what happens to
        // the consumer.
        let excess = queue.len().saturating_sub(INPUT_QUEUE_MAX_SAMPLES);
        if excess > 0 {
            queue.drain(0..excess);
        }
    }
}

impl AudioPlayer {
    pub fn new() -> Result<(Self, AudioSender)> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| anyhow!("No default audio output device available"))?;

        let config = device.default_output_config()?;
        let target_sample_rate = config.sample_rate().0 as usize;
        let channels = config.channels() as usize;

        let input_queue = Arc::new(Mutex::new(VecDeque::new()));
        let output_queue = Arc::new(Mutex::new(VecDeque::new()));

        let input_queue_clone = input_queue.clone();
        let output_queue_clone = output_queue.clone();

        // The resampler is created on THIS thread, before the worker spawns: a
        // failure here returns an error to the caller instead of a silently dead
        // audio thread.
        let chunk_size = 1024;
        let mut resampler = FftFixedIn::<f32>::new(48000, target_sample_rate, chunk_size, 2, 2)
            .map_err(|e| anyhow!("Failed to create resampler (48kHz -> {}Hz): {}", target_sample_rate, e))?;
        let mut input_buffers = vec![vec![0.0; chunk_size]; 2]; // Left, Right

        // Background worker for resampling
        thread::spawn(move || {
            loop {
                let frames_needed = resampler.input_frames_next();
                let samples_needed = frames_needed * 2; // Stereo input

                let mut has_enough_data = false;
                {
                    let mut in_q = lock_or_recover(&input_queue_clone);
                    if in_q.len() >= samples_needed {
                        // De-interleave
                        for i in 0..frames_needed {
                            input_buffers[0][i] = in_q.pop_front().unwrap_or(0.0); // L
                            input_buffers[1][i] = in_q.pop_front().unwrap_or(0.0); // R
                        }
                        has_enough_data = true;
                    }
                }

                if has_enough_data {
                    if let Ok(output_buffers) = resampler.process(&input_buffers, None) {
                        let out_frames = output_buffers[0].len();
                        let mut out_q = lock_or_recover(&output_queue_clone);

                        // Re-interleave and handle target channel count
                        for i in 0..out_frames {
                            let l = output_buffers[0][i];
                            let r = output_buffers[1][i];

                            if channels == 1 {
                                out_q.push_back((l + r) * 0.5);
                            } else if channels == 2 {
                                out_q.push_back(l);
                                out_q.push_back(r);
                            } else {
                                // For >2 channels (e.g. 5.1), fill L/R and silence others
                                out_q.push_back(l);
                                out_q.push_back(r);
                                for _ in 2..channels {
                                    out_q.push_back(0.0);
                                }
                            }
                        }

                        // Drift compensation: if jitter buffer (output queue) exceeds 60ms of audio,
                        // we aggressively drop old frames to catch up to real-time.
                        let max_output_samples = target_sample_rate * channels * 60 / 1000;
                        if out_q.len() > max_output_samples {
                            let excess = out_q.len() - max_output_samples;
                            out_q.drain(0..excess);
                        }
                    }
                } else {
                    thread::sleep(std::time::Duration::from_millis(2));
                }
            }
        });

        let output_queue_cpal = output_queue.clone();
        let err_fn = |err| eprintln!("An error occurred on the output audio stream: {}", err);

        let stream = match config.sample_format() {
            SampleFormat::F32 => device.build_output_stream(
                &config.config(),
                move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                    // Recover from poisoning instead of unwrapping: a panic inside
                    // the host's realtime callback is crash/UB territory.
                    let mut out_q = lock_or_recover(&output_queue_cpal);
                    for sample in data.iter_mut() {
                        *sample = out_q.pop_front().unwrap_or(0.0);
                    }
                },
                err_fn,
                None,
            )?,
            SampleFormat::I16 => device.build_output_stream(
                &config.config(),
                move |data: &mut [i16], _: &cpal::OutputCallbackInfo| {
                    let mut out_q = lock_or_recover(&output_queue_cpal);
                    for sample in data.iter_mut() {
                        let f = out_q.pop_front().unwrap_or(0.0);
                        *sample = (f * i16::MAX as f32) as i16;
                    }
                },
                err_fn,
                None,
            )?,
            SampleFormat::U16 => device.build_output_stream(
                &config.config(),
                move |data: &mut [u16], _: &cpal::OutputCallbackInfo| {
                    let mut out_q = lock_or_recover(&output_queue_cpal);
                    for sample in data.iter_mut() {
                        let f = out_q.pop_front().unwrap_or(0.0);
                        *sample = ((f * i16::MAX as f32) as i16 as u16).wrapping_add(32768);
                    }
                },
                err_fn,
                None,
            )?,
            format => return Err(anyhow!("Unsupported sample format: {:?}", format)),
        };

        stream.play()?;

        Ok((Self {
            _stream: stream,
            _config: config,
        }, AudioSender { input_queue }))
    }
}
