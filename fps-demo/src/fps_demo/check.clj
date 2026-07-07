(ns fps-demo.check
  "Headless verification (no GL context, no display) of the q1k3 port's pure
  layers. Real GL runs only at runtime via core.clj; this catches data/shape/
  shader-source bugs before touching the GPU. Mirrors gl-demo.check's idiom."
  (:require [clojure.string    :as str]
            [glimmer-gl.shader :as shader]
            [fps-demo.textured :as tex]
            [fps-demo.shaders  :as sh]
            [fps-demo.map      :as lvl]
            [fps-demo.maps.l    :as lvl-data]
            [fps-demo.render    :as render]
            [fps-demo.player    :as player]
            [fps-demo.model     :as model]
            [fps-demo.models.unit :as unit]
              [fps-demo.entity    :as ent]
              [fps-demo.projectile :as proj]
              [fps-demo.light     :as light]
              [fps-demo.particle  :as part]
              [fps-demo.pickup    :as pkup]
              [fps-demo.hud       :as hud]
              [fps-demo.overlay   :as ov]
              [fps-demo.texture   :as ttt]
              [fps-demo.textures  :as textures]
            [glimmer-gl.matrix  :as mat]
            [jolt.ffi           :as ffi]
            [fps-demo.capture    :as capture]
            [glimmer-gl.gtk]))                         ; side-effect: widget registry

(defn- approx= [a b] (< (Math/abs (- a b)) 1e-9))

(defn- min-max [xs]
  (reduce (fn [[lo hi] x] [(min lo x) (max hi x)])
          [(first xs) (first xs)] (rest xs)))

