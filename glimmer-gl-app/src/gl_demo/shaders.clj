(ns gl-demo.shaders
  "The surface shader, assembled from independent shader *modules* with
   glimmer-gl.shader/merge-specs to demonstrate composition. Each module is a
   self-contained data map carrying its own uniforms plus the GLSL function that
   uses them:

     plasma-module  -> vec3 plasma_color(vec3 p, float t)   (u_scale, u_warp)
     stripes-module -> vec3 stripe_color(vec3 p)            (u_stripes)

   `base` provides the vertex stage and framing uniforms; `main-module` blends
   the two effects by `u_mix` and lights the result. `shader-spec` is just
   `(merge-specs base plasma-module stripes-module main-module)` — uniforms from
   every module merge, and the helper functions concatenate ahead of main(), so
   the whole shader is composed as data and only emitted as GLSL at compile time.
   Drop a module from the merge, or add a third, to change the look."
  (:require [glimmer.core   :as ui]
            [glimmer.ratom  :as r]
            [glimmer-gl.gtk :as glx]        ; registers :gl-area + :scale
            [glimmer-gl.gl  :as gl]
            [glimmer-gl.mesh :as mesh]
            [glimmer-gl.primitives :as p]
            [glimmer-gl.matrix :as m]
            [glimmer-gl.shader :as sh]
            [gl-demo.scene  :as scene]
            [jolt.ffi       :as ffi]))

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
   :vs "void main() {
  v_obj = a_pos;
  v_normal = mat3(u_model) * a_normal;
  gl_Position = u_mvp * vec4(a_pos, 1.0);
}"})

