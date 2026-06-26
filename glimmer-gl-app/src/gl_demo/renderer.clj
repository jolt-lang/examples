(ns gl-demo.renderer
  "Thin GL realization of a declarative scene plan. The scene is data (produced
  by glimmer-gl.scene/flatten from a hiccup tree of gothic geometry); this
  module's job is the one inherently-imperative step — turning that data into GL
  draw calls. It mirrors glimmer's separation of content from reconciliation:
  meshes are uploaded lazily and cached by identity (equal subtrees reuse one
  VBO, like glimmer reuses a widget where shape matches), and each frame runs a
  two-pass shadow-mapping render (depth-only from the light, then lit with the
  depth texture sampled for shadows)."
  (:require [glimmer-gl.gl     :as gl]
            [glimmer-gl.matrix :as m]
            [glimmer-gl.mesh   :as mesh]
            [glimmer-gl.shader :as sh]
            [gl-demo.scene     :as scene]
            [jolt.ffi          :as ffi]))

;; material keyword -> base RGB. Resolved here (GL realization), not in the
;; declarative geometry, which only names materials.
(def material-colors
  {:stone      [0.55 0.52 0.48]
   :dark-stone [0.28 0.26 0.24]
   :ground     [0.16 0.14 0.13]
   :gold       [0.85 0.68 0.24]
   :glass      [0.50 0.45 0.62]})

(def ^:const shadow-w 2048)
(def ^:const shadow-h 2048)

(def ^:private float-size (ffi/sizeof :float))
(def ^:private stride-bytes (* 6 float-size))   ; pos(3) + normal(3)

;; --- GL object helpers -------------------------------------------------------
(defn- gen-one [gen-fn]
  (let [p (ffi/alloc (ffi/sizeof :uint))]
    (gen-fn 1 p)
    (let [id (ffi/read p :uint)]
      (ffi/free p) id)))

;; The framebuffer currently bound (the host surface GTK set up for this frame).
;; On macOS, GtkGLArea renders into its own FBO, so binding 0 for the lit pass
;; is an invalid framebuffer op — capture this at draw start and restore it.
(defn- current-framebuffer []
  (let [p (ffi/alloc (ffi/sizeof :int))]
    (gl/gl-get-integerv gl/GL-FRAMEBUFFER-BINDING p)
    (let [v (ffi/read p :int)]
      (ffi/free p) v)))

;; Upload one mesh's interleaved pos+normal buffer once; returns {:vao :vbo :count}.
;; Cached by the caller keyed on the Mesh record (value equality), so every copy
;; of e.g. a column shares a single upload.
(defn- upload-mesh [geom]
  (let [vao (gen-one gl/gl-gen-vertex-arrays)
        vbo (gen-one gl/gl-gen-buffers)
        {:keys [data] cnt :count} (mesh/->floats geom {:shading :flat})
        ptr (gl/write-floats data)]
    (gl/gl-bind-vertex-array vao)
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER (* (count data) float-size) ptr gl/GL-STATIC-DRAW)
    (ffi/free ptr)
    (gl/gl-enable-vertex-attrib-array 0)            ; a_pos @ loc 0
    (gl/gl-vertex-attrib-pointer 0 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes 0)
    (gl/gl-enable-vertex-attrib-array 1)            ; a_normal @ loc 1
    (gl/gl-vertex-attrib-pointer 1 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes (* 3 float-size))
    (gl/gl-bind-vertex-array 0)
    {:vao vao :vbo vbo :count cnt}))

;; A depth-only framebuffer + depth texture configured as a shadow sampler
;; (hardware compare, LEQUAL, white border so out-of-frustum reads as lit).
(defn- make-shadow-map! []
  (let [tex    (gen-one gl/gl-gen-textures)
        fbo    (gen-one gl/gl-gen-framebuffers)]
    (gl/gl-bind-texture gl/GL-TEXTURE-2D tex)
    (gl/gl-tex-image-2d gl/GL-TEXTURE-2D 0 gl/GL-DEPTH-COMPONENT24
                        shadow-w shadow-h 0 gl/GL-DEPTH-COMPONENT gl/GL-FLOAT ffi/null)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MIN-FILTER gl/GL-NEAREST)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MAG-FILTER gl/GL-NEAREST)
    ;; GL_CLAMP_TO_BORDER is unsupported on Apple's GL (GL_INVALID_ENUM); use
    ;; CLAMP_TO_EDGE. Out-of-frustum samples are masked in the lit shader anyway.
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-S gl/GL-CLAMP-TO-EDGE)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-T gl/GL-CLAMP-TO-EDGE)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-COMPARE-MODE gl/GL-COMPARE-REF-TO-TEXTURE)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-COMPARE-FUNC gl/GL-LEQUAL)
    (gl/gl-bind-framebuffer gl/GL-FRAMEBUFFER fbo)
    (gl/gl-framebuffer-texture-2d gl/GL-FRAMEBUFFER gl/GL-DEPTH-ATTACHMENT gl/GL-TEXTURE-2D tex 0)
    (gl/gl-draw-buffer gl/GL-NONE)
    (let [status (gl/gl-check-framebuffer-status gl/GL-FRAMEBUFFER)]
      (when (not= status gl/GL-FRAMEBUFFER-COMPLETE)
        (println "[renderer] shadow framebuffer incomplete, status =" status)))
    (gl/gl-bind-framebuffer gl/GL-FRAMEBUFFER 0)
    {:fbo fbo :tex tex}))

