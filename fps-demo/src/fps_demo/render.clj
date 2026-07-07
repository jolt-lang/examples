(ns fps-demo.render
  "The q1k3 draw loop, separate from glimmer-gl's flat-color renderer. On the
  first realize it compiles the textured point-light shader, uploads one textured
  box, and builds a procedural checkerboard texture; each render sets the camera
  + light uniforms and draws. This is the Phase 0 proof: textured geometry, a
  point light, and a working custom pipeline.

  State lives in an atom so on-realize can hand it to on-render."
  (:require [glimmer-gl.gl      :as gl]
            [glimmer-gl.shader  :as shader]
            [glimmer-gl.matrix  :as mat]
            [jolt.ffi           :as ffi]
            [fps-demo.shaders   :as sh]
            [fps-demo.textured  :as tex]
            [fps-demo.texture   :as ttt]
            [fps-demo.textures  :as textures]
            [fps-demo.player    :as player]
            [fps-demo.map       :as lvl]
            [fps-demo.maps.l    :as lvl-data]
            [fps-demo.model     :as model]
            [fps-demo.models.unit :as unit]
            [fps-demo.overlay   :as ov]
            [fps-demo.light     :as light]
            [fps-demo.hud       :as hud]))

;; pixel-format constants not exported by glimmer-gl (its renderer is texture-free)
(def ^:private GL-RGBA8          (int 0x8058))
(def ^:private GL-RGBA           (int 0x1908))
(def ^:private GL-UNSIGNED-BYTE  (int 0x1401))
(def ^:private GL-FALSE          (int 0))
(def ^:private GL-DYNAMIC-DRAW   (int 0x88E8))

;; libc file write, for the offscreen screenshot path (glReadPixels -> raw file).
;; Only used behind FPS_DEMO_SHOT; keeps a headless visual-verification hook.
(ffi/defcfn ^:private c-fopen  "fopen"  [:string :string] :pointer)
(ffi/defcfn ^:private c-fwrite "fwrite" [:pointer :size_t :size_t :pointer] :size_t)
(ffi/defcfn ^:private c-fclose "fclose" [:pointer] :int)

(defn save-screenshot!
  "Read the current framebuffer (w×h RGBA) and write it as raw bytes to `path`
  via libc fwrite. Must run with the GL context current (i.e. from on-render).
  Returns [w h] — pixels are bottom-row-first (GL origin), so the reader flips."
  [w h path]
  (let [n   (* (int w) (int h) 4)
        ptr (ffi/alloc n)]
    (gl/gl-read-pixels 0 0 (int w) (int h) GL-RGBA GL-UNSIGNED-BYTE ptr)
    (let [f (c-fopen path "wb")]
      (c-fwrite ptr 1 n f)
      (c-fclose f))
    (ffi/free ptr)
    [w h]))

(defn- lcg-rng
  "A deterministic 0-arg float [0,1) generator (fixed seed) so the baked noise
  textures are stable across runs (q1k3 uses Math.random once at load)."
  []
  (let [s (atom (long 123456789))]
    (fn []
      (let [n (long (mod (+ (* @s 1103515245) 12345) 2147483648))]
        (reset! s n)
        (/ (double n) 2147483648.0)))))

(defn- resample-to-64
  "Nearest-neighbour resample a w×h RGBA byte vector to 64×64, since a
  GL_TEXTURE_2D_ARRAY requires every layer to share one dimension. q1k3's set is
  64×64 and 32×32; the 32s double to 64. Returns a flat 64*64*4 byte vector."
  [w h px]
  (let [at (fn [x y]
             (let [sx (int (* (/ x 64.0) w))
                   sy (int (* (/ y 64.0) h))
                   so (* (+ (* sy w) sx) 4)]
                [(aget px so) (aget px (+ so 1)) (aget px (+ so 2)) (aget px (+ so 3))]))]
    (vec (for [y (range 64) x (range 64) c (at x y)] c))))