(defn -main [& _]
  ;; --- textured box geometry --------------------------------------------------
  ;; A box is 6 faces * 2 tris * 3 verts = 36 vertices, each
  ;; [x y z u v nx ny nz tex-index] (stride 9), so the flat data has 324 floats.
  ;; Each face tiles the texture by its own world size / texel-world (64), so a
  ;; non-cubic box has DIFFERENT U/V tile counts per face (no single-tile stretch).
  (let [b     (tex/box [0.0 0.0 0.0] [64.0 32.0 64.0] 5)
        data  (:data b)]
    (println "textured box: count =" (:count b) " stride =" (:stride b)
             " floats =" (count data))
    (assert (= 36 (:count b))  "a box tessellates to 36 vertices")
    (assert (= 9  (:stride b)) "interleaved stride is pos(3)+uv(2)+normal(3)+tex-index(1) = 9")
    (assert (= 324 (count data)) "36 verts * 9 floats = 324")
    (let [pos (vec (map (fn [i] (nth data i)) (range 0 (count data) 9)))
          [pxmin pxmax] (min-max pos)]
      (assert (approx= pxmin 0.0) (str "x min should be box min 0.0, got " pxmin))
      (assert (approx= pxmax 64.0) (str "x max should be box max 64.0, got " pxmax)))
    (let [us (vec (map (fn [i] (nth data (+ i 3))) (range 0 (count data) 9)))
          vs (vec (map (fn [i] (nth data (+ i 4))) (range 0 (count data) 9)))]
      ;; 64-unit edge -> 64/64 = 1.0 tile; 32-unit edge -> 0.5. Both appear, which
      ;; is exactly what one shared `tile` could not produce (that was the stretch).
      (assert (approx= 1.0 (apply max us)) "max U tile = 64/64 = 1.0")
      (assert (approx= 1.0 (apply max vs)) "max V tile = 1.0")
      (assert (some #(approx= 0.5 %) us) "a 32-unit face edge tiles 0.5 (per-face density)")
      (assert (some #(approx= 0.5 %) vs) "a 32-unit face edge tiles 0.5 on V too"))
    (let [idx (vec (map (fn [i] (nth data (+ i 8))) (range 0 (count data) 9)))
          [imin imax] (min-max idx)]
      (assert (approx= imin 5.0) "every vertex tex-index is the face layer (5)")
      (assert (approx= imax 5.0) "every vertex tex-index is the face layer (5)")))

  ;; --- q1k3 point-light + texture shader spec ---------------------------------
  ;; The lit shader must declare a texture sampler, per-vertex UV, and a point
  ;; light (position + colour + range). We check the generated GLSL, not the
  ;; compiled program (compilation needs a GL context — verified at runtime).
  (let [{:keys [vs-src fs-src]} (shader/sources sh/lit-spec)]
    (assert (str/includes? fs-src "sampler2D") "lit shader samples a 2D texture")
    (assert (str/includes? fs-src "u_num_textures") "lit shader knows the atlas texture count")
    (assert (str/includes? fs-src "u_lights") "lit shader has the dynamic light array")
    (assert (str/includes? fs-src "accum_light") "lit shader accumulates the light loop")
    (assert (str/includes? vs-src "a_uv") "vertex shader reads a_uv attribute")
    (assert (str/includes? vs-src "a_tex_index") "vertex shader reads the tex-array layer")
    (assert (str/includes? vs-src "v_uv") "vertex shader writes the v_uv varying")
    (assert (str/includes? vs-src "v_tex_index") "vertex shader writes the tex-array layer")
    (assert (str/includes? fs-src "v_uv") "fragment shader reads v_uv")
    (println "shader sources generated:"
             "vs" (count vs-src) "chars, fs" (count fs-src) "chars"))

  ;; --- q1k3 packed-map decoder ------------------------------------------------
  ;; Synthetic container: 1 map, 2 blocks (with a texture change between them),
  ;; 2 entities. Verifies block/entity parsing, world scaling, and the derived
  ;; solid-cell collision set.
  (let [data [16 0                      ; blocks_size = 16 (LE)
               255 1                      ; sentinel -> texture index 1
               0 0 0 1 1 1                ; block0: x0 y0 z0 sx1 sy1 sz1
               255 2                      ; sentinel -> texture index 2
               1 0 0 2 1 2                ; block1: x1 y0 z0 sx2 sy1 sz2
               2 0                        ; num_entities = 2 (LE)
               0 10 20 30 1 2            ; entity0: player @ grid(10,20,30)
               12 5 6 7 0 0]             ; entity1: light @ grid(5,6,7)
        [m]   (lvl/decode-container data)
        blks  (:blocks m)
        ents  (:entities m)
        cells (lvl/solid-cells blks)]
    (assert (= 1 (count (lvl/decode-container data))) "container has 1 map")
    (assert (= 2 (count blks)) "parsed 2 blocks")
    (assert (= 2 (count ents)) "parsed 2 entities")
    ;; block0 keeps texture 1; block1 picks up texture 2 from the second sentinel
    (assert (= 1 (get-in blks [0 :tex])) "block0 texture is 1")
    (assert (= 2 (get-in blks [1 :tex])) "block1 texture is 2")
    ;; grid coords parsed verbatim
    (assert (= {:x 0 :y 0 :z 0 :sx 1 :sy 1 :sz 1}
               (dissoc (nth blks 0) :tex)) "block0 grid coords")
    (assert (= {:x 1 :y 0 :z 0 :sx 2 :sy 1 :sz 2}
               (dissoc (nth blks 1) :tex)) "block1 grid coords")
    ;; world scaling: X/Z *32, Y *16
    (assert (= {:min [0.0 0.0 0.0]   :size [32.0 16.0 32.0]}
               (lvl/world-box (nth blks 0))) "block0 world box")
    (assert (= {:min [32.0 0.0 0.0]  :size [64.0 16.0 64.0]}
               (lvl/world-box (nth blks 1))) "block1 world box")
    ;; entity parse
    (assert (= {:type 0 :x 10 :y 20 :z 30 :data1 1 :data2 2} (nth ents 0))
           "player entity")
    (assert (= {:type 12 :x 5 :y 6 :z 7 :data1 0 :data2 0} (nth ents 1))
           "light entity")
    ;; solid cells: block0 fills (0,0,0); block1 fills x in {1,2}, y 0, z in {0,1}
    (assert (contains? cells (lvl/cell-key 0 0 0)) "block0 is solid")
    (assert (contains? cells (lvl/cell-key 1 0 0)) "block1 cell (1,0,0) solid")
    (assert (contains? cells (lvl/cell-key 2 0 1)) "block1 cell (2,0,1) solid")
    (assert (not (contains? cells (lvl/cell-key 3 0 0))) "cell past block1 is empty")
    (assert (not (contains? cells (lvl/cell-key 0 1 0))) "cell above block0 is empty"))
  (println "map decoder: 1 map, 2 blocks, 2 entities, 5 solid cells")

  ;; --- decode the REAL q1k3 level (build/l = m1 ++ m2) -----------------------
  ;; This is the load-bearing verification: the decoder must correctly parse the
  ;; actual game's binary container. Counts come straight from pack_map.c output
  ;; (m1: "331 blocks ... 91 entities", m2: "230 blocks ... 95 entities").
  (let [maps   (lvl/decode-container lvl-data/bytes)
        [m1 m2] maps]
    (assert (= 2 (count maps)) "real container holds 2 maps (m1, m2)")
    (assert (= 331 (count (:blocks m1))) "m1 has 331 blocks")
    (assert (= 91 (count (:entities m1))) "m1 has 91 entities")
    (assert (= 230 (count (:blocks m2))) "m2 has 230 blocks")
    (assert (= 95 (count (:entities m2))) "m2 has 95 entities")
    ;; every block has sane grid extents (within the 128^3 collision grid)
    (doseq [b (:blocks m1)]
      (assert (and (<= 0 (:x b) 127) (<= 0 (:y b) 127) (<= 0 (:z b) 127))
              (str "m1 block in grid: " b))))
  (println "real q1k3 level decoded: m1(331/91) m2(230/95)")

  ;; --- render pipeline: geometry count ---------------------------------------
  ;; Proves the level tessellates to exactly 331 blocks × 36 verts/block, i.e.
  ;; the VBO the GL renderer uploads carries the whole static world. No GL
  ;; context needed — this is pure data flow.
  (let [n-blocks  331
        expected  (* n-blocks 36)]
    (assert (= expected (render/level-vertex-count))
            "level tessellates to 331*36 vertices")
    (assert (pos? expected) "level has geometry"))
  (println "level render geometry:" (render/level-vertex-count) "vertices")

  ;; --- player physics (pure translation of q1k3 _update_physics) -------------
  ;; tick = 1/60 s. Gravity is -1200 u/s^2; one tick of free fall should add
  ;; exactly gravity*tick to v.y while leaving X/Z velocity untouched.
  (let [tick      0.016666667
        empty     #{}
        s0        {:p [0.0 1000.0 0.0] :v [0.0 0.0 0.0] :a [0.0 0.0 0.0]
                   :f 10 :on-ground false}
        s1        (player/step-physics empty s0 tick)
        fall      (get-in s1 [:v 1])]
    (assert (neg? fall) "gravity makes v.y negative")
    (assert (< (Math/abs (- fall (* -1200.0 tick))) 0.01) "v.y == gravity*tick")
    (assert (approx= 0.0 (get-in s1 [:v 0])) "friction never touches v.x at rest")
    (assert (< (get-in s1 [:p 1]) 1000.0) "player descends"))
  (println "player physics: free-fall gravity integration ok")

  ;; voxel AABB collision query (cell [0 0 0] spans world x,z in [0,32), y in [0,16))
  (let [cells #{(lvl/cell-key 0 0 0)}]
    (assert (player/block-at-box? cells [0.0 0.0 0.0] [10.0 10.0 10.0]) "box inside cell")
    (assert (player/block-at-box? cells [-1.0 -1.0 -1.0] [0.0 0.0 0.0]) "box touching cell edge")
    (assert (not (player/block-at-box? cells [33.0 0.0 0.0] [40.0 10.0 10.0])) "box past cell")
    (assert (not (player/block-at-box? cells [0.0 33.0 0.0] [10.0 40.0 10.0])) "box above cell (y)"))
  (println "player physics: voxel AABB collision query ok")

  ;; floor landing: 4x4 floor of cells at y=0 (top at world y=16), player
  ;; half-height 24 -> resting center >= 40. After enough ticks the player is
  ;; on-ground and stable.
  (let [tick      0.016666667
        floor     (into #{} (for [x (range 0 4) z (range 0 4)] (lvl/cell-key x 0 z)))
        s0        {:p [16.0 1000.0 16.0] :v [0.0 -2000.0 0.0] :a [0.0 0.0 0.0]
                   :f 10 :on-ground false}
        final     (loop [s s0 n 0]
                    (if (or (:on-ground s) (> n 500)) s
                        (recur (player/step-physics floor s tick) (inc n))))
        py        (get-in final [:p 1])]
    (assert (:on-ground final) "player lands and reports on-ground")
    (assert (>= py 40.0) (str "resting center above floor top+half-height: " py))
    (assert (< py 56.0) (str "resting center within one substep of floor: " py))
    (assert (approx= 0.0 (get-in final [:v 1])) "vertical velocity killed on landing"))
  (println "player physics: floor landing ok")

  ;; wall stops horizontal motion: player starts GROUNDED on a floor at q1k3
  ;; terminal walk speed (accel/friction = 3000/10 = 300 u/s), walks into a
  ;; tall wall, and stops without penetrating. The wall spans y cells 0..3
  ;; (world y [0,64)) so the grounded player's 48-tall box overlaps it.
  (let [tick      0.016666667
        cells     (into #{} (concat
                              (for [y (range 0 4)] (lvl/cell-key 2 y 0))    ; tall wall, world x [64,96)
                              (for [cx (range 0 2)] (lvl/cell-key cx 0 0)))) ; floor x [0,64) under approach
        s0        {:p [0.0 40.0 0.0] :v [300.0 0.0 0.0] :a [0.0 0.0 0.0]
                   :f 10 :on-ground true}
        final     (loop [s s0 n 0]
                    (if (> n 200) s (recur (player/step-physics cells s tick) (inc n))))
        px        (get-in final [:p 0])
        half      (player/player-half)]
    ;; player box max.x = px + half.x must stay < wall min.x (64)
    (assert (< (+ px (nth half 0)) 64.0)
            (str "player X never penetrates wall: " (+ px (nth half 0))))
    (assert (approx= 0.0 (get-in final [:v 0])) "horizontal velocity killed by wall"))
  (println "player physics: wall collision ok")

  ;; mouse look: yaw advances with mouse-x; pitch clamps to [-1.5,1.5]
  (let [[yaw1 pitch1] (player/look 0.0 0.0 1000.0 0.0)
        [_ pitch2]    (player/look 0.0 0.0 0.0 1.0e7)
        [_ pitch3]    (player/look 0.0 0.0 0.0 -1.0e7)]
    (assert (pos? yaw1) "mouse +x adds yaw")
    (assert (approx= 0.0 pitch1) "no mouse y -> no pitch")
    (assert (approx= 1.5 pitch2) "pitch clamps to +1.5")
    (assert (approx= -1.5 pitch3) "pitch clamps to -1.5"))

  ;; #66/#75: configurable sensitivity (one factor for both axes) + invert-Y.
  ;; q1k3 uses a single _mouse_sensitivity; inverted negates the Y factor. With a
  ;; higher sens, the same delta turns farther; invert flips the pitch sign.
  (let [s1 (player/look 0.0 0.0 1000.0 0.0     {:sens 0.00030})
        s2 (player/look 0.0 0.0 1000.0 0.0     {:sens 0.00015})
        d  (player/look 0.0 0.0 0.0    1000.0  {:sens 0.00030})
        i  (player/look 0.0 0.0 0.0    1000.0  {:sens 0.00030 :invert true})]
    (assert (approx= (first s1) (* 1000.0 0.00030)) "yaw scales by :sens")
    (assert (approx= (first s2) (* 1000.0 0.00015)) "lower sens turns less")
    (assert (> (first s1) (first s2)) "doubled sens doubles yaw")
    (assert (approx= (second d) (* 1000.0 0.00030)) "pitch scales by :sens (non-inverted: +my = +pitch)")
    (assert (approx= (second i) (* 1000.0 -0.00030)) "invert negates pitch"))

  ;; camera forward must agree with wish-accel's horizontal forward so that W
  ;; walks toward screen center. forward's (x,z) dir == wish-accel forward dir.
  (let [gnd true]
    (doseq [yaw [0.0 0.7 1.5 2.2 3.0 4.0 5.5 6.2]]
      (let [fwd   (player/forward yaw 0.0)
            fx    (nth fwd 0) fz (nth fwd 2)
            accel (player/wish-accel {:forward 1} yaw gnd)   ; iz=1, ix=0
            ax    (nth accel 0) az (nth accel 2)
            ;; compare unit horizontal directions (sign of cross product ~ 0)
            cross (- (* fx az) (* fz ax))]
        (assert (< (Math/abs cross) 1e-6)
                (str "forward & wish-accel disagree at yaw=" yaw
                     ": cross=" cross)))))
  (println "player physics: camera forward agrees with wish-accel ok")

  ;; --- .rmf model parser (pure port of q1k3/source/model.js) -----------------
  ;; Synthetic single-triangle model, hand-computed:
  ;;   header [1 3 1]; verts (b-15): v0=(0,0,0) v1=(10,0,0) v2=(0,10,0)
  ;;   bounds min/max x,y in [0,10] -> uf=0.1 u=0 ; vf=-0.1 v=-1.0
  ;;   index triple [0,1,2] (delta a=0, abs b=1, abs c=2)
  ;;   face normal normalize(cross(v2-v1,v0-v1)) = (0,0,1)
  ;;   emitted order: mv2,mv1,mv0 with uv v2,v1,v0
  (let [blob      [1 3 1  15 15 15  25 15 15  15 25 15  0 1 2]
        models    (model/load-container blob)]
    (assert (= (count models) 1) "container splits into 1 model blob")
    (assert (= (first models) blob) "single-blob container round-trips whole blob"))
  (println "model parser: container splitting ok")

  (let [blob      [1 3 1  15 15 15  25 15 15  15 25 15  0 1 2]
        m         (model/init-model blob 1 1 1)
        verts     (:verts m)
        stride    (:stride m)
        frame0    (get (:frames m) 0)]
    (assert (= stride 8) "vertex stride is pos3+normal3+uv2 = 8")
    (assert (= frame0 0) "first frame starts at offset 0")
    (assert (= (:num-verts m) 3) "one face -> 3 emitted verts")
    (assert (approx= 0.0 (nth verts (+ frame0 3))) "normal.x = 0")
    (assert (approx= 0.0 (nth verts (+ frame0 4))) "normal.y = 0")
    (assert (approx= 1.0 (nth verts (+ frame0 5))) "normal.z = 1")
    (let [off frame0]
      (assert (approx= 0.0  (nth verts (+ off 0))) "v0 pos.x")
      (assert (approx= 10.0 (nth verts (+ off 1))) "v0 pos.y")
      (assert (approx= 0.0  (nth verts (+ off 2))) "v0 pos.z")
      (assert (approx= 0.0  (nth verts (+ off 6))) "v0 uv.u")
      (assert (approx= -2.0 (nth verts (+ off 7))) "v0 uv.v"))
    (let [off (+ frame0 stride)]
      (assert (approx= 10.0 (nth verts (+ off 0))) "v1 pos.x")
      (assert (approx= 1.0  (nth verts (+ off 6))) "v1 uv.u")
      (assert (approx= -1.0 (nth verts (+ off 7))) "v1 uv.v"))
    (let [off (+ frame0 (* 2 stride))]
      (assert (approx= 0.0  (nth verts (+ off 0))) "v2 pos.x")
      (assert (approx= 0.0  (nth verts (+ off 6))) "v2 uv.u")
      (assert (approx= -1.0 (nth verts (+ off 7))) "v2 uv.v")))
  (println "model parser: single-triangle vertex/normal/uv ok")

  ;; header [2 1 1]: 2 frames * 1 vert + 1 face. Per-axis scale (y*2) and frame
  ;; offsets advance by faces*3.
  (let [blob2     [2 1 1  15 15 15  30 15 15  0 0 0]
        m         (model/init-model blob2 1 2 1)
        verts     (:verts m)
        stride    (:stride m)]
    (assert (= (count (:frames m)) 2) "two frame offsets")
    (assert (= (get (:frames m) 0) 0) "frame 0 at 0")
    (assert (= (get (:frames m) 1) 3) "frame 1 at faces*3 = 3")
    (assert (approx= 0.0 (nth verts 1)) "scaled y of frame0 = 0*2")
    (let [off (* (get (:frames m) 1) stride)]
      (assert (approx= 15.0 (nth verts (+ off 0))) "frame1 pos.x = 15")
      (assert (approx= 0.0  (nth verts (+ off 1))) "frame1 pos.y = 0*2")))
  (println "model parser: multi-frame + per-axis scale ok")

  ;; --- #52 core: model frame -> render-interleaved buffer (pure) ---------------
  ;; render.clj binds attributes as [a_pos3 a_uv2 a_normal3] (stride 8), but
  ;; model.clj stores each vertex as [pos3 normal3 uv2]. frame-buffer reorders one
  ;; frame into render order; mix-frame-buffer lerps two frames' POSITIONS by t
  ;; (uv + normal taken from frame A) for skeletal-free vertex anim, à la q1k3.
  (let [blob [1 3 1  15 15 15  25 15 15  15 25 15  0 1 2]
        m    (model/init-model blob 1 1 1)
        fb   (model/frame-buffer m 0)
        d    (:data fb)]
    (assert (= (:count fb) 3) "frame-buffer emits num-verts verts")
    (assert (= (count d) 24) "3 verts * stride 8 = 24 floats")
    ;; v0 = mv2 = (0,10,0) uv (0,-2) normal (0,0,1)  -> [pos uv normal]
    (assert (approx= (nth d 0) 0.0)  "fb v0 pos.x = 0")
    (assert (approx= (nth d 3) 0.0)  "fb v0 uv.u = 0")
    (assert (approx= (nth d 4) -2.0) "fb v0 uv.v = -2")
    (assert (approx= (nth d 5) 0.0)  "fb v0 n.x = 0")
    (assert (approx= (nth d 7) 1.0)  "fb v0 n.z = 1")
    ;; v1 = mv1 = (10,0,0) uv (1,-1)
    (assert (approx= (nth d 12) -1.0) "fb v1 uv.v = -1"))
  (println "model frame-buffer: pos/uv/normal reorder ok")

  ;; 2-frame triangle: frame-1 lifts v2 by +10 in z. mix at t=0.5 lerps that
  ;; vertex's position to z=5 while keeping frame-0 uv/normal.
  (let [blob2 [2 3 1   15 15 15  25 15 15  15 25 15   ; frame 0
               15 15 15  25 15 15  15 25 25           ; frame 1 (v2 z +10)
               0 1 2]
        m     (model/init-model blob2 1 1 1)
        mix   (model/mix-frame-buffer m 0 1 0.5)
        d     (:data mix)]
    (assert (= (:count mix) 3) "mix emits num-verts verts")
    ;; v0 = mv2: frame0 pos (0,10,0), frame1 pos (0,10,10) -> lerped (0,10,5)
    (assert (approx= (nth d 2) 5.0) "mix v0 pos.z lerped to 5 at t=0.5")
    (assert (approx= (nth d 4) -2.0) "mix v0 uv = frame0 uv (unchanged)")
    (assert (approx= (nth d 7) 1.0) "mix v0 normal = frame0 normal (0,0,1)")
    ;; v1 = mv1: pos identical in both frames -> stays (10,0,0)
    (assert (approx= (nth d 9) 0.0) "mix v1 pos.y = 0 (no lerp delta)")
    ;; t=0 and t=1 reduce to the exact frame buffers (compared numerically:
    ;; frame-buffer returns int pos from the raw blob, mix-frame-buffer lerps to
    ;; float — equal in value, distinct under Clojure =).
    (let [d0 (:data (model/mix-frame-buffer m 0 1 0.0))
          f0 (:data (model/frame-buffer m 0))]
      (assert (every? true? (map approx= d0 f0)) "mix at t=0 equals frame-buffer frame 0"))
    (let [d1 (:data (model/mix-frame-buffer m 0 1 1.0))
          f1 (:data (model/frame-buffer m 1))]
      ;; at t=1 POSITIONS match frame b exactly; uv/normal stay frame a by design
      ;; (cheap vertex anim — normals aren't re-derived per blend), so the whole
      ;; buffer need not equal f1, whose normals were recomputed for moved geo.
      (assert (approx= (nth d1 2) (nth f1 2)) "mix at t=1: v0 pos.z matches frame1")
      (assert (approx= (nth d1 10) (nth f1 10)) "mix at t=1: v1 pos.z matches frame1")))
  (println "model mix-frame-buffer: 2-frame position lerp ok")

  ;; --- REAL grunt asset: parse the actual q1k3 unit.rmf (6 anim frames) ------
  ;; Verifies init-model + frame-buffer against the genuine binary produced by
  ;; pack_model.php from unit_{idle,run_1..4,fire}.obj. Guards the entire asset
  ;; pipeline: packer -> embedded bytes -> parser -> render buffer.
  (let [m   (model/init-model unit/bytes 2.5 2.2 2.5)
        fb0 (model/frame-buffer m 0)]
    (assert (= (count (:frames m)) 6) "unit.rmf has 6 animation frames")
    (assert (= (:num-verts m) (* 68 3)) "unit.rmf: 68 faces * 3 = 204 verts")
    (assert (= (:count fb0) 204) "frame-buffer emits 204 render verts")
    (assert (= (count (:data fb0)) (* 204 8)) "frame-buffer: 204 verts * stride 8")
    ;; positions live in [-2.5, 2.5] after sx,sy,sz scaling (packer normalizes
    ;; to +-15 raw, init-model centers+scales). Sanity-range the first vert.
    (let [px (nth (:data fb0) 0)]
      (assert (and (< px 5.0) (> px -5.0)) "unit v0 pos.x within scaled bounds"))
    ;; animation is real: frame 0 (idle) and frame 1 (run_1) differ in position,
    ;; proving multi-frame decode works on the actual asset.
    (let [d0 (:data (model/frame-buffer m 0))
          d1 (:data (model/frame-buffer m 1))]
      (assert (not (every? true? (map approx= d0 d1)))
              "unit.rmf: frame 0 and frame 1 positions differ (animation present)")))
  (println "model: real unit.rmf (6-frame grunt) parses + animates ok")

  ;; --- #52[C]: anim-state -> frame pair + mix (pure, q1k3 r_u_frame_mix) -----
  ;; unit.rmf frames: 0=idle, 1..4=run cycle, 5=fire. enemy-frame-anim maps an
  ;; enemy's {:anim-frames :anim-period :anim-time} to [frame-a frame-b mix].
  (let [idle {:anim-frames [0]       :anim-period 1.0 :anim-time 0.0}
        walk {:anim-frames [1 2 3 4] :anim-period 0.4 :anim-time 0.0}]
    ;; single-frame anim is static
    (assert (= (model/enemy-frame-anim idle) [0 0 0.0]) "idle -> [0 0 0]")
    ;; walk at t=0: frame[0]=1 blending into frame[1]=2, mix 0
    (assert (= (model/enemy-frame-anim walk) [1 2 0.0]) "walk t=0 -> [1 2 0]")
    ;; walk halfway through first frame (period/8 = 0.05): mix 0.5
    (let [[a b mix] (model/enemy-frame-anim
                       (assoc walk :anim-time 0.05))]
      (assert (= a 1) "walk t=0.05 frame-a = 1")
      (assert (= b 2) "walk t=0.05 frame-b = 2")
      (assert (approx= mix 0.5) "walk t=0.05 mix = 0.5"))
    ;; walk at t=0.1 (one full frame): advances to [2 3 0]
    (assert (= (model/enemy-frame-anim (assoc walk :anim-time 0.1)) [2 3 0.0])
            "walk t=0.1 -> [2 3 0]")
    ;; walk at t=period wraps cleanly back to start
    (assert (= (model/enemy-frame-anim (assoc walk :anim-time 0.4)) [1 2 0.0])
            "walk t=period wraps -> [1 2 0]"))
  (println "model enemy-frame-anim: idle/walk/mix/wrap ok")

  ;; --- #52[D]: per-frame render buffer contract (what draw-enemies! uploads) --
  ;; draw-enemies! blends two frames each tick via mix-frame-buffer, then uploads
  ;; (:data buf) as floats and draws (:count buf) verts. Pin that contract on the
  ;; real grunt: a single-frame idle and a blended run pair both yield 204 verts
  ;; (204*8 floats), and the blend actually interpolates between the two frames.
  (let [m    (model/init-model unit/bytes 2.5 2.2 2.5)
        idle (model/mix-frame-buffer m 0 0 0.0)
        walk (model/mix-frame-buffer m 1 2 0.5)]
    (assert (= (:count idle) 204) "mix idle: 204 verts")
    (assert (= (count (:data idle)) (* 204 8)) "mix idle: 204*8 floats")
    (assert (= (:count walk) 204) "mix walk: 204 verts")
    (assert (= (count (:data walk)) (* 204 8)) "mix walk: 204*8 floats")
    (let [d1 (model/frame-buffer m 1)
          d2 (model/frame-buffer m 2)
          x1 (nth (:data d1) 0) x2 (nth (:data d2) 0) xm (nth (:data walk) 0)
          lo (min x1 x2) hi (max x1 x2)]
      (assert (and (>= xm lo) (<= xm hi))
              "mix walk: blended pos.x within frame1..frame2")))
  (println "model mix-frame-buffer: idle + blend render buffers ok")

  ;; --- #52[C]: enemy model matrix (pure, T · Ry · S) -------------------------
  ;; render/enemy-model-matrix composes translate * yaw-rotate-Y * uniform scale.
  ;; Checked with yaw=0 (rotation-independent) + a length-preserving rotation.
  (let [tp (fn [m p] (mat/transform-point m p))]
    ;; translate: origin -> pos
    (let [m (render/enemy-model-matrix {:pos [10 0 0] :yaw 0 :scale 1})]
      (assert (every? true? (map approx= (tp m [0 0 0]) [10.0 0.0 0.0]))
              "model-matrix translate: origin -> (10,0,0)"))
    ;; uniform scale: [1 1 1] -> [2 2 2]
    (let [m (render/enemy-model-matrix {:pos [0 0 0] :yaw 0 :scale 2})]
      (assert (every? true? (map approx= (tp m [1 1 1]) [2.0 2.0 2.0]))
              "model-matrix scale: [1 1 1] -> [2 2 2]"))
    ;; translate + scale: [1 0 0] at pos [5 0 0] scale 1 -> [6 0 0]
    (let [m (render/enemy-model-matrix {:pos [5 0 0] :yaw 0 :scale 1})]
      (assert (every? true? (map approx= (tp m [1 0 0]) [6.0 0.0 0.0]))
              "model-matrix translate+scale: [1 0 0] -> (6,0,0)"))
    ;; rotation preserves length: unit point stays unit-distance from origin
    (let [m   (render/enemy-model-matrix {:pos [0 0 0] :yaw 1.0 :scale 1})
          out (tp m [1 0 0])
          mag (Math/sqrt (+ (* (out 0) (out 0)) (* (out 1) (out 1)) (* (out 2) (out 2))))]
      (assert (approx= mag 1.0) "model-matrix yaw rotates but preserves length")))
  (println "render enemy-model-matrix: translate/scale/rotate ok")

  ;; --- enemy AI math (atan2 hand-rolled — jolt has no Math/atan2) -----------
  ;; atan2 must agree with the true values to within the ~0.005 rad rational
  ;; approximation; anglemod is exact (modulo fold, no atan2).
  (let [pi  Math/PI
        tol 0.01]
    (assert (< (Math/abs (- (ent/atan2 1.0 1.0)  (/ pi 4))) tol) "atan2(1,1)=pi/4")
    (assert (approx= (ent/atan2 0.0 1.0) 0.0) "atan2(0,1)=0")
    (assert (< (Math/abs (- (ent/atan2 1.0 0.0) (/ pi 2))) tol) "atan2(1,0)=pi/2")
    (assert (< (Math/abs (- (ent/atan2 0.0 -1.0) pi)) tol) "atan2(0,-1)=pi")
    (assert (< (Math/abs (- (ent/atan2 -1.0 0.0) (- (/ pi 2)))) tol) "atan2(-1,0)=-pi/2")
    (assert (< (Math/abs (- (ent/atan2 1.0 -1.0) (* 0.75 pi))) tol) "atan2(1,-1)=3pi/4")
    (assert (< (Math/abs (- (ent/atan2 -1.0 -1.0) (* -0.75 pi))) tol) "atan2(-1,-1)=-3pi/4"))
  (let [pi Math/PI twopi (* 2.0 pi)]
    (assert (approx= (ent/anglemod 0.5) 0.5) "anglemod keeps small positive")
    (assert (approx= (ent/anglemod -0.5) -0.5) "anglemod keeps small negative")
    (assert (< (Math/abs (- (ent/anglemod (+ pi 0.1)) -3.0415926)) 0.01) "anglemod wraps pi+e")
    ;; boundary at exactly +/-pi is convention-dependent (q1k3's own atan2(sin,cos)
    ;; picks a side by float dust); gameplay deltas never land here, so test magnitude.
    (assert (< (Math/abs (- (Math/abs (ent/anglemod (* 3 pi))) pi)) 1e-6) "anglemod wraps 3pi->|pi|")
    (assert (< (Math/abs (- (Math/abs (ent/anglemod (* -3 pi))) pi)) 1e-6) "anglemod wraps -3pi->|pi|"))
  ;; angle-to-player (vec3_2d_angle = atan2(dx, dz)) + 2D distance
  (let [pi Math/PI]
    (assert (< (Math/abs (- (ent/angle-to-player [0.0 0.0 0.0] [1.0 0.0 1.0]) (/ pi 4))) 0.01)
            "angle to NE player is pi/4")
    (assert (approx= (ent/angle-to-player [0.0 0.0 0.0] [0.0 0.0 5.0]) 0.0)
            "player straight +Z is yaw 0 (forward=[sin,cos])")
    (assert (approx= (ent/dist-2d [0.0 0.0 0.0] [3.0 0.0 4.0]) 5.0) "3-4-5 distance"))
  (println "entity math: atan2 / anglemod / angle-to-player / dist-2d ok")

  ;; --- enemy construction + set-state (anim reset, timer + jitter) ----------
  (let [e (ent/make-enemy [0.0 0.0 0.0] 0.0)]
    (assert (= (:state e) :idle) "make-enemy starts idle")
    (assert (approx= (:health e) ent/base-health) "default health")
    (assert (approx= (:state-update-at e) 0.1) "idle update-at = 0 + 0.1 dur + 0 jitter")
    (assert (= (:anim-frames e) [0]) "idle anim = frame 0"))
  ;; set-state follow, rng 0 -> no jitter: at = t + dur + dur/4*0
  (let [f (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :follow 10.0 (fn [] 0.0))]
    (assert (= (:state f) :follow) "set-state follow")
    (assert (approx= (:anim-period f) 0.20) "follow uses run anim, period 0.20")
    (assert (= (:anim-frames f) [1 2 3 4]) "follow anim frames 1..4")
    (assert (approx= (:anim-time f) 0.0) "set-state resets anim-time to 0")
    (assert (approx= (:state-update-at f) 10.3) "follow update-at = 10 + 0.3 + 0"))
  ;; set-state follow, rng 1 -> full jitter: at = t + dur + dur/4
  (let [f (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :follow 10.0 (fn [] 1.0))]
    (assert (approx= (:state-update-at f) 10.375) "follow update-at = 10 + 0.3 + 0.075 jitter"))
  (println "entity: make-enemy + set-state (anim/timer/jitter) ok")

  ;; --- FSM decision tick (entity_enemy._update) -----------------------------
  ;; Every test calls state-update directly with an injected view + rng, so the
  ;; transition logic is verified without the voxel map / GL. rng (fn [] 0.0)
  ;; gives the "no evade, no jitter" path; (fn [] 1.0) forces evade / full jitter.
  (let [base  (ent/make-enemy [0.0 0.0 0.0] 0.0)
        see   {:dist 100.0 :angle 1.0 :can-see true}
        blind {:dist 100.0 :angle 1.0 :can-see false}
        far   {:dist 900.0 :angle 1.0 :can-see true}]
    ;; idle + can-see + near  -> wake into attack-aim
    (assert (= (:state (ent/state-update base see 5.0 (fn [] 0.0))) :attack-aim)
            "idle wakes to attack-aim when LOS + near")
    ;; idle + near but blind  -> stays idle
    (assert (= (:state (ent/state-update base blind 5.0 (fn [] 0.0))) :idle)
            "idle stays idle when no LOS")
    ;; idle + far             -> stays idle
    (assert (= (:state (ent/state-update base far 5.0 (fn [] 0.0))) :idle)
            "idle stays idle when too far"))
  ;; FOLLOW transitions
  (let [f0 (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :follow 0.0 (fn [] 0.0))]
    ;; follow + see + in attack band + rng low (<= chance) -> attack-aim
    (assert (= (:state (ent/state-update f0 {:dist 400.0 :angle 0.5 :can-see true}
                                    5.0 (fn [] 0.0))) :attack-aim)
            "follow -> attack-aim in attack band (rng<=chance)")
    ;; follow + see + in attack band + rng high (> chance) -> evade
    (assert (= (:state (ent/state-update f0 {:dist 400.0 :angle 0.5 :can-see true}
                                    5.0 (fn [] 1.0))) :evade)
            "follow -> evade when rng>chance")
    ;; follow + too close (< evade) -> evade regardless of rng
    (assert (= (:state (ent/state-update f0 {:dist 50.0 :angle 0.5 :can-see true}
                                    5.0 (fn [] 0.0))) :evade)
            "follow -> evade when within evade-distance"))
  ;; follow -> aim path: target_yaw tracks player, and aim-check evades if blind
  (let [f0 (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :follow 0.0 (fn [] 0.0))]
    (let [r (ent/state-update f0 {:dist 400.0 :angle 0.5 :can-see true} 5.0 (fn [] 0.0))]
      (assert (= (:state r) :attack-aim) "follow -> attack-aim in attack band (rng<=chance)")
      (assert (approx= (:target-yaw r) 0.5) "aim sets target-yaw to player angle"))
    ;; follow + blind + in band: aim entered, then aim-check evades (no LOS)
    (assert (= (:state (ent/state-update f0 {:dist 400.0 :angle 0.5 :can-see false}
                                    5.0 (fn [] 0.0))) :evade)
            "follow -> aim -> evade when LOS lost in same tick"))
  ;; attack-aim auto-advances to prepare on its own tick (aim is a 0.1s orient)
  (let [a (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :attack-aim 0.0 (fn [] 0.0))]
    (assert (= (:state (ent/state-update a {:dist 200.0 :angle 0.7 :can-see true}
                                5.0 (fn [] 0.0))) :attack-prepare)
            "attack-aim auto-advances to attack-prepare on tick"))
  ;; auto-advance chain: prepare ->(tick) exec [FIRE] ->(tick) recover
  (let [prep (ent/make-enemy [0.0 0.0 0.0] 0.0)
        prep (ent/set-state prep :attack-prepare 0.0 (fn [] 0.0))
        ;; prepare update-at = 0.4; tick at t=0.5 -> advance to exec, fire-now set
        exec (ent/state-update prep {:dist 200.0 :angle 0.0 :can-see true} 0.5 (fn [] 0.0))]
    (assert (= (:state exec) :attack-exec) "prepare auto-advances to exec")
    (assert (true? (:fire-now exec)) "attack-exec sets fire-now"))
  ;; fire-now is a per-frame transient: step-enemy clears it next frame.
  ;; frame A (decision tick: prepare->exec) fires; frame B (no new tick) must not.
  (let [prep (-> (ent/make-enemy [0.0 0.0 0.0] 0.0)
                 (ent/set-state :attack-prepare 0.4 (fn [] 0.0)))   ; update-at 0.8
        view {:dist 200.0 :angle 0.0 :can-see true}
        a    (ent/step-enemy prep view 0.9 0.016 (fn [] 0.0))]      ; 0.9 > 0.8 -> tick -> exec -> fire
    (assert (true? (:fire-now a)) "step-enemy frame A fires (exec entered)")
    (let [b (ent/step-enemy a view 0.917 0.016 (fn [] 0.0))]        ; +1 frame, before exec update-at
      (assert (not (:fire-now b)) "step-enemy frame B does not re-fire (transient cleared)")))
  (println "entity FSM: wake/follow/aim/evade/auto-advance+fire ok")

  ;; --- per-frame motion: yaw lerp + velocity-from-state-speed + anim ---------
  ;; step-enemy's every-frame path (not gated by the decision tick). Use a frame
  ;; where no tick is due so only yaw/vel/anim run. dt small; view irrelevant.
  (let [e (-> (ent/make-enemy [0.0 0.0 0.0] 0.0)
              (assoc :target-yaw (/ Math/PI 2) :on-ground true))
        ;; idle update-at 0.1; step at t=0.0 (< 0.1) -> no tick, only per-frame
        r (ent/step-enemy e {:dist 999.0 :angle 0.0 :can-see false} 0.0 0.016 (fn [] 0.0))]
    ;; yaw 0 -> toward pi/2 by 0.1*delta = pi/2*0.1
    (assert (approx= (:yaw r) (* (/ Math/PI 2) 0.1)) "yaw lerps 10% toward target")
    ;; idle speed mult 0 -> zero horizontal velocity, vy preserved (0)
    (assert (approx= (nth (:vel r) 0) 0.0) "idle vel.x = 0")
    (assert (approx= (nth (:vel r) 2) 0.0) "idle vel.z = 0")
    ;; anim-time advanced by dt
    (assert (approx= (:anim-time r) 0.016) "anim-time += dt"))
  ;; follow (speed mult 1.0) + target-yaw 0 -> forward = +Z at full speed
  (let [f (-> (ent/make-enemy [0.0 0.0 0.0] 0.0)
              (ent/set-state :follow 0.0 (fn [] 0.0))   ; update-at 0.3
              (assoc :target-yaw 0.0 :on-ground true))
        r (ent/step-enemy f {:dist 999.0 :angle 0.0 :can-see false} 0.0 0.016 (fn [] 0.0))]
    (assert (approx= (nth (:vel r) 2) ent/enemy-speed) "follow vel.z = full speed (+Z, yaw 0)")
    (assert (approx= (nth (:vel r) 0) 0.0) "follow vel.x = 0 (yaw 0 -> straight +Z)"))
  ;; patrol (mult 0.5) at yaw pi/2 -> forward = +X at half speed
  (let [p (-> (ent/make-enemy [0.0 0.0 0.0] 0.0)
              (ent/set-state :patrol 0.0 (fn [] 0.0))
              (assoc :target-yaw (/ Math/PI 2) :on-ground true))
        r (ent/step-enemy p {:dist 999.0 :angle 0.0 :can-see false} 0.0 0.016 (fn [] 0.0))]
    (assert (approx= (nth (:vel r) 0) (* 0.5 ent/enemy-speed)) "patrol vel.x = half speed (+X, yaw pi/2)")
    (assert (approx= (nth (:vel r) 2) 0.0) "patrol vel.z = 0"))
  ;; airborne -> horizontal velocity left untouched
  (let [f (-> (ent/make-enemy [0.0 0.0 0.0] 0.0)
              (ent/set-state :follow 0.0 (fn [] 0.0))
              (assoc :on-ground false :vel [7.0 7.0 7.0]))
        r (ent/step-enemy f {:dist 999.0 :angle 0.0 :can-see false} 0.0 0.016 (fn [] 0.0))]
    (assert (approx= (nth (:vel r) 0) 7.0) "airborne keeps vel.x"))
  (println "entity motion: yaw-lerp / velocity-by-state / anim / airborne ok")

  ;; --- damage / death / wake-on-hit / grunt spec ----------------------------
  ;; A non-lethal hit while already alert (follow) just reduces health.
  (let [f (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :follow 0.0 (fn [] 0.0))
        r (ent/receive-damage f 10.0 [1.0 0.0 1.0] 5.0 (fn [] 0.0))]
    (assert (approx= (:health r) (- ent/base-health 10.0)) "non-lethal hit reduces health")
    (assert (= (:state r) :follow) "alert enemy stays follow (no re-wake)")
    (assert (not (:dead? r)) "not dead"))
  ;; A hit while IDLE wakes to follow facing the player.
  (let [i (ent/make-enemy [0.0 0.0 0.0] 0.0)
        r (ent/receive-damage i 10.0 [1.0 0.0 1.0] 5.0 (fn [] 0.0))]
    (assert (= (:state r) :follow) "idle hit wakes to follow")
    (assert (< (Math/abs (- (:target-yaw r) (/ Math/PI 4))) 0.01) "wake faces player (pi/4 to NE)")
    (assert (approx= (:state-update-at r) (+ 5.0 0.3)) "wake follow update-at = t + 0.3"))
  ;; PATROL hit also wakes to follow.
  (let [p (ent/set-state (ent/make-enemy [0.0 0.0 0.0] 0.0) :patrol 0.0 (fn [] 0.0))]
    (assert (= (:state (ent/receive-damage p 1.0 [0.0 0.0 5.0] 5.0 (fn [] 0.0))) :follow)
            "patrol hit wakes to follow"))
  ;; Lethal hit: dead? true, health clamped to 0, state unchanged (caller gibs).
  (let [r (ent/receive-damage (ent/make-enemy [0.0 0.0 0.0] 0.0) 999.0 [1.0 0.0 0.0] 5.0 (fn [] 0.0))]
    (assert (true? (:dead? r)) "lethal hit sets dead?")
    (assert (approx= (:health r) 0.0) "dead health clamped to 0")
    (assert (= (:state r) :idle) "death preserves state"))
  ;; Damage on a dead enemy is a no-op (idempotent).
  (let [dead (assoc (ent/make-enemy [0.0 0.0 0.0] 0.0) :dead? true :health 0.0)
        r    (ent/receive-damage dead 50.0 [1.0 0.0 0.0] 5.0 (fn [] 0.0))]
    (assert (true? (:dead? r)) "dead stays dead")
    (assert (approx= (:health r) 0.0) "dead health stays 0"))
  ;; Grunt spec: health 40, texture 17, model grunt; idle by default.
  (let [g (ent/make-grunt [0.0 0.0 0.0] 0.0 0)]
    (assert (approx= (:health g) ent/grunt-health) "grunt health = 40")
    (assert (= (:texture g) ent/grunt-texture) "grunt texture = 17")
    (assert (= (:model g) :grunt) "grunt model = :grunt")
    (assert (= (:state g) :idle) "grunt with patrol-dir 0 starts idle"))
  ;; Grunt patrol: faces +/-X, state patrol.
  (let [g1 (ent/make-grunt [0.0 0.0 0.0] 0.0 1)
        g2 (ent/make-grunt [0.0 0.0 0.0] 0.0 -1)]
    (assert (= (:state g1) :patrol) "grunt patrol-dir 1 -> patrol")
    (assert (approx= (:target-yaw g1) (/ Math/PI 2)) "patrol +1 faces +X (yaw pi/2)")
    (assert (approx= (:target-yaw g2) (- (/ Math/PI 2))) "patrol -1 faces -X (yaw -pi/2)"))
  ;; Grunt survives 39 dmg (alive) and dies on the 40th.
  (let [g (ent/make-grunt [0.0 0.0 0.0] 0.0 0)]
    (assert (not (:dead? (ent/receive-damage g 39.0 [1.0 0.0 0.0] 5.0 (fn [] 0.0)))) "grunt survives 39 dmg")
    (assert (true? (:dead? (ent/receive-damage g 40.0 [1.0 0.0 0.0] 5.0 (fn [] 0.0)))) "grunt dies on 40 dmg"))
  (println "entity combat: damage/wake/lethal/idempotent + grunt spec ok")

  ;; --- #72: enemy type-spec table (grunt/enforcer/ogre/zombie) ----------------
  ;; q1k3 dispatches map entity type-ids to enemy classes (map.js spawn_class):
  ;; 1 grunt, 2 enforcer, 3 ogre, 4 zombie, 5 hound. Each type overrides health,
  ;; texture, model, size, speed and (ogre/zombie) attack-distance + anim/state
  ;; tables. make-enemy-of-type applies the spec; spawn-from-entities turns the
  ;; decoded map entities into enemies at their world coords (x*32,y*16,z*32).
  (let [g (ent/make-enemy-of-type :grunt    [0 0 0] 0.0 0)
        e (ent/make-enemy-of-type :enforcer [0 0 0] 0.0 0)
        o (ent/make-enemy-of-type :ogre     [0 0 0] 0.0 0)
        z (ent/make-enemy-of-type :zombie   [0 0 0] 0.0 0)]
    ;; per-type stats (q1k3 _init): grunt 40hp/tex17, enforcer 80hp/tex19/sz14x44,
    ;; ogre 200hp/tex20/speed96/atk350, zombie 60hp/tex18/speed0/atk350.
    (assert (approx= (:health g) 40.0)  "grunt health 40")
    (assert (= (:texture g) 17)         "grunt texture 17")
    (assert (approx= (:health e) 80.0)  "enforcer health 80")
    (assert (= (:texture e) 19)         "enforcer texture 19")
    (assert (= (:half e) [14.0 44.0 14.0]) "enforcer size 14x44x14")
    (assert (approx= (:speed o) 96.0)   "ogre speed 96")
    (assert (approx= (:health o) 200.0) "ogre health 200")
    (assert (approx= (:attack-distance o) 350.0) "ogre attack-distance 350")
    (assert (approx= (:speed z) 0.0)    "zombie speed 0")
    (assert (approx= (:attack-distance z) 350.0) "zombie attack-distance 350")
    ;; base grunt/enforcer keep the default 800 attack-distance.
    (assert (approx= (:attack-distance g) 800.0) "grunt attack-distance 800")
    ;; every typed enemy carries its model keyword (render picks the mesh later).
    (assert (= (:model o) :ogre)   "ogre model :ogre")
    (assert (= (:model z) :zombie) "zombie model :zombie"))
  ;; zombie _receive_damage ignores any hit that isn't a gib (>60): a shotgun
  ;; blast of 40 leaves it untouched, a grenade of 80 gibs it.
  (let [z (ent/make-enemy-of-type :zombie [0 0 0] 0.0 0)]
    (assert (approx= (:health (ent/receive-damage z 40.0 [0 0 0] 5.0 (fn [] 0.0))) 60.0)
            "zombie shrugs off 40 dmg (gib-only)")
    (assert (true? (:dead? (ent/receive-damage z 80.0 [0 0 0] 5.0 (fn [] 0.0))))
            "zombie gibs on 80 dmg"))
  ;; zombie + ogre FSM respects the per-type attack-distance: a zombie in follow
  ;; at dist 500 (>350) does NOT enter attack-aim, an ogre at dist 500 (>350)
  ;; likewise; a grunt at dist 500 (<800) does.
  (let [z0 (ent/set-state (ent/make-enemy-of-type :zombie [0 0 0] 0.0 0) :follow 0.0 (fn [] 0.0))
        o0 (ent/set-state (ent/make-enemy-of-type :ogre [0 0 0] 0.0 0) :follow 0.0 (fn [] 0.0))
        g0 (ent/set-state (ent/make-grunt [0 0 0] 0.0 0) :follow 0.0 (fn [] 0.0))]
    (assert (= (:state (ent/state-update z0 {:dist 500.0 :angle 0.0 :can-see true} 5.0 (fn [] 0.0))) :follow)
            "zombie at dist 500 stays following (beyond its 350 attack-range)")
    (assert (= (:state (ent/state-update o0 {:dist 500.0 :angle 0.0 :can-see true} 5.0 (fn [] 0.0))) :follow)
            "ogre at dist 500 stays following (beyond its 350 attack-range)")
    (assert (= (:state (ent/state-update g0 {:dist 500.0 :angle 0.0 :can-see true} 5.0 (fn [] 0.0))) :attack-aim)
            "grunt at dist 500 attacks (within its 800 attack-range)"))
  ;; spawn-from-entities: decode the q1k3 entity type-id to an enemy at its world
  ;; position, with data1 as the patrol direction. Non-enemy entities (type 0
  ;; player, 8 health pickup) are skipped.
  (let [es [{:type 1 :x 1 :y 2 :z 3 :data1 0 :data2 0}   ; grunt at (32,32,96)
            {:type 3 :x 4 :y 0 :z 5 :data1 1 :data2 0}   ; ogre patrolling
            {:type 5 :x 0 :y 0 :z 0 :data1 0 :data2 0}   ; hound
            {:type 0 :x 0 :y 0 :z 0 :data1 0 :data2 0}   ; player spawn — skip
            {:type 8 :x 0 :y 0 :z 0 :data1 0 :data2 0}]  ; pickup — skip
        spawned (ent/spawn-from-entities es)]
    (assert (= 3 (count spawned)) "3 enemies spawn (grunt + ogre + hound); player/pickup skipped")
    (let [[g o h] spawned]
      (assert (= (:model g) :grunt) "first spawned is the grunt")
      (assert (approx= (nth (:pos g) 0) 32.0) "grunt world x = 1*32")
      (assert (approx= (nth (:pos g) 2) 96.0) "grunt world z = 3*32")
      (assert (= (:model o) :ogre) "second spawned is the ogre")
      (assert (= (:state o) :patrol) "ogre data1=1 -> patrol")
      (assert (approx= (:target-yaw o) (/ Math/PI 2)) "ogre patrol faces +X")
      (assert (= (:model h) :hound) "third spawned is the hound")))
  (println "entity types: grunt/enforcer/ogre/zombie specs + spawn-from-entities ok")

  ;; End-to-end: the real m1 map declares a mix of entity types; spawning from
  ;; its decoded entities must yield only valid enemies (grunt/enforcer/ogre/
  ;; zombie), each with a world position and a known model. This is the wiring
  ;; core.clj's spawn-grunts now depends on.
  (let [real (ent/spawn-from-entities (:entities (first (lvl/decode-container lvl-data/bytes))))]
    (assert (pos? (count real)) "m1 spawns at least one enemy")
    (assert (every? #{:grunt :enforcer :ogre :zombie :hound} (map :model real))
            "every m1-spawned enemy is a known type")
    (assert (every? (fn [e] (= 3 (count (:pos e)))) real)
            "every spawned enemy has a world position"))
  (println "entity spawn: real m1 entities -> typed enemies ok")

  ;; --- #73: the hound — melee lunge + contact damage --------------------------
  ;; q1k3 entity_enemy_hound: 25hp/tex22, size 12x16x12, speed 256, attack-range
  ;; 200, evade 64. Its attack is NOT a projectile — on :attack-exec it gets a
  ;; forward velocity lunge (600 fwd, 250 up) and deals 14 on player contact,
  ;; once per exec (guarded by :did-hit).
  (let [h (ent/make-enemy-of-type :hound [0 0 0] 0.0 0)]
    (assert (approx= (:health h) 25.0) "hound health 25")
    (assert (= (:texture h) 22)        "hound texture 22")
    (assert (= (:half h) [12.0 16.0 12.0]) "hound size 12x16x12")
    (assert (approx= (:speed h) 256.0) "hound speed 256")
    (assert (approx= (:attack-distance h) 200.0) "hound attack-distance 200")
    (assert (:melee? h)               "hound is melee")
    (assert (approx= (:melee-damage h) 14.0) "hound melee damage 14"))
  ;; the lunge: a hound in follow within attack-range transitions through the
  ;; instant aim->prepare->exec chain (one decision tick each, 0.0 durations) and
  ;; lands in :attack-exec with a forward leap — NOT a :fire-now (that's ranged).
  ;; Lunging along yaw 0 (+Z): vz=600, vy=250. Follow's update-at is 0.3, so the
  ;; first tick lands at 0.4; the exec lunge applies on the 3rd.
  (let [view {:dist 100.0 :angle 0.0 :can-see true}
        h0 (ent/set-state (ent/make-enemy-of-type :hound [0 0 0] 0.0 0) :follow 0.0 (fn [] 0.0))
        f1 (ent/step-enemy h0 view 0.4   0.016 (fn [] 0.0))   ; follow -> attack-aim
        f2 (ent/step-enemy f1 view 0.416 0.016 (fn [] 0.0))   ; aim -> prepare
        r  (ent/step-enemy f2 view 0.433 0.016 (fn [] 0.0))]  ; prepare -> exec (lunge)
    (assert (= (:state f1) :attack-aim)  "hound frame 1: follow -> attack-aim")
    (assert (= (:state f2) :attack-prepare) "hound frame 2: aim -> prepare")
    (assert (= (:state r) :attack-exec) "hound frame 3: prepare -> attack-exec (lunge)")
    (assert (not (:fire-now r)) "hound lunge is melee, not ranged fire-now")
    (assert (approx= (nth (:vel r) 2) 600.0) "lunge forward (vz=600) along yaw 0")
    (assert (approx= (nth (:vel r) 1) 250.0) "lunge upward (vy=250)")
    (assert (not (:on-ground r)) "lunge leaves the ground")
    (assert (not (:did-hit r)) "lunge starts with did-hit clear"))
  ;; contact damage: a lunging hound overlapping the player deals 14 once, then
  ;; is guarded (a second contact the same exec deals 0). A miss deals 0.
  (let [h  (ent/make-enemy-of-type :hound [0 0 0] 0.0 0)
        ex (ent/set-state h :attack-exec 0.0 (fn [] 0.0))
        [h1 d1] (ent/melee-contact ex [0 0 0] [12 24 12])     ; center overlap
        [h2 d2] (ent/melee-contact h1 [0 0 0] [12 24 12])]    ; same exec again
    (assert (approx= d1 14.0) "first contact deals 14")
    (assert (:did-hit h1) "contact sets the did-hit guard")
    (assert (approx= d2 0.0) "second contact in same exec deals 0 (guarded)")
    (let [[_ d-miss] (ent/melee-contact ex [500 0 0] [12 24 12])] ; far away
      (assert (approx= d-miss 0.0) "non-overlapping hound deals 0")))
  ;; a grunt (non-melee) never deals contact damage even in attack-exec.
  (let [g  (ent/make-grunt [0 0 0] 0.0 0)
        gx (ent/set-state g :attack-exec 0.0 (fn [] 0.0))
        [_ gd] (ent/melee-contact gx [0 0 0] [12 24 12])]
    (assert (approx= gd 0.0) "grunt (ranged) deals no melee contact damage"))
  (println "entity hound: melee lunge + contact-damage (once-per-exec) ok")

  ;; --- player health/damage/death (entity_player._receive_damage/_kill) ------
  ;; q1k3 player: 100 hp, subtract on hit, dead at <=0. No armor / no i-frames.
  (let [p {:health 100.0 :dead? false}]
    (assert (approx= (:health (player/hurt-player p 30.0)) 70.0) "player loses 30 hp")
    (assert (not (:dead? (player/hurt-player p 30.0))) "alive at 70 hp"))
  ;; Lethal: dead? true, health clamped to 0.
  (let [r (player/hurt-player {:health 25.0 :dead? false} 40.0)]
    (assert (true? (:dead? r)) "player dies at <0 hp")
    (assert (approx= (:health r) 0.0) "dead health clamped to 0"))
  ;; Dead player takes no further damage (idempotent).
  (let [r (player/hurt-player {:health 0.0 :dead? true} 50.0)]
    (assert (:dead? r) "dead stays dead")
    (assert (approx= (:health r) 0.0) "dead health unchanged"))
  ;; Exact boundary: 100 - 100 = 0 -> dead.
  (let [r (player/hurt-player {:health 100.0 :dead? false} 100.0)]
    (assert (true? (:dead? r)) "0 hp is death")
    (assert (approx= (:health r) 0.0) "boundary health 0"))
  (println "player combat: health/hurt/lethal/boundary ok")

  ;; --- map-trace line of sight (q1k3 map.js) --------------------------------
  ;; Pure over a hand-built solid-cells set. Cells are 32x16x32 (x>>5, y>>4,
  ;; z>>5). block-at? maps a world point to its cell and tests membership.
  (let [cells #{(lvl/cell-key 0 0 0)}]
    (assert (lvl/block-at? cells 0.0 0.0 0.0) "origin cell is solid")
    (assert (lvl/block-at? cells 16.0 0.0 0.0) "x=16 -> cell(0,0,0) solid")
    (assert (lvl/block-at? cells 31.0 15.0 31.0) "corner -> cell(0,0,0) solid")
    (assert (not (lvl/block-at? cells 32.0 0.0 0.0)) "x=32 -> cell(1,0,0) empty")
    (assert (not (lvl/block-at? cells 0.0 16.0 0.0)) "y=16 -> cell(0,1,0) empty"))
  ;; map-trace: clear path -> nil (no obstruction).
  (let [cells #{}]
    (assert (nil? (lvl/map-trace cells [0.0 8.0 8.0] [120.0 8.0 8.0])) "clear path -> nil"))
  ;; map-trace: solid cell on the path -> hit point (march 16-unit steps, first
  ;; sample is one step AFTER the start). Ray +X into cell(1,0,0)=world x[32,64).
  (let [cells #{(lvl/cell-key 1 0 0)}]
    (let [hit (lvl/map-trace cells [0.0 0.0 0.0] [100.0 0.0 0.0])]
      (assert (some? hit) "blocked path -> hit point, not nil")
      ;; steps: (16,0,0)->cell0 empty; (32,0,0)->cell1 SOLID. hit=[32,0,0].
      (assert (approx= (nth hit 0) 32.0) "hit x = 32 (first sample in cell(1,0,0))")
      (assert (approx= (nth hit 1) 0.0) "hit y preserved")
      (assert (approx= (nth hit 2) 0.0) "hit z preserved")))
  ;; map-trace: solid cell just past the endpoint is NOT hit (steps = len/16).
  (let [cells #{(lvl/cell-key 3 0 0)}]                                ; cell(3,0,0)=world x[96,128)
    (assert (nil? (lvl/map-trace cells [0.0 0.0 0.0] [80.0 0.0 0.0]))
            "solid past endpoint not sampled (len/16 steps)"))
  ;; map-trace: coincident points -> nil (no steps, no obstruction).
  (assert (nil? (lvl/map-trace #{} [5.0 5.0 5.0] [5.0 5.0 5.0])) "coincident -> nil")
  (println "map trace: block-at? / clear / blocked / out-of-range / coincident ok")

  ;; --- enemy-view: the geometry bridge (player + cells -> FSM view) ----------
  ;; enemy-view is pure over (cells, player-pos, enemy). It computes the view
  ;; map {dist angle can-see} that step-enemy consumes — closing the loop between
  ;; the voxel LOS (map-trace) and the FSM without coupling them.
  (let [cells #{}]
    (let [v (ent/enemy-view cells [0.0 0.0 0.0] {:pos [100.0 0.0 0.0]})]
      (assert (approx= (:dist v) 100.0) "dist = 100 on +X")
      ;; enemy at +X faces the player at origin -> looks toward -X -> angle -pi/2.
      (assert (< (Math/abs (- (:angle v) (- (/ Math/PI 2)))) 0.01) "angle = -pi/2 (player due -X)")
      (assert (true? (:can-see v)) "clear LOS -> can-see true"))
    ;; wall cell between enemy(+X) and player blocks LOS.
    (let [wall #{(lvl/cell-key 1 0 0)}                               ; cell x[32,64), sits between 0 and 100
          v (ent/enemy-view wall [0.0 0.0 0.0] {:pos [100.0 0.0 0.0]})]
      (assert (false? (:can-see v)) "wall between -> can-see false")
      (assert (approx= (:dist v) 100.0) "dist unaffected by LOS"))
    ;; a wall NOT on the segment does not block.
    (let [off #{(lvl/cell-key 0 0 5)}]                                ; far off the line of sight
      (assert (true? (:can-see (ent/enemy-view off [0.0 0.0 0.0] {:pos [100.0 0.0 0.0]})))
              "off-path wall does not block")))
  (println "enemy view: dist/angle/can-see over cells + player ok")

  ;; --- step-enemies: the per-tick fold (enemies x cells x player -> [new fired]) -
  ;; Pure fold: each enemy gets a view and is stepped; the ones that entered
  ;; :attack-exec this tick (their :fire-now is set) are collected into `fired`
  ;; so the caller can spawn their projectiles (q1k3 _attack). Returns
  ;; [new-enemies fired]. We trigger a REAL fire by putting a grunt in
  ;; :attack-prepare past its deadline: stepping auto-advances to :attack-exec,
  ;; which sets :fire-now (see entity FSM test above). new-enemies have the
  ;; transient :fire-now stripped; the entries in `fired` retain it.
  (let [cells #{}
        ;; grunt close to player, locked into attack-prepare (update-at ~0.4).
        prep  (-> (ent/make-grunt [50.0 0.0 0.0] 0.0 0)
                  (ent/set-state :attack-prepare 0.0 (fn [] 0.0)))]
    ;; step at t=0.6 (> update-at) -> attack-prepare advances to attack-exec -> fire.
    (let [[enemies fired] (ent/step-enemies cells [0.0 0.0 0.0] [prep] 0.6 0.016 (fn [] 0.0))]
      (assert (= 1 (count fired)) "firing grunt is collected in `fired`")
      (assert (:fire-now (first fired)) "the fired enemy retains :fire-now for spawning")
      (assert (= (:state (first enemies)) :attack-exec) "folded enemy is now in attack-exec")
      (assert (not (:fire-now (first enemies))) "fold strips the transient :fire-now"))
    ;; an idle grunt (no LOS trigger) fires nothing this tick.
    (let [idle (ent/make-grunt [800.0 0.0 0.0] 0.0 0)   ; beyond wake distance
          [_ fired2] (ent/step-enemies cells [0.0 0.0 0.0] [idle] 0.6 0.016 (fn [] 0.0))]
      (assert (zero? (count fired2)) "idle grunt beyond wake range fires nothing")))
  (println "enemy fold: step-enemies fire-collection ok")

  ;; --- step-melee: hound lunge contact folded over the enemy list -------------
  ;; A lunging hound overlapping the player deals its melee damage once; a distant
  ;; hound and a ranged grunt contribute nothing.
  (let [hound (-> (ent/make-enemy-of-type :hound [0.0 0.0 0.0] 0.0 0)
                  (ent/set-state :attack-exec 0.0 (fn [] 0.0)))
        grunt (ent/set-state (ent/make-grunt [0.0 0.0 0.0] 0.0 0) :attack-exec 0.0 (fn [] 0.0))
        [es dmg] (ent/step-melee [hound grunt] [0.0 0.0 0.0] [12.0 24.0 12.0])]
    (assert (approx= dmg 14.0) "step-melee sums the hound's 14 contact damage")
    (assert (:did-hit (first es)) "the hound is marked did-hit after contact")
    (let [[_ dmg-far] (ent/step-melee [hound] [900.0 0.0 0.0] [12.0 24.0 12.0])]
      (assert (approx= dmg-far 0.0) "a distant lunging hound deals no melee damage")))
  (println "enemy fold: step-melee hound contact ok")

  ;; --- enemy locomotion: the 6-arg step-enemy integrates position ------------
  ;; q1k3 entity_enemy._update sets velocity along the facing then runs
  ;; _update_physics (position + gravity + wall/ledge collision + stair-step).
  ;; The port routes this through player/step-physics. Verify: a grounded
  ;; follower walks along its target-yaw, gravity makes a floating enemy fall,
  ;; and a wall/ledge stop turns the enemy (_did_collide).
  (let [floor (into #{} (for [x (range 0 4) z (range 0 6)] (lvl/cell-key x 0 z))) ; top at y=16
        ;; follower facing +Z on the floor: center rests at 16+half.y(28)=44.
        f0 (-> (ent/make-grunt [48.0 44.0 48.0] 0.0 0)
               (ent/set-state :follow 0.0 (fn [] 0.0))
               (assoc :target-yaw 0.0 :on-ground true))
        r  (ent/step-enemy f0 {:dist 100.0 :angle 0.0 :can-see false}
                           0.0 0.016666667 (fn [] 0.0) floor)]
    (assert (> (nth (:pos r) 2) 48.0) "grounded follower walks forward (+Z)")
    (assert (:on-ground r) "follower stays on the ground while walking"))
  ;; gravity: a floating idle enemy with no floor beneath falls this tick.
  (let [e0 (-> (ent/make-grunt [500.0 500.0 500.0] 0.0 0)
               (assoc :on-ground false :vel [0.0 0.0 0.0]))
        r  (ent/step-enemy e0 {:dist 999.0 :angle 0.0 :can-see false}
                           0.0 0.016666667 (fn [] 0.0) #{})]
    (assert (< (nth (:pos r) 1) 500.0) "a floating enemy falls under gravity"))
  ;; wall turn: a follower walking +Z into a tall wall stops and turns by its
  ;; turn-bias (_did_collide, non-patrol). Floor spans z-cells 0..1; wall fills
  ;; z-cell 2 (world z[64,96)) over the enemy's height, so the +Z move is blocked.
  (let [floor (into #{} (concat
                          (for [x (range 0 3) z (range 0 2)] (lvl/cell-key x 0 z))
                          (for [y (range 1 5)] (lvl/cell-key 1 y 2))))   ; tall wall at z-cell 2
        w0 (-> (ent/make-grunt [48.0 44.0 50.0] 0.0 0)
               (ent/set-state :follow 0.0 (fn [] 0.0))
               (assoc :target-yaw 0.0 :on-ground true :turn-bias 0.5))
        r  (ent/step-enemy w0 {:dist 100.0 :angle 0.0 :can-see false}
                           0.0 0.016666667 (fn [] 0.0) floor)]
    (assert (> (Math/abs (:target-yaw r)) 0.0) "hitting a wall turns the enemy (target-yaw changes)")
    (assert (< (+ (nth (:pos r) 2) 12.0) 64.0) "enemy never penetrates the wall (front < 64)"))
  (println "enemy locomotion: walk / gravity / wall-turn ok")

  ;; --- fp-view handedness: world +X must render screen-RIGHT and forward +Z
  ;; must go INTO the screen, matching q1k3's left-handed projection
  ;; (clip.x = world.x). The raw glimmer-gl look-at uses s = cross(f, up) = -X
  ;; at yaw 0, which mirrors X; render/fp-view composes an X mirror to un-mirror
  ;; it. This mirror is the root cause of the reversed left/right controls
  ;; (strafe and mouse-yaw): wish-accel/look are faithful q1k3 (right = +X) but
  ;; the mirrored view put +X on screen-left.
  (let [v (render/fp-view [0.0 0.0 0.0] [0.0 0.0 1.0])    ; eye at origin, +Z forward
        m (mat/->vec v)                                   ; 16 column-major floats
        xform-x (fn [px py pz] (+ (* (nth m 0)  px) (* (nth m 4)  py) (* (nth m 8)  pz) (nth m 12)))
        xform-z (fn [px py pz] (+ (* (nth m 2)  px) (* (nth m 6)  py) (* (nth m 10) pz) (nth m 14)))
        raw (mat/->vec (mat/look-at [0.0 0.0 0.0] [0.0 0.0 1.0] [0.0 1.0 0.0]))]
    (assert (pos? (xform-x 1.0 0.0 0.0)) "world +X -> camera +x (screen right)")
    (assert (neg? (xform-z 0.0 0.0 1.0)) "world +Z (forward) -> camera -z (into screen)")
    (assert (neg? (nth raw 0)) "raw look-at mirrors X (world +X -> camera -x): the bug we fix"))
  (println "fp-view handedness: world +X screen-right, +Z into screen ok")

  ;; --- perf micro-benchmark: persistent vector vs native buffer indexed access --
  ;; mix-frame-buffer (the enemy blend) is the hot path (~71ms/frame for 1 enemy);
  ;; it indexes the model's :verts persistent vector (~9792 floats) ~2856×/frame
  ;; and builds a 1632-element out vector via conj. This measures per-access cost
  ;; of vector nth vs a native float buffer to confirm the cause and pick a fast
  ;; replacement. Stride pattern (* i 31) avoids sequential cache-friendly access.
  (let [n      9792
        reps   2856
        pv     (vec (for [i (range n)] (double i)))
        t-pv   (let [t0 (System/nanoTime)]
                 (loop [i 0 sum 0.0]
                   (if (>= i reps) sum
                       (recur (inc i) (+ sum (double (nth pv (mod (* i 31) n)))))))
                 (- (System/nanoTime) t0))
        nb     (let [p (ffi/alloc (* n (ffi/sizeof :float)))]
                 (dotimes [i n] (ffi/write p :float (* i 4) (double i)))
                 p)
        t-nb   (let [t0 (System/nanoTime)]
                 (loop [i 0 sum 0.0]
                   (if (>= i reps) sum
                       (recur (inc i) (+ sum (ffi/read nb :float (* (mod (* i 31) n) 4))))))
                 (- (System/nanoTime) t0))
        t-conj (let [t0 (System/nanoTime)]
                 (loop [i 0 out []]
                   (if (>= i n) out (recur (inc i) (conj out (double i)))))
                 (- (System/nanoTime) t0))]
    (println (str "[bench] pv nth  " (double (/ t-pv reps 1000.0))
                  " us/op  (total " (double (/ t-pv 1000000.0)) " ms for " reps " reads)"))
    (println (str "[bench] nb read " (double (/ t-nb reps 1000.0))
                  " us/op  (total " (double (/ t-nb 1000000.0)) " ms for " reps " reads)"))
    (println (str "[bench] pv conj build n=" n ": " (double (/ t-conj 1000000.0)) " ms"))
    (ffi/free nb))

  ;; --- FP camera handedness: strafe (D=+X) and yaw turn must both be correct ---
  ;; wish-accel is a faithful q1k3 port: D (right) -> world +X at yaw 0; W ->
  ;; world +Z (forward). Both are verified through the EXACT FP camera the
  ;; renderer uses (view = mirror * look-at, mirror = scaling -1 1 1) via the
  ;; library's own transform-point (no hand-rolled matrix math — that once gave a
  ;; false "inverted" verdict). Camera-space x' sign = screen side (mirror is
  ;; baked into the view; projection doesn't flip x).
  (let [cam-x (fn [yaw p]
                (let [fwd  [(double (* (Math/cos 0.0) (Math/sin yaw))) 0.0
                            (double (* (Math/cos 0.0) (Math/cos yaw)))]
                      view (mat/mul (mat/scaling -1.0 1.0 1.0)
                                    (mat/look-at [0.0 0.0 0.0] fwd [0.0 1.0 0.0]))
                      cs   (mat/transform-point view p)]
                  (nth cs 0)))
        ;; strafe: world +X (D) must be screen-right => cam-x > 0 at yaw 0
        strafe-r (cam-x 0.0 [100.0 0.0 100.0])
        strafe-l (cam-x 0.0 [-100.0 0.0 100.0])
        ;; turn: mouse-right increases yaw (look); a fixed ahead point (0,0,1)
        ;; must move screen-LEFT => cam-x(yaw+0.5) < 0
        turn-pos (cam-x 0.5  [0.0 0.0 1.0])
        turn-neg (cam-x -0.5 [0.0 0.0 1.0])]
    (println (str "[cam] strafe  +X=" (double strafe-r) " (screen-" (if (pos? strafe-r) "RIGHT ok" "LEFT BAD")
                  ")   -X=" (double strafe-l)))
    (println (str "[cam] turn   yaw+0.5 cam-x=" (double turn-pos)
                  " => point moves " (if (neg? turn-pos) "LEFT (correct)" "RIGHT (INVERTED)")))
    (println (str "[cam] turn   yaw-0.5 cam-x=" (double turn-neg)
                  " (should mirror: " (if (pos? turn-neg) "ok" "BAD") ")")))

  ;; --- projectiles: per-kind stats (entity_projectile_*.js _init) -------------
  ;; Every attack in q1k3 is a physical projectile. Check the per-kind stats:
  ;; shell (4 dmg, no gravity, 0.1s), nail (9, no gravity, 3s), plasma (15),
  ;; grenade (bouncy, area 120 over 196u, 2s fuse), gib (10, gravity).
  (let [sh (proj/shell   :player [0.0 0.0 0.0] [0.0 0.0 100.0] 0.0)
        na (proj/nail     :player [0.0 0.0 0.0] [0.0 0.0 100.0] 0.0)
        pl (proj/plasma   :enemy  [0.0 0.0 0.0] [0.0 0.0 100.0] 0.0)
        gr (proj/grenade  :player [0.0 0.0 0.0] [0.0 0.0 100.0] 0.0)
        gi (proj/gib      :enemy  [0.0 0.0 0.0] [0.0 0.0 100.0] 0.0)]
    (assert (approx= (:direct sh) 4.0)  "shell 4 dmg")
    (assert (approx= (:die-at sh) 0.1)  "shell 0.1s life")
    (assert (approx= (:gravity sh) 0.0) "shell no gravity")
    (assert (approx= (:direct na) 9.0)  "nail 9 dmg")
    (assert (approx= (:die-at na) 3.0)  "nail 3s life")
    (assert (approx= (:direct pl) 15.0) "plasma 15 dmg")
    (assert (approx= (:gravity gr) 1.0) "grenade has gravity")
    (assert (approx= (:bounciness gr) 0.5) "grenade bounces (0.5)")
    (assert (approx= (:area gr) 120.0) "grenade area 120")
    (assert (approx= (:radius gr) 196.0) "grenade radius 196")
    (assert (approx= (:die-at gr) 2.0) "grenade 2s fuse")
    (assert (approx= (:direct gi) 10.0) "gib 10 dmg")
    (assert (approx= (:gravity gi) 1.0) "gib has gravity")
    ;; ogre grenade carries the reduced 40 damage
    (let [og (proj/grenade :enemy [0.0 0.0 0.0] [0.0 0.0 1.0] 0.0 40.0)]
      (assert (approx= (:area og) 40.0) "ogre grenade area 40")))
  (println "projectile: per-kind stats ok")

  ;; --- projectile flight + collision (entity_t._update_physics) ---------------
  ;; No walls, no targets: a no-gravity nail advances by vel*tick and survives.
  (let [na (proj/nail :player [0.0 8.0 8.0] [1000.0 0.0 0.0] 0.0)
        {p :proj} (proj/step-projectile #{} na [] 0.0 0.016666667)]
    (assert (some? p) "nail in the open survives a tick")
    (assert (approx= (nth (:pos p) 0) (* 1000.0 0.016666667)) "nail advances by vel*tick")
    (assert (approx= (nth (:vel p) 0) 1000.0) "no gravity/friction: vel.x unchanged"))
  ;; Gravity pulls a gib's vertical velocity down over a tick.
  (let [gi (proj/gib :enemy [0.0 100.0 0.0] [0.0 0.0 0.0] 0.0)
        {p :proj} (proj/step-projectile #{} gi [] 0.0 0.016666667)]
    (assert (neg? (nth (:vel p) 1)) "gib gravity makes vel.y negative"))
  ;; Wall contact kills a non-bouncer and sprays impact fx (no direct hit).
  (let [na (proj/nail :player [40.0 8.0 8.0] [3000.0 0.0 0.0] 0.0)
        cells #{(lvl/cell-key 2 0 0)}                    ; solid world x[64,96)
        {p :proj es :events} (proj/step-projectile cells na [] 0.0 0.016666667)]
    (assert (nil? p) "nail dies on wall contact")
    (assert (some #(= :fx (:t %)) es) "wall impact spawns fx particles")
    (assert (not-any? #(= :hit (:t %)) es) "wall hit deals no entity damage"))
  ;; A grenade bounces off a wall (reflected vel, still alive).
  (let [gr (proj/grenade :player [40.0 8.0 8.0] [3000.0 0.0 0.0] 0.0)
        cells #{(lvl/cell-key 2 0 0)}
        {p :proj} (proj/step-projectile cells gr [] 0.0 0.016666667)]
    (assert (some? p) "grenade survives the wall (bounces)")
    (assert (neg? (nth (:vel p) 0)) "grenade x velocity reflects on bounce"))
  ;; Direct entity hit: a nail reaching an enemy deals its :direct and dies.
  (let [na  (proj/nail :player [0.0 8.0 8.0] [3000.0 0.0 0.0] 0.0)
        en  [{:pos [40.0 8.0 8.0] :half [12.0 28.0 12.0] :dead? false}]
        {p :proj es :events} (proj/step-projectile #{} na en 0.0 0.016666667)]
    (assert (nil? p) "nail dies on entity contact")
    (let [hit (first (filter #(= :hit (:t %)) es))]
      (assert (some? hit) "entity contact emits a :hit event")
      (assert (= :player (:team hit)) "player-team projectile hits an enemy")
      (assert (zero? (:idx hit)) "hit indexes the struck enemy")
      (assert (approx= (:amount hit) 9.0) "nail direct damage is 9")))
  ;; Expiry: a nail past its fuse vanishes silently; a grenade explodes.
  (let [na (proj/nail :player [0.0 0.0 0.0] [0.0 0.0 0.0] 0.0)
        {p :proj es :events} (proj/step-projectile #{} (assoc na :die-at 0.05) [] 0.1 0.016)]
    (assert (nil? p) "expired nail is gone")
    (assert (empty? es) "expired nail emits nothing"))
  (let [gr (proj/grenade :player [0.0 0.0 0.0] [0.0 0.0 0.0] 0.0)
        {p :proj es :events} (proj/step-projectile #{} (assoc gr :die-at 0.05) [] 0.1 0.016)]
    (assert (nil? p) "expired grenade is gone")
    (assert (some #(= :explode (:t %)) es) "grenade fuse -> explosion event"))
  (println "projectile: flight / wall-death / bounce / hit / expiry ok")

  ;; --- splash-damage falloff (scale(dist,0,r,amount,0)) -----------------------
  (assert (approx= (proj/splash-damage 0.0 196.0 120.0) 120.0) "center takes full splash")
  (assert (approx= (proj/splash-damage 98.0 196.0 120.0) 60.0) "half radius -> half damage")
  (assert (approx= (proj/splash-damage 196.0 196.0 120.0) 0.0) "rim -> 0")
  (assert (approx= (proj/splash-damage 300.0 196.0 120.0) 0.0) "beyond rim -> 0")
  (println "projectile: splash-damage falloff ok")

  ;; --- attack spawn helpers (weapons.js + entity_enemy_*._attack) -------------
  ;; Player shotgun: 8 shells, all :player team; with rng 0.5 the +/-0.04 spread
  ;; is exactly 0, so every pellet flies straight down the look (+Z here).
  (let [shells (proj/player-shotgun [0.0 0.0 0.0] 0.0 0.0 0.0 (fn [] 0.5))]
    (assert (= 8 (count shells)) "shotgun throws 8 shells")
    (assert (every? #(= :shell (:kind %)) shells) "all pellets are shells")
    (assert (every? #(= :player (:team %)) shells) "all pellets are player-team")
    (assert (pos? (nth (:vel (first shells)) 2)) "pellets fly forward (+Z look)"))
  ;; Enemy attacks by type: grunt 3 shells, enforcer 1 plasma, ogre 1 grenade (40
  ;; area), zombie 1 gib, hound none (melee). All :enemy team, aimed at the player.
  (let [player-pos [0.0 0.0 100.0]
        atk (fn [type] (proj/enemy-attack (ent/make-enemy-of-type type [0.0 0.0 0.0] 0.0 0)
                                          player-pos 0.0 (fn [] 0.5)))]
    (let [g (atk :grunt)]
      (assert (= 3 (count g)) "grunt fires 3 shells")
      (assert (every? #(and (= :shell (:kind %)) (= :enemy (:team %))) g) "grunt shells are enemy-team")
      (assert (pos? (nth (:vel (first g)) 2)) "grunt shells aim toward the +Z player"))
    (let [e (atk :enforcer)]
      (assert (= 1 (count e)) "enforcer fires 1 plasma")
      (assert (= :plasma (:kind (first e))) "enforcer projectile is plasma"))
    (let [o (atk :ogre)]
      (assert (= 1 (count o)) "ogre fires 1 grenade")
      (assert (= :grenade (:kind (first o))) "ogre projectile is a grenade")
      (assert (approx= (:area (first o)) 40.0) "ogre grenade area 40"))
    (let [z (atk :zombie)]
      (assert (= 1 (count z)) "zombie fires 1 gib")
      (assert (= :gib (:kind (first z))) "zombie projectile is a gib"))
    (assert (empty? (atk :hound)) "hound is melee: fires no projectile"))
  (println "projectile: player-shotgun + enemy-attack spawns ok")

  ;; --- step-projectiles fold + routing ----------------------------------------
  ;; A player shell reaches an enemy (enemy-hit); an enemy shell reaches the
  ;; player (player-hit). Verify both route to the correct team in one fold.
  (let [enemies [{:pos [40.0 8.0 8.0] :half [12.0 28.0 12.0] :dead? false}]
        player  {:pos [0.0 8.0 8.0] :half [12.0 24.0 12.0]}
        pshell  (proj/shell :player [0.0 8.0 8.0] [3000.0 0.0 0.0] 0.0)   ; -> enemy at +X
        eshell  (proj/shell :enemy  [40.0 8.0 8.0] [-3000.0 0.0 0.0] 0.0) ; -> player at origin
        [remain events] (proj/step-projectiles #{} [pshell eshell] enemies player 0.0 0.016666667)]
    (assert (some #(and (= :hit (:t %)) (= :player (:team %))) events)
            "player shell -> enemy-hit event")
    (assert (some #(and (= :hit (:t %)) (= :enemy (:team %))) events)
            "enemy shell -> player-hit event")
    (assert (zero? (count remain)) "both shells died on contact"))
  (println "projectile: step-projectiles fold routes hits by team ok")

  ;; --- dynamic light array (q1k3 r_push_light + fragment loop) ----------------
  ;; fade: full (×10) within 768u, linear to 0 at 1024u, clamped beyond.
  (assert (approx= (light/fade 768.0 1.0) 10.0) "light full within 768u")
  (assert (approx= (light/fade 1024.0 1.0) 0.0) "light gone at 1024u")
  (assert (approx= (light/fade 896.0 1.0) 5.0)  "light half-faded at 896u")
  (assert (approx= (light/fade 2000.0 1.0) 0.0) "light clamped to 0 past far range")
  (assert (approx= (light/fade 100.0 2.0) 20.0) "intensity scales the fade")
  ;; pack-lights: colour pre-multiplied by fade, packed two vec3 per light;
  ;; returns [array packed-count] so the shader loops only over live lights.
  (let [cam  [0.0 0.0 100.0]
        [arr n] (light/pack-lights [{:pos [0.0 0.0 0.0] :intensity 1.0 :color [255.0 128.0 64.0]}] cam)]
    (assert (= (alength arr) (* light/max-lights 6)) "packed array is max-lights * 6 floats")
    (assert (= n 1) "one in-range light -> packed-count 1")
    (assert (approx= (aget arr 0) 0.0) "light pos.x packed")
    (assert (approx= (aget arr 2) 0.0) "light pos.z packed")
    ;; dist 100 < 768 -> fade 10; colour *= 10
    (assert (approx= (aget arr 3) 2550.0) "colour.r pre-multiplied by fade (255*10)")
    (assert (approx= (aget arr 4) 1280.0) "colour.g pre-multiplied by fade (128*10)")
    (assert (approx= (aget arr 5) 640.0)  "colour.b pre-multiplied by fade (64*10)"))
  ;; a light beyond the far range contributes nothing (skipped, count 0).
  (let [[arr n] (light/pack-lights [{:pos [0.0 0.0 5000.0] :intensity 1.0 :color [255.0 255.0 255.0]}]
                                   [0.0 0.0 0.0])]
    (assert (approx= (aget arr 3) 0.0) "far light packs no colour")
    (assert (= n 0) "far light -> packed-count 0"))
  ;; more than max-lights are capped (only the first max-lights land).
  (let [many (repeat (* 3 light/max-lights)
                     {:pos [0.0 0.0 0.0] :intensity 1.0 :color [10.0 10.0 10.0]})
        [arr n] (light/pack-lights many [0.0 0.0 0.0])]
    (assert (= (alength arr) (* light/max-lights 6)) "array stays max-lights wide with overflow")
    (assert (= n light/max-lights) "packed-count caps at max-lights")
    (assert (approx= (aget arr (- (* light/max-lights 6) 3)) 100.0) "last slot filled (10*10)"))
  (println "light: fade / pack / far-cull / overflow-cap ok")

  ;; --- respawn: a dead player resets to spawn at full health (q1k3 game_init) -
  ;; player/_kill sets dead; game_init(map_index) after 2s re-spawns the player.
  ;; respawn-player folds that reset into the state map: position/velocity/
  ;; on-ground/look reset, health back to max, dead? cleared. :time is preserved.
  (let [dead {:p [100.0 200.0 300.0] :v [5.0 5.0 5.0] :a [1.0 0.0 0.0]
              :on-ground true :yaw 1.2 :pitch 0.4 :f 10
              :health 0.0 :dead? true :time 42.0 :keys #{:forward}}
        r    (player/respawn-player dead [10.0 20.0 30.0])]
    (assert (not (:dead? r)) "respawn clears dead?")
    (assert (approx= player/player-max-health (:health r)) "respawn restores full health")
    (assert (approx= 10.0 (nth (:p r) 0)) "respawn moves player to spawn x")
    (assert (approx= 30.0 (nth (:p r) 2)) "respawn moves player to spawn z")
    (assert (approx= 0.0 (nth (:v r) 0)) "respawn zeroes velocity")
    (assert (approx= 0.0 (:yaw r)) "respawn resets yaw")
    (assert (approx= 0.0 (:pitch r)) "respawn resets pitch")
    (assert (approx= 42.0 (:time r)) "respawn preserves game time")
    (assert (= #{:forward} (:keys r)) "respawn preserves held keys"))
  (println "player respawn: dead -> spawn/full-health/no-velocity ok")

  ;; --- particles: spawn N blood, gravity integrates, life culls (q1k3 _spawn) -
  ;; entity_t._spawn_particles: v = ((rand-0.5)*spd, rand*spd, (rand-0.5)*spd),
  ;; die_at = time + life + rand*life*0.2. With a constant rng=0.5, x/z drift is
  ;; zero and vy = blood-speed; gravity pulls vy down over the step.
  (let [rng   (fn [] 0.5)
        ps    (part/spawn-blood [0.0 0.0 0.0] 6 rng)]
    (assert (= 6 (count ps)) "spawn-blood makes N particles")
    (doseq [p ps]
      (assert (pos? (:life p)) "particle has positive life")
      (assert (approx= (:life p) (:max-life p)) "fresh particle life == max-life"))
    ;; one step: vy decreases by gravity*dt, pos advances by vel*dt
    (let [dt     0.05
          ps2    (part/step-particles ps dt)
          vy0    (nth (:vel (first ps)) 1)
          vy1    (nth (:vel (first ps2)) 1)]
      (assert (approx= (+ vy0 (* part/particle-grav dt)) vy1)
              (str "step: vy += gravity*dt; got " vy1 " from " vy0))
      ;; particle moved: pos.y increased (vy0>0) then this step
      (assert (> (nth (:pos (first ps2)) 1) 0.0) "step: particle rose this tick"))
    ;; exhaust life -> all culled
    (let [dead-all (loop [q ps n 0]
                     (if (or (empty? q) (> n 200)) q
                         (recur (part/step-particles q 0.05) (inc n))))]
      (assert (zero? (count dead-all)) "particles culled once life expires")))
  (println "particle: spawn/step-gravity/life-cull ok")

  ;; --- pickups: health +25 within 40u, consume; outside / consumed = no-op -----
  ;; entity_pickup_t._update: if (vec3_dist(this.p, player.p) < 40) pickup().
  ;; health: +25 then kill. apply-pickups folds all pickups against one player.
  (let [near (pkup/make-health-pickup [30.0 0.0 0.0])   ; dist 30 < 40
        far  (pkup/make-health-pickup [0.0 0.0 50.0])   ; dist 50 >= 40
        used (assoc (pkup/make-health-pickup [10.0 0.0 0.0]) :consumed? true)
        pl   {:p [0.0 0.0 0.0] :health 50.0}]
    (assert (= :health (:type near)) "health pickup typed")
    (assert (approx= pkup/health-amount (:amount near)) "health pickup amount 25")
    (let [[pl' [n' f' u']] (pkup/apply-pickups pl [near far used])]
      (assert (approx= 75.0 (:health pl')) (str "near pickup heals +25 -> 75, got " (:health pl')))
      (assert (:consumed? n') "near pickup consumed after apply")
      (assert (not (:consumed? f')) "far pickup not consumed")
      (assert (:consumed? u') "already-consumed stays consumed-marked")
      ;; a second apply on the result heals nothing more (near now consumed)
      (let [[pl2 _] (pkup/apply-pickups pl' [n' f' u'])]
        (assert (approx= 75.0 (:health pl2)) "no double-heal from consumed pickup"))
      ;; far + an already-consumed pickup alone heal nothing (stays at base 50)
      (let [[pl-far _] (pkup/apply-pickups pl [far used])]
        (assert (approx= 50.0 (:health pl-far)) "far/used alone heal nothing"))))
  (println "pickup: health +25 in radius / consume / idempotent ok")

  ;; --- HUD: muzzle flash window is muzzle-duration ----------------------------
  ;; muzzle flash: visible for muzzle-duration after fire, then off; nil fire-time = off
  (assert (true?  (hud/muzzle-active? 1.00 0.97)) "flash on within window")
  (assert (false? (hud/muzzle-active? 1.00 0.90)) "flash off after window")
  (assert (false? (hud/muzzle-active? 5.00 nil))  "no fire ever -> no flash")
  (println "hud: muzzle-flash window ok")

  ;; --- overlay geometry: status bar / numbers / crosshair / viewmodel ---------
  ;; Each builder returns interleaved pos3+color3 floats (6/vertex, 6 verts/quad).
  ;; status bar: one full-width quad = 36 floats.
  (assert (= 36 (count (ov/status-bar-verts 800 600))) "status bar is one quad (36 floats)")
  ;; seven-segment numbers: each digit is >=2 and <=7 segment quads (36 floats each);
  ;; "8" lights all seven, "1" only two, and a multi-digit number scales by count.
  (let [n1  (ov/number-verts 1 0.0 0.0 40.0 [1.0 1.0 1.0])
        n8  (ov/number-verts 8 0.0 0.0 40.0 [1.0 1.0 1.0])
        n88 (ov/number-verts 88 0.0 0.0 40.0 [1.0 1.0 1.0])]
    (assert (= (* 2 36) (count n1))  "digit 1 = 2 segments")
    (assert (= (* 7 36) (count n8))  "digit 8 = 7 segments")
    (assert (= (* 2 (count n8)) (count n88)) "88 is two 8s")
    ;; digits advance to the right: the second digit's x > first digit's x
    (assert (> (nth n88 (+ (count n8) 0)) (nth n88 0)) "second digit is further right"))
  ;; crosshair: two quads (horizontal + vertical arm) centered at (w/2, h/2)
  (let [c (ov/crosshair-verts 800 600)
        xs (map c (range 0 (count c) 6))]
    (assert (= (* 2 36) (count c)) "crosshair is two quads")
    (assert (approx= 400.0 (/ (+ (apply min xs) (apply max xs)) 2.0)) "crosshair centered on w/2"))
  ;; particle points: one GL_POINTS vertex per particle (pos3 + color3 = 6 floats)
  (let [ps  [{:pos [1.0 2.0 3.0]} {:pos [4.0 5.0 6.0]}]
        fv  (ov/particle-point-verts ps)]
    (assert (= (* 6 2) (count fv)) (str "2 particles => 12 floats (1 point each), got " (count fv)))
    (assert (approx= (aget fv 0) 1.0) "point 0 carries the particle position")
    (assert (approx= (aget fv 6) 4.0) "point 1 carries the particle position"))
  ;; viewmodel: at least one gun quad; muzzle flash adds geometry (more floats)
  (let [idle  (ov/viewmodel-verts false)
        flash (ov/viewmodel-verts true)]
    (assert (>= (count flash) (count idle)) "flash viewmodel has >= verts than idle")
    (assert (> (count idle) 0) "viewmodel always draws something")
    ;; the builders must return a FLAT float vector (pos3+color3, 6/vertex), not a
    ;; nested structure — draw-flat! uploads (count verts)/6 vertices straight to
    ;; the VBO, so any nesting silently breaks the upload.
    (let [flat? (fn [v] (and (pos? (count v))
                             (zero? (rem (count v) 6))
                             (every? #(instance? Number %) v)))]
      (assert (flat? idle)  (str "viewmodel idle is a flat float vector; got count=" (count idle)))
      (assert (flat? flash) (str "viewmodel flash is a flat float vector; got count=" (count flash)))
      ;; idle is 4 body quads (24 verts = 144 floats); flash adds 1 quad (30 verts).
      (assert (approx= 144.0 (double (count idle)))  (str "idle = 4 quads = 144 floats; got " (count idle)))
      (assert (approx= 180.0 (double (count flash))) (str "flash = 5 quads = 180 floats; got " (count flash)))))
  (println "overlay: status-bar / numbers / crosshair / particle-point / viewmodel ok")

  ;; --- #65/#74 HUD: ammo readout (faithful GL analog of q1k3's ∞ text) -------
  ;; q1k3 draws the active weapon's ammo as DOM text — `weapon._needs_ammo ?
  ;; weapon._ammo : '∞'`. The shotgun is needs-ammo=0, so it shows ∞. The fps-demo
  ;; overlay has no text renderer (flat shader is pos3+color3, no glyph atlas), so
  ;; the faithful analog draws ∞ procedurally: two ring outlines (one quad strip
  ;; per ring) — drawable with the existing flat shader, no new asset. Only the
  ;; shotgun exists in the port, so ammo is always ∞; the function draws the glyph.
  (let [v  (ov/ammo-verts [400.0 200.0])
        n  (count v)
        xs (map v (range 0 n 6))
        ys (map v (range 1 n 6))]
    (assert (pos? n) "ammo glyph draws geometry")
    (assert (zero? (rem n 6)) "ammo verts are flat (6 floats/vertex)")
    (assert (every? #(instance? Number %) v) "ammo verts are a flat float vector")
    (let [xmin (apply min xs) xmax (apply max xs)
          ymin (apply min ys) ymax (apply max ys)
          cx (/ (+ xmin xmax) 2.0) cy (/ (+ ymin ymax) 2.0)
          half-w (/ (- xmax xmin) 2.0) half-h (/ (- ymax ymin) 2.0)
          r ov/ammo-radius t ov/ammo-thickness]
      (assert (approx= cx 400.0) "glyph centered at requested cx")
      (assert (approx= cy 200.0) "glyph centered at requested cy")
      (assert (> half-w half-h) "glyph wider than tall (two rings side by side)")
      (assert (approx= half-w (+ (* 2.0 r) (/ t 2.0))) "glyph half-width = 2r + t/2")
      (assert (approx= half-h (+ r (/ t 2.0))) "glyph half-height = r + t/2")))
  (println "overlay: ammo infinity glyph (procedural, no text renderer) ok")

  ;; --- ttt texture DSL interpreter (issue #17) ---------------------------------
  ;; fps-demo.texture interprets q1k3's packed texture definitions into flat RGBA
  ;; byte buffers. Colours pack 16-bit RGBA4: r,g,b = nibble*17, a = nibble*17
  ;; (q1k3 uses nibble/15 as a float alpha; for RGBA8 bytes that's nibble*17). The
  ;; emboss op draws the top colour at (-1,-1), bottom at (+1,+1), fill centred;
  ;; an alpha-0 colour paints nothing (source-over). All pure + GL-free.
  ;; bg fill: a 2x2 opaque red (0xF00F) decodes to four [255 0 0 255] pixels.
  (let [t  (ttt/decode-texture [2 2 61455] [] (fn [] 0.0))
        px (:pixels t)]
    (assert (= 2 (:w t))   "texture width from header")
    (assert (= 16 (count px)) "2x2x4 = 16 byte values")
    (assert (= [255 0 0 255] [(aget px 0) (aget px 1) (aget px 2) (aget px 3)]) "opaque-red bg"))
  ;; rect op (0): top/bottom = 0 (alpha 0, no-op) so only the green fill lands in
  ;; the rect interior; the opaque-black bg stays outside.
  (let [t  (ttt/decode-texture [4 4 15  0 1 1 2 2 0 0 3855] [] (fn [] 0.0))
        px (:pixels t)
        at (fn [x y] (let [o (* (+ (* y 4) x) 4)] [(aget px o) (aget px (inc o)) (aget px (+ o 2)) (aget px (+ o 3))]))]
    (assert (= [0 0 0 255]   (at 0 0)) "bg outside rect stays black")
    (assert (= [0 255 0 255] (at 1 1)) "rect fill is green")
    (assert (= [0 255 0 255] (at 2 2)) "rect fill corner")
    (assert (= [0 0 0 255]   (at 3 3)) "bg outside rect stays black"))
  ;; draw-prev op (4): a 2x2 texture that blits texture 0 (opaque red) over its
  ;; black bg ends up fully red — exercises decode-all's ordered accumulator, the
  ;; nearest-neighbour blit, and source-over alpha.
  (let [ts (ttt/decode-all [[2 2 61455]
                            [2 2 15  4 0 0 0 2 2 15]]
                           (fn [] 0.0))
        px (:pixels (nth ts 1))]
    (assert (= 2 (count ts)) "decode-all returns one map per definition")
    (assert (= [255 0 0 255] [(aget px 0) (aget px 1) (aget px 2) (aget px 3)]) "draw-prev blits red over black"))
  (println "texture: ttt bg/rect/draw-prev decode ok")

  ;; Decode all 31 real q1k3 texture definitions (textures/data) — a smoke test
  ;; that exercises every op (0 rect/emboss, 1 rect-multiple, 2 noise, 3 text
  ;; skip, 4 draw-prev incl. scaled blits like tex1's 64x512 stretch) on real
  ;; data without throwing, and that each buffer is exactly w*h*4 bytes.
  ;; rng=0.5 so noise actually lands non-trivial alpha.
  (let [ts  (ttt/decode-all textures/data (fn [] 0.5))
        bad (filter (fn [t] (not= (count (:pixels t)) (* (:w t) (:h t) 4))) ts)]
    (assert (= 31 (count ts)) (str "31 q1k3 textures; got " (count ts)))
    (assert (empty? bad) "every texture buffer is w*h*4 bytes")
    (assert (= [64 64] ((juxt :w :h) (nth ts 0)))  "tex0 is 64x64 stone")
    (assert (= [32 32] ((juxt :w :h) (nth ts 4)))  "tex4 is 32x32")
    (assert (= [32 32] ((juxt :w :h) (nth ts 30))) "tex30 is 32x32")
    ;; the index map in textures.clj claims two size classes only.
    (assert (= #{[64 64] [32 32]} (set (map (juxt :w :h) ts)))
            "textures are 64x64 or 32x32"))
  (println "texture: all 31 q1k3 defs decode (ops 0-4, scaled draw-prev) ok")

  ;; --- startup guard: app namespaces load + bulk texture upload round-trips -----
  ;; The GUI loop itself can't run without a display, but two things are
  ;; headlessly checkable and BOTH must hold or `-M:run` shows no window:
  ;;   (1) glimmer.core + fps-demo.core LOAD (a load throw aborts -main before
  ;;       any window appears). The runtime `require :as` alias is NOT visible to
  ;;       the compiler at the call site, so we resolve the app fn via its
  ;;       namespace var rather than a bare `core/app` symbol.
  ;;   (2) the texture-array upload primitive (byte-array -> ffi/write-array,
  ;;       replacing a 508K-call per-byte ffi/write loop that froze realize!)
  ;;       round-trips the bytes intact.
  (try
    (require '[glimmer.core])
    (require '[fps-demo.core])
     (let [app-fn (deref (find-var 'fps-demo.core/app))
           tree   (app-fn)]
       ;; app root is a bare :gl-area. The old controls row (a sensitivity
       ;; :scale + invert :checkbutton above the pane) was removed: it stole
       ;; keyboard focus and swallowed the arrow keys. With only the area as
       ;; root, the toplevel window's EventControllerKey gets them and the area
       ;; fills the whole window.
       (assert (= (first tree) :gl-area) "app root is a :gl-area (no controls row)")
       (let [gl-props (second tree)]
         (assert (true? (:hexpand gl-props)) "gl-area expands to fill the window")
         (assert (true? (:vexpand gl-props)) "gl-area expands vertically")
         (assert (:on-button gl-props) "gl-area carries :on-button (cursor-lock entry)")
         (assert (:on-key gl-props) "gl-area carries :on-key (Escape unlock)")))
     (println "startup: glimer.core + fps-demo.core load; app tree is [:gl-area] ok")
    (catch Exception e
      (println "startup: FAILED —")
      (println "  msg:" (ex-message e))
      (loop [ex e depth 0]
        (when (and ex (< depth 5))
          (println "  [" depth "] " (.getName (class ex)) ":" (.getMessage ex))
          (recur (.getCause ex) (inc depth))))
      (assert false "startup guard: app namespaces must load")))

  ;; --- mouse-capture ns loads + delta reads (CoreGraphics defcfn resolved) ---
  ;; capture/delta calls CGGetLastMouseDelta, a benign query (no dissociation),
  ;; so it's safe in the headless shell and proves the defcfn bindings resolved.
  (require '[fps-demo.capture :as capture])
  (let [[dx dy] (capture/delta)]
    (assert (and (number? dx) (number? dy)) "capture/delta returns a [dx dy] pair"))
  (println "capture: CoreGraphics bindings resolved; delta reads [dx dy] ok")

  ;; bulk texture-upload primitive: build a 508K byte buffer, write-array it to
  ;; native memory in one call, read it back, confirm bytes are intact. This is
  ;; the primitive make-texture-atlas now depends on (was a per-byte loop).
  (let [n   (* 31 64 64 4)                       ; same size as the real texture blob
        src (vec (for [i (range n)] (mod i 251))) ; deterministic 0-250 byte values
        ba  (byte-array (map int src))
        ptr (ffi/alloc n)]
    (ffi/write-array ptr ba)
    (let [back (vec (ffi/read-array ptr n))]
      (ffi/free ptr)
      (assert (= (count back) n) "read-array returns every byte written")
      (assert (= back src) "write-array/read-array round-trip is byte-exact")
      (assert (zero? (nth back 0)) "first byte is 0")
      (assert (= 250 (nth back 250)) "offset 250 holds 250")))
  (println "startup: byte-array -> write-array -> read-array round-trip ok (508K)")

  (println "check: ok"))
