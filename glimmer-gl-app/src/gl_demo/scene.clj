(ns gl-demo.scene
  "A fullscreen procedural plasma shader. No vertex buffers, no camera, no MVP:
   a single oversized triangle is generated entirely in the vertex shader from
   gl_VertexID, so it covers the whole pane regardless of size. The fragment
   shader paints a domain-warped, palette-cycling field driven by u_time and
   the Scale/Warp sliders. Robust by construction — there is nothing that can
   land offscreen or fail a depth test.")

(def vs-source
  "#version 330 core
out vec2 v_uv;
void main() {
  // Fullscreen-triangle trick: 3 verts from the index alone, no attributes.
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  vec2 ndc = p * 2.0 - 1.0;
  v_uv = ndc;
  gl_Position = vec4(ndc, 0.0, 1.0);
}")

(def fs-source
  "#version 330 core
in vec2 v_uv;
out vec4 frag;
uniform float u_time;
uniform float u_scale;
uniform float u_warp;

// Cosine palette (Inigo Quilez) — smooth cycling through the rainbow.
vec3 palette(float t) {
  vec3 a = vec3(0.5);
  vec3 b = vec3(0.5);
  vec3 c = vec3(1.0);
  vec3 d = vec3(0.00, 0.33, 0.67);
  return a + b * cos(6.28318 * (c * t + d));
}

void main() {
  vec2 uv = v_uv * u_scale;
  float t = u_time;
  // Domain warp: bend coordinates by a flowing sine/cosine field.
  vec2 q = vec2(uv.x + sin(uv.y * 2.0 + t) * u_warp,
                uv.y + cos(uv.x * 2.0 - t * 1.3) * u_warp);
  // Sum a few octaves of a sine/cosine interference pattern.
  float v = 0.0;
  float amp = 1.0;
  for (int i = 0; i < 4; i++) {
    v += amp * sin(q.x * 1.5 + t) * cos(q.y * 1.3 - t * 0.7);
    q *= 1.7;
    amp *= 0.5;
  }
  frag = vec4(palette(v * 0.5 + t * 0.08), 1.0);
}")
