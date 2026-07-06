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

(def lit-spec
  {:version "330 core"
   :prelude ""
   :uniforms
   {:u_mvp        :mat4
    :u_model      :mat4
    :u_tex        :sampler2D
    :u_ambient    [:vec3 [0.18 0.18 0.22]]
    :u_light_pos  [:vec3 [0.0 3.0 0.0]]
    :u_light_col  [:vec3 [1.0 0.85 0.6]]
    :u_light_dist [:float 8.0]}
   :attribs
   {:a_pos    [:vec3 0]
    :a_uv     [:vec2 1]
    :a_normal [:vec3 2]}
   :varying
   {:v_uv        :vec2
    :v_normal    :vec3
    :v_world_pos :vec3}
   :fs-out {:frag_color :vec4}
   :vs-main
   [[:set :v_world_pos [:. [:* :u_model [:vec4 :a_pos 1.0]] :xyz]]
    [:set :v_normal    [:* [:mat3 :u_model] :a_normal]]
    [:set :v_uv        :a_uv]
    [:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]
   :fs-main
   [[:let :tex      :vec4 [:texture :u_tex :v_uv]]
    [:let :to_light :vec3 [:- :u_light_pos :v_world_pos]]
    [:let :dist     :float [:length :to_light]]
    [:let :dir      :vec3 [:/ :to_light [:max :dist 0.0001]]]
    [:let :ndotl    :float [:max [:dot :v_normal :dir] 0.0]]
    [:let :r2       :float [:* :u_light_dist :u_light_dist]]
    [:let :atten    :float [:/ 1.0 [:+ 1.0 [:/ [:* :dist :dist] :r2]]]]
    [:let :lit      :vec3 [:* :u_light_col [:* :ndotl :atten]]]
    [:set :frag_color [:vec4 [:* [:. :tex :rgb] [:+ :u_ambient :lit]]
                       [:. :tex :a]]]]})