;; Compile both programs and create the shadow map. Requires a current GL context.
(defn make-renderer! []
  (atom {:depth  (sh/program scene/depth-spec)
         :lit    (sh/program scene/lit-spec)
         :shadow (make-shadow-map!)
         :meshes {}}))

;; Ensure every mesh in the plan has an uploaded VAO; mutates the state atom's
;; :meshes cache (idempotent — already-uploaded meshes are skipped).
(defn- ensure-meshes! [st plan]
  (doseq [{:keys [geom]} (:items plan)]
    (when-not (get-in @st [:meshes geom])
      (swap! st assoc-in [:meshes geom] (upload-mesh geom)))))

;; ctx keys: :plan :view :proj :eye :canvas :bg :light {:dir :color :lview :lproj}
;;           :ambient :fog {:near :far :color} :shadow-bias
(defn draw! [st ctx]
  (let [plan   (:plan ctx)
        default-fbo (current-framebuffer)
        {:keys [dir color lview lproj]} (:light ctx)
        vp     (m/mul (:proj ctx) (:view ctx))
        lvp    (m/mul lproj lview)
        depth  (:depth @st)
        lit    (:lit @st)
        shadow (:shadow @st)]
    (ensure-meshes! st plan)
    (let [meshes (:meshes @st)
          items  (:items plan)]
      ;; ---- shadow pass: render depth only, from the light's POV ----
      (gl/gl-bind-framebuffer gl/GL-FRAMEBUFFER (:fbo shadow))
      (gl/gl-viewport 0 0 shadow-w shadow-h)
      (gl/gl-clear gl/GL-DEPTH-BUFFER-BIT)
      (gl/gl-enable gl/GL-DEPTH-TEST)
      (gl/gl-enable gl/GL-CULL-FACE)
      (gl/gl-cull-face gl/GL-FRONT)                  ; back faces only -> less acne
      (gl/gl-use-program (:program depth))
      (doseq [{:keys [geom world cast-shadow]} items]
        (when cast-shadow
          (let [upload (get meshes geom)]
            (gl/gl-bind-vertex-array (:vao upload))
            (sh/set-uniforms! depth {:u_mvp (m/mul lvp world)})
            (gl/gl-draw-arrays gl/GL-TRIANGLES 0 (:count upload)))))
      ;; ---- lit pass: Blinn-Phong modulated by the shadow sampler ----
      (gl/gl-bind-framebuffer gl/GL-FRAMEBUFFER default-fbo)
      (let [[cw ch] (:canvas ctx)]
        (gl/gl-viewport 0 0 cw ch))
      (let [bg (:bg ctx)]
        (gl/gl-clear-color (nth bg 0) (nth bg 1) (nth bg 2) 1.0))
      (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
      (gl/gl-enable gl/GL-DEPTH-TEST)
      (gl/gl-enable gl/GL-CULL-FACE)
      (gl/gl-cull-face gl/GL-BACK)
      (gl/gl-active-texture gl/GL-TEXTURE0)
      (gl/gl-bind-texture gl/GL-TEXTURE-2D (:tex shadow))
      (gl/gl-use-program (:program lit))
      (sh/set-uniforms! lit
        {:u_light_dir   dir
         :u_light_color color
         :u_ambient     (:ambient ctx)
         :u_camera_pos  (:eye ctx)
         :u_shadow_map  0
         :u_shadow_bias (:shadow-bias ctx)
         :u_fog_near    (get-in ctx [:fog :near])
         :u_fog_far     (get-in ctx [:fog :far])
         :u_fog_color   (get-in ctx [:fog :color])})
      (doseq [{:keys [geom world material cast-shadow]} items]
        (let [upload (get meshes geom)]
          (gl/gl-bind-vertex-array (:vao upload))
          (sh/set-uniforms! lit
            {:u_mvp       (m/mul vp world)
             :u_model     world
             :u_light_mvp (m/mul lvp world)
             :u_color     (get material-colors material [0.5 0.5 0.5])})
          (gl/gl-draw-arrays gl/GL-TRIANGLES 0 (:count upload)))))))
