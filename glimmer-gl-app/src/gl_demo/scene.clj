(ns gl-demo.scene
  "Shaders-as-data for the gothic shadow demo. Two specs, each a plain map
  compiled by glimmer-gl.shader:

    depth-spec  — the shadow-map pass. Writes nothing to color; the depth-only
                  framebuffer captures the light-space z of every fragment.
                  Shares a_pos at location 0 with lit-spec so both run on the
                  same uploaded VAOs.

    lit-spec    — the lit pass. Blinn-Phong diffuse+specular modulated by a
                  sampler2DShadow lookup of the depth map, plus distance fog for
                  the cathedral mood. Per-mesh uniforms (u_model, u_color, the
                  two MVPs) are set per item by the renderer.

  Because both specs are data you can (assoc) a uniform or swap a body without
  touching GL — see glimmer-gl.shader/merge-specs for composition."
  (:require [glimmer-gl.shader :as sh]))

;; --- shadow-map pass: light-space depth only --------------------------------
(def depth-spec
  {:version "330 core"
   :uniforms {:u_mvp :mat4}
   :attribs  {:a_pos [:vec3 0]}
   :vs "void main() { gl_Position = u_mvp * vec4(a_pos, 1.0); }"
   :fs "void main() {}"})

;; --- lit pass: Blinn-Phong + sampled shadow + distance fog -------------------
(def lit-spec
  {:version "330 core"
   :uniforms {:u_mvp          :mat4         ; camera proj * view * model
              :u_model        :mat4         ; model -> world (normals use its mat3)
              :u_light_mvp    :mat4         ; light proj * view * model (shadow coord)
              :u_light_dir    :vec3         ; direction the light travels
              :u_light_color  :vec3
              :u_ambient      :vec3
              :u_color        :vec3         ; base material color
              :u_camera_pos   :vec3
              :u_shadow_map   :sampler2DShadow ; texture unit 0, bound to the depth texture
              :u_shadow_bias  :float
              :u_fog_near     :float
              :u_fog_far      :float
              :u_fog_color    :vec3}
   :attribs  {:a_pos   [:vec3 0]
              :a_normal [:vec3 1]}
   :varying  {:v_world_pos :vec3
              :v_normal    :vec3
              :v_lpos      :vec4}
   :vs ["void main() {
  v_world_pos = (u_model * vec4(a_pos, 1.0)).xyz;
  v_normal    = mat3(u_model) * a_normal;
  v_lpos      = u_light_mvp * vec4(a_pos, 1.0);
  gl_Position = u_mvp * vec4(a_pos, 1.0);
}"]
   :fs ["out vec4 frag_color;
void main() {
  // project the light-space coordinate into the depth-texture's [0,1] sample window
  vec3 sc = (v_lpos.xyz / v_lpos.w) * 0.5 + 0.5;
  // outside the shadow frustum: treat as fully lit (no occluder recorded there)
  float shadow = 1.0;
  if (sc.x >= 0.0 && sc.x <= 1.0 && sc.y >= 0.0 && sc.y <= 1.0 && sc.z <= 1.0) {
    shadow = texture(u_shadow_map, vec3(sc.xy, sc.z - u_shadow_bias));
  }
  vec3 N = normalize(v_normal);
  vec3 L = normalize(-u_light_dir);
  float diff = max(dot(N, L), 0.0);
  vec3 V = normalize(u_camera_pos - v_world_pos);
  vec3 H = normalize(L + V);
  float spec = pow(max(dot(N, H), 0.0), 32.0) * step(0.0, diff);
  vec3 lit = u_ambient + u_light_color * (diff + spec) * shadow;
  vec3 col = u_color * lit;
  float dist  = length(u_camera_pos - v_world_pos);
  float fog   = clamp((u_fog_far - dist) / (u_fog_far - u_fog_near), 0.0, 1.0);
  frag_color  = vec4(mix(u_fog_color, col, fog), 1.0);
}"]})