;; --- module 1: domain-warped plasma ------------------------------------------
(def plasma-module
  {:uniforms {:u_scale [:float 3.0] :u_warp [:float 0.5]}
   :fs ["// Inigo Quilez cosine palette.
vec3 palette(float t) {
  vec3 a = vec3(0.5), b = vec3(0.5), c = vec3(1.0), d = vec3(0.00, 0.33, 0.67);
  return a + b * cos(6.28318 * (c * t + d));
}"
        "// Domain-warped, 4-octave plasma over a 2D coordinate.
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
}"
        "vec3 plasma_color(vec3 p, float t) {
  float v = plasma(p.xy, t + p.z * 1.5, u_scale, u_warp);
  return palette(v * 0.5 + t * 0.08);
}"]})

;; --- module 2: animated gold stripes -----------------------------------------
(def stripes-module
  {:uniforms {:u_stripes [:float 8.0]}
   :fs ["vec3 stripe_color(vec3 p, float t) {
  float s = 0.5 + 0.5 * sin((p.y + p.x * 0.5) * u_stripes + t);
  return mix(vec3(0.04, 0.04, 0.08), vec3(1.0, 0.82, 0.28), smoothstep(0.45, 0.55, s));
}"]})

;; --- combining main ----------------------------------------------------------
(def main-module
  {:uniforms {:u_mix [:float 0.5]}
   :fs ["out vec4 frag;
void main() {
  vec3 a = plasma_color(v_obj, u_time);
  vec3 b = stripe_color(v_obj, u_time);
  vec3 col = mix(a, b, u_mix);
  float diff = max(dot(normalize(v_normal), normalize(u_light)), 0.0);
  frag = vec4(col * (0.35 + 0.65 * diff), 1.0);
}"]})

;; --- the composed shader -----------------------------------------------------
(def shader-spec
  (sh/merge-specs base plasma-module stripes-module main-module))

;; --- control state (reactive — the panel re-renders on change) ---------------
(defonce shape   (r/atom :cube))
(defonce speed   (r/atom 1.0))
(defonce zoom    (r/atom 1.0))    ; model scale
(defonce p-scale (r/atom 3.0))    ; plasma pattern frequency (u_scale)
(defonce warp    (r/atom 0.5))    ; plasma domain-warp strength (u_warp)
(defonce blend   (r/atom 0.5))    ; mix between the plasma and stripe modules (u_mix)
(defonce smooth  (r/atom false))  ; per-vertex (smooth) vs per-face (flat) normals
(defonce paused  (r/atom false))

;; --- animation / GL state (plain atoms — not read by the panel) --------------
(defonce clock    (atom 0.0))     ; drives rotation angle and plasma u_time
(defonce viewport (atom [900 560]))
;; Per-GLArea GL handles, keyed by the area widget pointer.
(defonce gl-state (atom {}))

(def ^:private frame-dt 0.016)
(def ^:private light [0.4 0.85 0.6])     ; directional light

;; --- geometry ----------------------------------------------------------------
(defn- build-mesh [shape]
  (case shape
    :sphere (p/sphere 1.0 28 18)
    :tetra  (p/tetrahedron 1.35)
    (p/cuboid 1.5)))

(defn- buffer-for [shape smooth?]
  (mesh/->floats (build-mesh shape) {:shading (if smooth? :smooth :flat)}))

;; --- GL plumbing -------------------------------------------------------------
(def ^:private stride-bytes (* 6 (ffi/sizeof :float)))

(defn- upload!
  "(Re)fill the bound VBO from the mesh for `shape`/`smooth?`. Returns the vertex
   count to draw."
  [vbo shape smooth?]
  (let [{:keys [data] vcount :count} (buffer-for shape smooth?)
        ptr (gl/write-floats data)]
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                       (* (count data) (ffi/sizeof :float))
                       ptr gl/GL-STATIC-DRAW)
    (ffi/free ptr)
    vcount))

(defn- setup-attribs!
  "Wire the interleaved VBO to the shader's a_pos / a_normal attributes, using the
   locations the compiled shader resolved."
  [shader]
  (let [pos (sh/attrib-loc shader :a_pos)
        nrm (sh/attrib-loc shader :a_normal)]
    (when (>= pos 0)
      (gl/gl-enable-vertex-attrib-array pos)
      ;; byte offsets pass as plain integers — jolt pointers are addresses
      (gl/gl-vertex-attrib-pointer pos 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes 0))
    (when (>= nrm 0)
      (gl/gl-enable-vertex-attrib-array nrm)
      (gl/gl-vertex-attrib-pointer nrm 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes
                                   (* 3 (ffi/sizeof :float))))))

;; --- GLArea handlers ---------------------------------------------------------
(defn on-realize [area]
  (glx/make-current area)
  (when-let [err (glx/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [shader (try (sh/program scene/shader-spec)
                    (catch Throwable _ nil))]
    (if-not shader
      (println "gl-demo: failed to build GL program (see info log above)")
      (let [idp (ffi/alloc (ffi/sizeof :uint))]
        (gl/gl-gen-vertex-arrays 1 idp)
        (let [vao (ffi/read idp :uint)]
          (gl/gl-gen-buffers 1 idp)
          (let [vbo (ffi/read idp :uint)]
            (ffi/free idp)
            (gl/gl-enable gl/GL-DEPTH-TEST)
            (gl/gl-bind-vertex-array vao)
            (let [n (upload! vbo @shape @smooth)]
              (setup-attribs! shader)
              (swap! gl-state assoc area
                     {:shader shader :vao vao :vbo vbo :count n
                      :shape @shape :smooth @smooth})
              (println "gl-demo: GL ready — program" (:program shader)
                       "vao" vao "verts" n))))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

(defn on-render [area]
  (when-let [st (get @gl-state area)]
    ;; rebuild the VBO if the shape or shading changed since the last upload
    (let [st (if (or (not= (:shape st) @shape) (not= (:smooth st) @smooth))
               (let [n (upload! (:vbo st) @shape @smooth)
                     st' (assoc st :count n :shape @shape :smooth @smooth)]
                 (swap! gl-state assoc area st') st')
               st)
          shader (:shader st)
          [w h]  @viewport
          aspect (/ (double w) (max 1.0 (double h)))
          t      (double @clock)
          s      (double @zoom)
          model  (m/mul (m/mul (m/rotate-y t) (m/rotate-x (* t 0.5)))
                        (m/scaling s s s))
          view   (m/translation 0.0 0.0 -4.5)
          proj   (m/perspective 50.0 aspect 0.1 100.0)
          mvp    (m/mul proj (m/mul view model))]
      (gl/gl-clear-color 0.05 0.06 0.09 1.0)
      (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
      (gl/gl-use-program (:program shader))
      (sh/set-uniforms! shader
        {:u_mvp     mvp
         :u_model   model
         :u_time    t
         :u_scale   @p-scale
         :u_warp    @warp
         :u_mix     @blend
         :u_stripes 8.0
         :u_light   light})
      (gl/gl-bind-vertex-array (:vao st))
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 (:count st)))))

(defn on-tick [_area]
  (when-not @paused
    (swap! clock + (* (double @speed) frame-dt))))

;; --- reactive control panel --------------------------------------------------
(defn- slider [label-text lo hi step value-atom]
  [:hbox {:spacing 8}
   [:label {:label label-text :width-chars 6 :xalign 0.0}]
   [:scale {:min lo :max hi :step step :value @value-atom :digits 2 :hexpand true
            :on-value #(reset! value-atom %)}]])

(defn- shape-button [label kw]
  [:button {:label label
            :sensitive (not= @shape kw)        ; the active shape is greyed out
            :on-click #(reset! shape kw)}])

(defn- control-panel []
  [:vbox {:spacing 6 :margin 8}
   [:hbox {:spacing 6}
    [shape-button "Cube" :cube]
    [shape-button "Sphere" :sphere]
    [shape-button "Tetra" :tetra]]
   [slider "Speed" 0.0 4.0 0.05 speed]
   [slider "Zoom"  0.3 2.5 0.05 zoom]
   [slider "Scale" 0.5 8.0 0.1  p-scale]
   [slider "Warp"  0.0 1.5 0.05 warp]
   [slider "Blend" 0.0 1.0 0.05 blend]      ; plasma <-> stripes
   [:hbox {:spacing 12}
    [:checkbutton {:label "Smooth shading" :active @smooth
                   :on-toggled #(swap! smooth not)}]
    [:button {:label (if @paused "Resume" "Pause")
              :on-click #(swap! paused not)}]]])

(defn app []
  [:vbox {:spacing 0}
   [control-panel]
   [:separator {}]
   [:gl-area {:version [3 2] :depth-buffer true :hexpand true :vexpand true
              :on-realize on-realize
              :on-render  on-render
              :on-resize  on-resize
              :on-tick    on-tick}]])

(defn -main [& _]
  ;; GLIMMER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke testing);
  ;; unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLIMMER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply ui/run app
           :app-id "dev.jolt.glimmer-gl-demo"
           :title  "glimmer-gl • plasma mesh"
           :width  900 :height 640
           (when quit-ms [:auto-quit-ms quit-ms]))))