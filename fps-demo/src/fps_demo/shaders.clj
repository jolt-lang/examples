(ns fps-demo.shaders
  "q1k3-style shaders-as-data, compiled by glimmer-gl.shader. Phase 0 lights the
  world with a single point light (u_light_pos / u_light_col / u_light_dist) and
  samples a texture. shader/set-uniform! handles only scalars/vectors/mat4, so
  Phase 1's per-fragment light *array* (many point lights, as in the original)
  will be declared in :prelude and set directly with gl-uniform-3fv.")

(def flat-spec
  "Unlit flat-color shader: position + per-vertex color attributes, a single
  model-view-projection. Used for the screen-space HUD (ortho), world-space blood
  particles, and the first-person shotgun viewmodel. Per-vertex color means each
  pass is one upload + one draw, no color-grouping needed."
  {:version "330 core"
   :prelude ""
   :uniforms {:u_mvp :mat4}
   :attribs {:a_pos   [:vec3 0]
             :a_color [:vec3 1]}
   :varying {:v_color :vec3}
   :fs-out {:frag_color :vec4}
   :vs-main [[:set :v_color :a_color]
             [:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]
   :fs-main [[:set :frag_color [:vec4 :v_color 1.0]]]})

;; q1k3's dynamic point-light array (renderer.js). The shader DSL has no for-loop,
;; so the array + the accumulation loop live in the raw prelude; `u_lights` is
;; declared here (not in :uniforms) so it isn't re-declared, and is uploaded each
;; frame with gl-uniform-3fv. Two vec3 per light: l[i] = position, l[i+1] = colour
;; pre-multiplied by fade*intensity (fps-demo.light/pack-lights). 16 lights => [32].
(def ^:private lit-prelude
  (str
   "uniform vec3 u_lights[32];\n"
   "vec3 accum_light(vec3 wp, vec3 nrm){\n"
   "  vec3 vl = vec3(0.0);\n"
   "  for (int i = 0; i < 32; i += 2) {\n"
   "    vec3 d = u_lights[i] - wp;\n"
   "    float dd = dot(d, d);\n"
   "    vl += max(dot(nrm, d / max(sqrt(dd), 0.0001)), 0.0) * (1.0 / max(dd, 1.0)) * u_lights[i+1];\n"
   "  }\n"
   "  return vl;\n"
   "}\n"))

(def lit-spec
  {:version "330 core"
   :prelude lit-prelude
   :uniforms
   {:u_mvp          :mat4
    :u_model        :mat4
    :u_tex          :sampler2D
    :u_num_textures [:float 31.0]
    :u_ambient      [:vec3 [0.14 0.14 0.18]]}
   :attribs
   {:a_pos       [:vec3 0]
    :a_uv        [:vec2 1]
    :a_normal    [:vec3 2]
    :a_tex_index [:float 3]}
   :varying
   {:v_uv        :vec2
    :v_tex_index :float
    :v_normal    :vec3
    :v_world_pos :vec3}
   :fs-out {:frag_color :vec4}
   :vs-main
   [[:set :v_world_pos [:. [:* :u_model [:vec4 :a_pos 1.0]] :xyz]]
    [:set :v_normal    [:* [:mat3 :u_model] :a_normal]]
    [:set :v_uv        :a_uv]
    [:set :v_tex_index :a_tex_index]
    [:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]
   :fs-main
   [[:let :tex :vec4 [:texture :u_tex
                      [:vec2 [:. :v_uv :x]
                             [:/ [:+ :v_tex_index [:fract [:. :v_uv :y]]]
                              :u_num_textures]]]]
    ;; accumulate every dynamic point light (q1k3 fragment light loop)
    [:let :lit :vec3 [:accum_light :v_world_pos [:normalize :v_normal]]]
    [:set :frag_color [:vec4 [:* [:. :tex :rgb] [:+ :u_ambient :lit]]
                       [:. :tex :a]]]]})
