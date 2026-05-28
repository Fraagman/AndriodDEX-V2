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
    out.uv = model.position * 0.5 + 0.5;
    return out;
}

@group(0) @binding(0)
var<uniform> time: f32;

@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let r = fract(in.uv.x + time * 0.1);
    let g = fract(in.uv.y + time * 0.2);
    let b = 0.5;
    return vec4<f32>(r, g, b, 1.0);
}
