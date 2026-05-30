use anyhow::{anyhow, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream, SupportedStreamConfig};
use crossbeam_queue::ArrayQueue;
use std::sync::Arc;

pub struct AudioPlayer {
    _stream: Stream,
    _config: SupportedStreamConfig,
}

#[derive(Clone)]
pub struct AudioSender {
    queue: Arc<ArrayQueue<f32>>,
}

impl AudioSender {
    pub fn play_pcm(&self, pcm_16bit_stereo_48khz: &[i16]) {
        for &sample in pcm_16bit_stereo_48khz {
            let f = sample as f32 / i16::MAX as f32;
            let _ = self.queue.push(f);
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

        // Ring buffer holding ~2 seconds of audio (48000 Hz * 2 channels * 2 seconds)
        let queue = Arc::new(ArrayQueue::new(48000 * 2 * 2));
        let queue_clone = queue.clone();

        let err_fn = |err| eprintln!("An error occurred on the output audio stream: {}", err);

        let stream = match config.sample_format() {
            SampleFormat::F32 => device.build_output_stream(
                &config.config(),
                move |data: &mut [f32], _: &cpal::OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        *sample = queue_clone.pop().unwrap_or(0.0);
                    }
                },
                err_fn,
                None,
            )?,
            SampleFormat::I16 => device.build_output_stream(
                &config.config(),
                move |data: &mut [i16], _: &cpal::OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        let f = queue_clone.pop().unwrap_or(0.0);
                        *sample = (f * i16::MAX as f32) as i16;
                    }
                },
                err_fn,
                None,
            )?,
            SampleFormat::U16 => device.build_output_stream(
                &config.config(),
                move |data: &mut [u16], _: &cpal::OutputCallbackInfo| {
                    for sample in data.iter_mut() {
                        let f = queue_clone.pop().unwrap_or(0.0);
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
        }, AudioSender { queue }))
    }
}