(defn- make-texture-atlas
  "Decode all 31 q1k3 textures, resample each to 64×64, and upload them stacked
  vertically into one GL_TEXTURE_2D atlas (64 wide × 64×N tall). Each block
  selects its texture via a per-vertex V offset the shader computes from
  a_tex_index: v = (tex_index + fract(v_uv.y)) / num_textures. A 2D atlas (not a
  GL_TEXTURE_2D_ARRAY) because this macOS GtkGLArea context rejects the
  GL_TEXTURE_2D_ARRAY target with INVALID_ENUM despite reporting GL 4.1 —
  verified by glGetError probing (glTexParameteri on the bound array target
  fails immediately; the plain 2D target succeeds). Returns {:tex id :num-tex N}."
  []
  (let [layers (mapv (fn [t] (resample-to-64 (:w t) (:h t) (:pixels t)))
                     (ttt/decode-all textures/data (lcg-rng)))
        n      (count layers)
        blob   (reduce into [] layers)
        tex    (gl/gen-one gl/gl-gen-textures)]
    (gl/gl-bind-texture gl/GL-TEXTURE-2D tex)
    (let [ptr (ffi/alloc (count blob))]
      ;; one bulk copy of the whole RGBA blob (508K bytes) into native memory —
      ;; a per-byte ffi/write loop froze realize! for many seconds. write-array
      ;; is jolt's native memcpy primitive.
      (ffi/write-array ptr (byte-array (map int blob)))
      (gl/gl-tex-image-2d gl/GL-TEXTURE-2D 0 GL-RGBA8 64 (* 64 n) 0
                          GL-RGBA GL-UNSIGNED-BYTE ptr)
      (ffi/free ptr))
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MIN-FILTER gl/GL-NEAREST)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MAG-FILTER gl/GL-NEAREST)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-S gl/GL-REPEAT)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-T gl/GL-CLAMP-TO-EDGE)
    {:tex tex :num-tex n}))

;; --- Phase 1: the real q1k3 level -------------------------------------------
;; Decode the packed container once and turn every block into textured box
;; geometry, concatenated into a single vertex buffer (one draw call for the
;; whole static world — same strategy as q1k3's r_push_block render buffer).

(defn- first-map-blocks
  "Blocks of the first map in the container (m1, the Slipgate Complex)."
  []
  (:blocks (first (lvl/decode-container lvl-data/bytes))))

(defn level-vertex-count
  "Total triangle vertices the level tessellates to (public so the check suite
  can verify the geometry pipeline against real data without a GL context).
  36 verts per block (6 faces × 2 tris × 3 verts)."
  []
  (* 36 (count (first-map-blocks))))

(defn- level-geometry
  "Build one interleaved vertex buffer for every block. Returns
  {:data [...floats] :count N :stride 9}. Each block's :tex (the q1k3 texture
  sentinel) is baked into every vertex as the a_tex_index layer. `tile` per block
  scales the texture roughly once per 32-unit cell so large walls don't smear a
  single tile."
  []
  (let [blocks (first-map-blocks)]
    (loop [in blocks data [] count 0]
      (if (empty? in)
        {:data data :count count :stride 9}
        (let [b   (first in)
              {:keys [min size]} (lvl/world-box b)
              [sx sy sz] size
              tile (max 1.0 (/ (max sx (max sy sz)) 32.0))
              box  (tex/box min size tile (:tex b))]
          (recur (rest in)
                 (into data (:data box))
                 (+ count (:count box))))))))

