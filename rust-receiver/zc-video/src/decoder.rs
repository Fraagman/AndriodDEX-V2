use openh264::decoder::Decoder as OpenH264Decoder;
use openh264::formats::YUVSource;
use openh264::nal_units;

/// A decoded YUV420 frame with separate plane buffers and per-plane strides.
///
/// Each plane is tightly packed to its own stride (which may differ from width
/// due to encoder alignment). The caller uploads Y, U, V as three separate
/// R8Unorm textures and does YUV→RGB in a fragment shader.
pub struct DecodedFrame {
    pub y: Vec<u8>,
    pub y_stride: usize,
    pub u: Vec<u8>,
    pub u_stride: usize,
    pub v: Vec<u8>,
    pub v_stride: usize,
    pub width: u32,
    pub height: u32,
}

/// Software H.264 decoder wrapping the `openh264` crate.
///
/// Decodes Annex-B NAL byte streams into YUV420 planes. 1080p decodes in
/// single-digit milliseconds on modern hardware, which is fine for LAN
/// streaming. The master prompt explicitly chose this over Media Foundation
/// to avoid FFI complexity.
pub struct Decoder {
    inner: OpenH264Decoder,
}

impl Decoder {
    /// Creates a new decoder instance. Returns an error if the openh264
    /// library cannot be initialised.
    pub fn new() -> Result<Self, openh264::Error> {
        let inner = OpenH264Decoder::new()?;
        Ok(Self { inner })
    }

    /// Decodes one or more NAL units from an Annex-B byte stream.
    ///
    /// Returns `Ok(Some(frame))` when a picture is produced, `Ok(None)` when
    /// the NAL was consumed but no picture is ready yet (e.g. SPS/PPS), or
    /// `Err` on a decode failure. The caller should request a keyframe from the
    /// phone on error rather than panicking.
    pub fn decode(&mut self, nal: &[u8]) -> Result<Option<DecodedFrame>, openh264::Error> {
        // openh264 wants individual NAL units. The phone sends Annex-B streams
        // which may contain SPS+PPS+IDR concatenated. nal_units() splits them.
        let mut last_frame = None;

        for packet in nal_units(nal) {
            match self.inner.decode(packet) {
                Ok(Some(yuv)) => {
                    let (width, height) = yuv.dimensions();
                    let w = width as u32;
                    let h = height as u32;

                    let (y_stride, u_stride, v_stride) = yuv.strides();

                    let y_data = copy_plane(yuv.y(), y_stride, width, height);
                    let u_data = copy_plane(yuv.u(), u_stride, (width + 1) / 2, (height + 1) / 2);
                    let v_data = copy_plane(yuv.v(), v_stride, (width + 1) / 2, (height + 1) / 2);

                    last_frame = Some(DecodedFrame {
                        y: y_data,
                        y_stride: width,
                        u: u_data,
                        u_stride: (width + 1) / 2,
                        v: v_data,
                        v_stride: (width + 1) / 2,
                        width: w,
                        height: h,
                    });
                }
                Ok(None) => { /* SPS/PPS consumed, no picture yet */ }
                Err(e) => return Err(e),
            }
        }

        Ok(last_frame)
    }
}

/// Copies a plane from the decoder's strided buffer into a tightly packed buffer
/// with the target width as stride. This is necessary because openh264 may pad
/// rows to alignment boundaries.
fn copy_plane(src: &[u8], src_stride: usize, width: usize, height: usize) -> Vec<u8> {
    let mut dst = Vec::with_capacity(width * height);
    for row in 0..height {
        let start = row * src_stride;
        let end = start + width.min(src.len().saturating_sub(start));
        if start < src.len() {
            dst.extend_from_slice(&src[start..end]);
            // Pad if the source row is shorter than expected
            if end - start < width {
                dst.resize(dst.len() + (width - (end - start)), 0);
            }
        } else {
            // Source has fewer rows than expected, pad with zeros
            dst.resize(dst.len() + width, 0);
        }
    }
    dst
}
