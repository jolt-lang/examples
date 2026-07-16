(ns gl-demo.scene
  "The surface shader, assembled from independent shader *modules* with
   glimmer-gl.shader/merge-specs to demonstrate composition. Each module is a
   self-contained data map carrying its own uniforms plus its effect:

     plasma-module  -> vec3 plasma_color(vec3 p, float t)   (u_scale, u_warp)
     stripes-module -> vec3 stripe_color(vec3 p)            (u_stripes)

   `base` provides the vertex stage and framing uniforms; `main-module` blends
   the two effects by `u_mix` and lights the result. `shader-spec` is just
   `(merge-specs base plasma-module stripes-module main-module)` — uniforms from
   every module merge, helper functions go into :prelude where they precede
   main(), and the :vs-main/:fs-main statement vectors concatenate. The whole
   shader is composed as data and only emitted as GLSL at compile time.
   Drop a module from the merge, or add a third, to change the look."
  (:require [glimmer-gl.shader :as sh]))

;; --- shared vertex stage + framing -------------------------------------------
(def base
  {:version  "330 core"
   :uniforms {:u_mvp   :mat4
              :u_model :mat4
              :u_time  [:float 0.0]
              :u_light [:vec3 [0.4 0.85 0.6]]}
   :attribs  {:a_pos    [:vec3 0]
              :a_normal [:vec3 1]}
   :varying  {:v_obj :vec3 :v_normal :vec3}
   :vs-main  [[:set :v_obj :a_pos]
              [:set :v_normal [:* [:mat3 :u_model] :a_normal]]
              [:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]})

;; --- module 1: domain-warped plasma ------------------------------------------
;; Helper functions go in :prelude since the data IR only compiles main() bodies.
(def plasma-module
  {:uniforms {:u_scale [:float 3.0] :u_warp [:float 0.5]}
   :prelude
   "// Inigo Quilez cosine palette.
vec3 palette(float t) {
  vec3 a = vec3(0.5), b = vec3(0.5), c = vec3(1.0), d = vec3(0.00, 0.33, 0.67);
  return a + b * cos(6.28318 * (c * t + d));
}
// Domain-warped, 4-octave plasma over a 2D coordinate.
float plasma(vec2 uv, float t, float scale, float warp) {
  uv *= scale;
  vec2 q = vec2(uv.x + sin(uv.y * 2.0 + t) * warp,
                uv.y + cos(uv.x * 2.0 - t * 1.3) * warp);
  float v = 0.0, amp = 1.0;
  for (int i = 0; i < 4; i++) {
    v += amp * sin(q.x * 1.5 + t) * cos(q.y * 1.3 - t * 0.7);
    q *= 1.7; amp *= 0.5;
  }
  return v;
}
vec3 plasma_color(vec3 p, float t) {
  float v = plasma(p.xy, t + p.z * 1.5, u_scale, u_warp);
  return palette(v * 0.5 + t * 0.08);
}
"})

;; --- module 2: animated gold stripes -----------------------------------------
(def stripes-module
  {:uniforms {:u_stripes [:float 8.0]}
   :prelude
   "vec3 stripe_color(vec3 p, float t) {
  float s = 0.5 + 0.5 * sin((p.y + p.x * 0.5) * u_stripes + t);
  return mix(vec3(0.04, 0.04, 0.08), vec3(1.0, 0.82, 0.28), smoothstep(0.45, 0.55, s));
}
"})

;; --- combining main ----------------------------------------------------------
(def main-module
  {:uniforms {:u_mix [:float 0.5]}
   :fs-out   {:frag :vec4}
   :fs-main  [[:let :a :vec3 [:plasma_color :v_obj :u_time]]
              [:let :b :vec3 [:stripe_color :v_obj :u_time]]
              [:let :col :vec3 [:mix :a :b :u_mix]]
              [:let :diff :float [:max [:dot [:normalize :v_normal] [:normalize :u_light]] 0.0]]
              [:set :frag [:vec4 [:* :col [:+ 0.35 [:* 0.65 :diff]]] 1.0]]]})

;; --- the composed shader -----------------------------------------------------
(def shader-spec
  (sh/merge-specs base plasma-module stripes-module main-module))