(defn player-spawn
  "World-space eye of the first info_player_start (entity type 0) in m1, lifted
  to standing eye height. Falls back to the level center if the map has none."
  []
  (let [m1    (first (lvl/decode-container lvl-data/bytes))
        spawn (first (filter #(= 0 (:type %)) (:entities m1)))]
    (if spawn
      [(double (* (:x spawn) 32)) (double (+ (* (:y spawn) 16) 48.0)) (double (* (:z spawn) 32))]
      [256.0 64.0 256.0])))

(defn init!
  "Compile the q1k3 pipeline + upload the whole static level into one VBO. Call
   once, with a current GL context (i.e. from on-realize). Returns the opaque
   state map, including the camera eye/look derived from the player spawn."
   []
   (let [{:keys [vs-src fs-src]} (shader/sources sh/lit-spec)
         prog (gl/make-program vs-src fs-src)]
    (assert prog "q1k3 lit shader failed to compile/link")
    (let [u      (fn [^String n] (gl/gl-get-uniform-location prog n))
          geo    (level-geometry)
          eye    (player-spawn)
          vao    (gl/gen-one gl/gl-gen-vertex-arrays)
          vbo    (gl/gen-one gl/gl-gen-buffers)
          stride 36]
      (assert (pos? (:count geo)) "level geometry has vertices")
      (gl/gl-bind-vertex-array vao)
      (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
      (let [ptr (gl/write-floats (:data geo))]
        (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                           (* (ffi/sizeof :float) (count (:data geo)))
                           ptr gl/GL-STATIC-DRAW)
        (ffi/free ptr))
      (gl/gl-enable-vertex-attrib-array 0)                 ; a_pos       (location 0)
      (gl/gl-vertex-attrib-pointer 0 3 gl/GL-FLOAT GL-FALSE stride 0)
      (gl/gl-enable-vertex-attrib-array 1)                 ; a_uv        (location 1)
      (gl/gl-vertex-attrib-pointer 1 2 gl/GL-FLOAT GL-FALSE stride 12)
      (gl/gl-enable-vertex-attrib-array 2)                 ; a_normal    (location 2)
      (gl/gl-vertex-attrib-pointer 2 3 gl/GL-FLOAT GL-FALSE stride 20)
      (gl/gl-enable-vertex-attrib-array 3)                 ; a_tex_index (location 3)
      (gl/gl-vertex-attrib-pointer 3 1 gl/GL-FLOAT GL-FALSE stride 32)
      (gl/gl-bind-vertex-array 0)
      ;; flat (unlit) program + one dynamic overlay VAO/VBO shared by the HUD bar,
      ;; blood particles, and the first-person viewmodel (pos3+color3, stride 24).
      (let [{fvs :vs-src ffs :fs-src} (shader/sources sh/flat-spec)
            fprog (gl/make-program fvs ffs)]
        (assert fprog "q1k3 flat shader failed to compile/link")
        (let [fu      (fn [^String n] (gl/gl-get-uniform-location fprog n))
              fovao   (gl/gen-one gl/gl-gen-vertex-arrays)
              fovbo   (gl/gen-one gl/gl-gen-buffers)
              fstride 24]
          (gl/gl-bind-vertex-array fovao)
          (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER fovbo)
          (gl/gl-enable-vertex-attrib-array 0)             ; a_pos   (location 0)
          (gl/gl-vertex-attrib-pointer 0 3 gl/GL-FLOAT GL-FALSE fstride 0)
          (gl/gl-enable-vertex-attrib-array 1)             ; a_color (location 1)
          (gl/gl-vertex-attrib-pointer 1 3 gl/GL-FLOAT GL-FALSE fstride 12)
          (gl/gl-bind-vertex-array 0)
          (let [atlas (make-texture-atlas)]
            {:prog prog :vao vao
             :tex (:tex atlas) :num-tex (:num-tex atlas)
             :count (:count geo)
             :eye eye
             :enemy (init-enemies!)
             :flat {:prog fprog :vao fovao :vbo fovbo
                    :loc {:mvp (fu "u_mvp")}}
             :loc {:mvp        (u "u_mvp")
                   :model      (u "u_model")
                   :tex        (u "u_tex")
                   :num-tex    (u "u_num_textures")
                   :lights     (u "u_lights")}}))))))

(defn- upload-mat4
  "Write a 4×4 matrix to a uniform location as column-major (transpose=FALSE)."
  [loc m]
  (let [ptr (gl/write-floats (mat/->vec m))]
    (gl/gl-uniform-matrix4fv loc 1 GL-FALSE ptr)
    (ffi/free ptr)))

(def ^:private ^:const fp-x-mirror
  ;; diag(-1, 1, 1, 1): negates the camera-space x axis so world +X renders
  ;; screen-right (matching q1k3's left-handed clip.x = world.x). glimmer-gl's
  ;; look-at uses s = cross(f, up) = -X at yaw 0 and mirrors X; composing this on
  ;; the left un-mirrors the FPS view. (Indexed as a Matrix44 via matrix44.)
  (mat/matrix44 -1.0 0.0 0.0 0.0   0.0 1.0 0.0 0.0   0.0 0.0 1.0 0.0   0.0 0.0 0.0 1.0))

(defn fp-view
  "First-person view matrix (world->camera) for `eye` looking along `forward`
  with +Y up. Composes glimmer-gl's right-handed look-at with an X mirror so
  the view matches q1k3's left-handed projection: world +X is screen-right,
  forward goes into the screen. The mirror flips triangle winding, so face
  culling is disabled in draw! (winding-agnostic, depth-tested)."
  [eye forward]
  (mat/mul fp-x-mirror
           (mat/look-at eye
                        [(+ (nth eye 0) (nth forward 0))
                         (+ (nth eye 1) (nth forward 1))
                         (+ (nth eye 2) (nth forward 2))]
                        [0.0 1.0 0.0])))

(defn- draw-flat!
  "Upload interleaved pos3+color3 floats to the flat overlay VBO and draw them
  with the given model-view-projection. `verts` is a flat seq of Float (6/vert).
  Uses one dynamic buffer for the HUD, blood, and viewmodel — each pass rebinds
  the program, uploads, and draws."
  [state mvp verts]
  (let [f      (:flat state)
        n      (count verts)
        nverts (quot n 6)]
    (when (pos? nverts)
      (gl/gl-use-program (:prog f))
      (gl/gl-bind-vertex-array (:vao f))
      (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER (:vbo f))
      (let [ptr (gl/write-floats verts)]
        (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                           (* (ffi/sizeof :float) n)
                           ptr GL-DYNAMIC-DRAW)
        (ffi/free ptr))
      (upload-mat4 (get-in f [:loc :mvp]) mvp)
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 nverts)
      (gl/gl-bind-vertex-array 0))))

(defn- frame-lights
  "The dynamic point lights active this frame (q1k3 r_push_light callers). A warm
  lamp follows the player so the near field is visible until torches exist; a
  bright muzzle flash while firing; plasma/grenade projectiles glow; and any
  transient explosion flashes (`:lights` from the game state). rgb are 0-255."
  [cam hud]
  (let [eye (:eye cam)
        base [{:pos [(nth eye 0) (+ (nth eye 1) 8.0) (nth eye 2)]
               :intensity 10.0 :color [255.0 235.0 205.0]}]
        muzzle (when (hud/muzzle-active? (:time hud) (:fire-time hud))
                 [{:pos eye :intensity 12.0 :color [255.0 240.0 180.0]}])
        projs (keep (fn [p]
                      (case (:kind p)
                        :plasma  {:pos (:pos p) :intensity 5.0 :color [255.0 128.0 0.0]}
                        :grenade {:pos (:pos p) :intensity 1.5 :color [255.0 40.0 0.0]}
                        nil))
                    (:projectiles hud))]
    (concat base muzzle projs (:lights hud))))

(defn draw!
  "Render one frame: the full static q1k3 level from the player's eye, lit by the
   dynamic point-light array, then each enemy (vertex-animated grunt) at its own world
   pose, then the screen-space HUD bar, the world-space blood particles, and the
   first-person shotgun viewmodel. `state` is the map from init!; w/h are
   drawable; `cam` is {:eye [x y z] :yaw :pitch} — the first-person camera;
   `enemies` is the live enemy list; `hud` is {:health :fire-time :particles}."
  [state w h cam enemies hud]
  (gl/gl-viewport 0 0 w h)
  (gl/gl-clear-color 0.05 0.06 0.08 1.0)
  (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
  (gl/gl-enable gl/GL-DEPTH-TEST)
  ;; fp-view's X-mirror flips triangle winding, so disable face culling rather
  ;; than guess a front-face; the level and the grunt mesh are closed, so
  ;; depth-testing handles hidden back faces (cost is negligible at this count).
  (gl/gl-disable gl/GL-CULL-FACE)
  (gl/gl-use-program (:prog state))
  (let [[ex ey ez] (:eye cam)
        [fx fy fz] (player/forward (:yaw cam) (:pitch cam))
        proj  (mat/perspective 70.0 (/ (double w) (max h 1)) 1.0 8192.0)
        view  (fp-view [ex ey ez] [fx fy fz])
        model (mat/ident)
        mvp   (mat/mul proj (mat/mul view model))
        locs  (:loc state)]
    (upload-mat4 (:mvp locs) mvp)
    (upload-mat4 (:model locs) model)
    (gl/gl-active-texture gl/GL-TEXTURE0)
    (gl/gl-bind-texture gl/GL-TEXTURE-2D (:tex state))
    (gl/gl-uniform-1i (:tex locs) 0)
    (gl/gl-uniform-1f (:num-tex locs) (double (:num-tex state)))
    ;; upload the dynamic point-light array (two vec3 per light)
    (let [arr (light/pack-lights (frame-lights cam (or hud {})) (:eye cam))
          ptr (gl/write-floats arr)]
      (gl/gl-uniform-3fv (:lights locs) (* light/max-lights 2) ptr)
      (ffi/free ptr))
    (gl/gl-bind-vertex-array (:vao state))
    (gl/gl-draw-arrays gl/GL-TRIANGLES 0 (:count state))
    (gl/gl-bind-vertex-array 0)
    (draw-enemies! state proj view locs enemies)
    ;; in-flight projectiles: colored cubes in world space, depth-tested so walls
    ;; and enemies occlude them (drawn before the depth-test is disabled below).
    (when-let [projs (:projectiles hud)]
      (let [pv (ov/projectile-box-verts projs)]
        (when (pos? (count pv))
          (draw-flat! state (mat/mul proj view) pv))))
    ;; --- overlays (flat shader) -----------------------------------------------
    ;; HUD bar + viewmodel are screen-space (ortho); blood particles reuse the
    ;; world proj*view so they sit in the level. depth-test off so overlays paint
    ;; over the world, then back on for the next frame.
    (let [ortho (mat/ortho 0.0 (double w) 0.0 (double h) -1.0 1.0)
          world (mat/mul proj view)
          hmap  (or hud {})]
      (gl/gl-disable gl/GL-DEPTH-TEST)
      (when-let [fill (:health hmap)]
        (draw-flat! state ortho
                    (ov/hud-bar-verts w h (hud/bar-fill (double fill)))))
      (when-let [parts (:particles hmap)]
        (when (pos? (count parts))
          (draw-flat! state world (ov/particle-box-verts parts))))
      (draw-flat! state ortho
                  (ov/viewmodel-verts (hud/muzzle-active? (:time hmap)
                                                          (:fire-time hmap))))
      ;; ammo readout (∞ for the infinite-ammo shotgun), above the health bar.
      (draw-flat! state ortho (ov/ammo-verts [45.0 70.0]))
      (gl/gl-enable gl/GL-DEPTH-TEST))))

;; --- enemy model matrix (pure, #52[C]) --------------------------------------
;; Composed per-enemy for the u_model uniform. Column-major M = T · Ry · S, so a
;; model-space vertex is scaled, yaw-rotated about Y, then translated to world.

(defn enemy-model-matrix
  "Build a u_model matrix for an enemy from {:pos [x y z] :yaw rad :scale s}.
  yaw/scale default to 0.0/1.0."
  [enemy]
  (let [[x y z] (:pos enemy)
        yaw     (:yaw enemy 0.0)
        s       (:scale enemy 1.0)]
    (mat/mul (mat/translation x y z)
             (mat/mul (mat/rotate-y yaw)
                      (mat/scaling s s s)))))

;; --- enemy GL resources + per-frame draw (#52[D]) ---------------------------
;; unit.rmf is parsed once on realize into a vertex-animated model; a single
;; dynamic VBO/VAO (sharing the lit shader's pos3/uv2/normal3 layout) is
;; re-uploaded each frame with the enemy's blended frame, then drawn at its own
;; world pose. Mirrors q1k3's r_u_frame_mix + per-entity model matrix.

;; --- pose upload cache -------------------------------------------------------
;; mix-frame-buffer (per-float ffi/read) + write-floats (per-float ffi/write) is
;; the dominant per-frame cost (~1.4 ms/enemy). But a pose only changes when the
;; frame pair flips or the blend crosses a quantized step, so memoize the built
;; native pointer keyed by [frame-a frame-b mix-step]. The glBufferSubData that
;; follows is a fast driver memcpy — reusing the pointer is the win.
(def ^:private ^:const pose-mix-steps 10)
(def ^:private pose-ptr-cache (atom {}))

(defn- reset-pose-cache! []
  (doseq [ptr (vals @pose-ptr-cache)] (ffi/free ptr))
  (reset! pose-ptr-cache {}))

(defn- pose-ptr
  "Cached native upload pointer for enemy pose [fa fb mix]. mix is quantized to
  pose-mix-steps; on a cache miss the frame is blended and packed once, then the
  pointer is reused for every subsequent frame at that pose."
  [model fa fb mix]
  (let [q   (int (min (double (dec pose-mix-steps))
                      (Math/floor (* (double mix) pose-mix-steps))))
        key [fa fb q]]
    (or (get @pose-ptr-cache key)
        (let [buf (model/mix-frame-buffer model fa fb mix)
              ptr (gl/write-floats (:data buf))]
          (swap! pose-ptr-cache assoc key ptr)
          ptr))))

(defn init-enemies!
  "Parse unit.rmf into a vertex-animated grunt model and build one dynamic VBO/VAO
  using the lit shader's attrib layout (pos3/uv2/normal3, stride 32). The VBO is
  allocated DYNAMIC and re-uploaded each frame. Returns
  {:model :vao :vbo :count}; :count is verts per enemy (constant across frames)."
  []
  (reset-pose-cache!)
  (let [m       (model/init-model unit/bytes 2.5 2.2 2.5)
        vao     (gl/gen-one gl/gl-gen-vertex-arrays)
        vbo     (gl/gen-one gl/gl-gen-buffers)
        stride  32]
    (gl/gl-bind-vertex-array vao)
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-enable-vertex-attrib-array 0)                 ; a_pos   (location 0)
    (gl/gl-vertex-attrib-pointer 0 3 gl/GL-FLOAT GL-FALSE stride 0)
    (gl/gl-enable-vertex-attrib-array 1)                 ; a_uv    (location 1)
    (gl/gl-vertex-attrib-pointer 1 2 gl/GL-FLOAT GL-FALSE stride 12)
    (gl/gl-enable-vertex-attrib-array 2)                 ; a_normal(location 2)
    (gl/gl-vertex-attrib-pointer 2 3 gl/GL-FLOAT GL-FALSE stride 20)
    (gl/gl-bind-vertex-array 0)
    {:model m :vao vao :vbo vbo :count (:num-verts m)}))

(defn- draw-enemies!
  "Upload each enemy's current blended-frame vertex buffer and draw it at its own
  world pose. Reuses the lit shader/locs already bound by draw!. `proj`/`view`
  are draw!'s camera matrices; `enemies` is the live enemy list."
  [state proj view locs enemies]
  (when (seq enemies)
    (let [em     (:enemy state)
          vao    (:vao em)
          vbo    (:vbo em)
          model  (:model em)
          nverts (:count em)]
      (gl/gl-bind-vertex-array vao)
      (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
      (doseq [e enemies]
        (let [[fa fb mix] (model/enemy-frame-anim e)
              ptr         (pose-ptr model fa fb mix)
              emodel      (enemy-model-matrix e)
              emvp        (mat/mul proj (mat/mul view emodel))]
          (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                             (* nverts 32) ptr GL-DYNAMIC-DRAW)
          (upload-mat4 (:mvp locs) emvp)
          (upload-mat4 (:model locs) emodel)
          (gl/gl-draw-arrays gl/GL-TRIANGLES 0 nverts)))
      (gl/gl-bind-vertex-array 0))))
