struct VertexInput {
    @location(0) position: vec2<f32>,
};

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(model: VertexInput) -> VertexOutput {
    var out: VertexOutput;
    out.clip_position = vec4<f32>(model.position, 0.0, 1.0);
    // map -1..1 to 0..1 for uv, and flip y axis because texture coordinates have 0,0 at top-left
    out.uv = vec2<f32>(model.position.x * 0.5 + 0.5, 0.5 - model.position.y * 0.5);
    return out;
}

struct Uniforms {
    time: f32,
    use_texture: u32,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(0) @binding(1) var t_diffuse: texture_2d<f32>;
@group(0) @binding(2) var s_diffuse: sampler;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    if (uniforms.use_texture == 1u) {
        return textureSample(t_diffuse, s_diffuse, in.uv);
    } else {
        let r = fract(in.uv.x + uniforms.time * 0.1);
        let g = fract(in.uv.y + uniforms.time * 0.2);
        let b = 0.5;
        return vec4<f32>(r, g, b, 1.0);
    }
}
