// YUV→RGB conversion shader for the H.264 pipeline.
//
// The decoder uploads Y, U, and V as three separate R8Unorm textures.
// This shader samples all three and applies the BT.601 limited-range
// conversion matrix, which matches what Android's hardware H.264 encoder
// produces. The conversion is done entirely on the GPU — no CPU colour
// convert ever touches the frame data.

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

// Full-screen triangle trick: 3 vertices, no vertex buffer needed.
// Vertex IDs 0, 1, 2 produce a triangle that covers the entire screen.
@vertex
fn vs_main(@builtin(vertex_index) vertex_index: u32) -> VertexOutput {
    var out: VertexOutput;
    // Generate oversized triangle from vertex index
    let x = f32(i32(vertex_index & 1u) * 4 - 1);
    let y = f32(i32(vertex_index >> 1u) * 4 - 1);
    out.clip_position = vec4<f32>(x, y, 0.0, 1.0);
    // UV: map clip space to texture coordinates, flip Y
    out.uv = vec2<f32>(x * 0.5 + 0.5, 0.5 - y * 0.5);
    return out;
}

@group(0) @binding(0) var t_y: texture_2d<f32>;
@group(0) @binding(1) var t_u: texture_2d<f32>;
@group(0) @binding(2) var t_v: texture_2d<f32>;
@group(0) @binding(3) var s_yuv: sampler;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    // Sample each plane. R8Unorm textures return the value in the .r component.
    let y_val = textureSample(t_y, s_yuv, in.uv).r;
    let u_val = textureSample(t_u, s_yuv, in.uv).r;
    let v_val = textureSample(t_v, s_yuv, in.uv).r;

    // BT.601 limited range: Y [16..235], UV [16..240]
    // Normalise to [0..1] range first, then apply matrix.
    let y = (y_val - 16.0 / 255.0) * (255.0 / 219.0);
    let u = (u_val - 128.0 / 255.0) * (255.0 / 224.0);
    let v = (v_val - 128.0 / 255.0) * (255.0 / 224.0);

    // BT.601 YUV→RGB matrix
    let r = clamp(y + 1.402 * v, 0.0, 1.0);
    let g = clamp(y - 0.344136 * u - 0.714136 * v, 0.0, 1.0);
    let b = clamp(y + 1.772 * u, 0.0, 1.0);

    return vec4<f32>(r, g, b, 1.0);
}
