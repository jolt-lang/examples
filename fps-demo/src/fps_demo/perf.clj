(ns fps-demo.perf
  "Per-frame performance probe (headless). Reports the realistic per-frame CPU
  cost after the pose-cache + particle-builder optimizations. The enemy path is
  measured both cold (first frame, cache misses) and warm (steady state, hits).
  Run: joltc -M:perf."
  (:require [fps-demo.map         :as lvl]
            [fps-demo.maps.l      :as lvl-data]
            [fps-demo.model       :as model]
            [fps-demo.models.unit :as unit]
            [fps-demo.entity      :as ent]
            [fps-demo.projectile  :as proj]
            [fps-demo.player      :as player]
            [fps-demo.light       :as light]
            [fps-demo.particle    :as part]
            [fps-demo.overlay     :as ov]
            [glimmer-gl.gl        :as gl]
            [jolt.ffi             :as ffi]))

(def ^:private tick 0.016666667)
(def ^:private pose-mix-steps 10)
(defn- p [& xs] (apply prn xs) (flush))
(defn- us [ns n] (/ (double ns) n 1000.0))
(defn- time-it [n f] (dotimes [_ 5] (f)) (let [t0 (System/nanoTime)] (dotimes [_ n] (f)) (- (System/nanoTime) t0)))

(defn- qkey [fa fb mix]
  [(int fa) (int fb)
   (int (min (double (dec pose-mix-steps))
             (Math/floor (* (double mix) pose-mix-steps))))])

(defn -main [& _]
  (let [m1     (first (lvl/decode-container lvl-data/bytes))
        mdl    (model/init-model unit/bytes 2.5 2.2 2.5)
        spawn  [1088.0 768.0 96.0]
        ;; advance anim-time across the run cycle so poses actually vary
        grunts (vec (for [i (range 6)]
                      (assoc (ent/make-grunt [(+ (spawn 0) 200.0 (* i 60.0))
                                              (spawn 1) (+ (spawn 2) (* i 40.0))] 0.0 0)
                             :anim-time (* (mod i 4) 0.05))))
        parts0 (part/spawn-blood spawn 16 (fn [] (Math/random)))
        iters  400
        ;; COLD: every iteration evicts the cache (mix-frame-buffer + write-floats
        ;; per enemy), i.e. the old per-frame cost.
        cold-ns (time-it iters
                   (fn [] (doseq [e grunts]
                            (let [[fa fb mix] (model/enemy-frame-anim e)
                                  buf (model/mix-frame-buffer mdl fa fb mix)
                                  ptr (gl/write-floats (:data buf))]
                              (ffi/free ptr)))))
        ;; WARM: a persistent cache across all iterations (steady state). First
        ;; iteration builds; the rest are map lookups — this is the real per-frame
        ;; cost after the pose cache.
        cache (atom {})
        warm-ns (let [t0 (System/nanoTime)]
                  (dotimes [_ iters]
                    (doseq [e grunts]
                      (let [[fa fb mix] (model/enemy-frame-anim e)
                            k (qkey fa fb mix)]
                        (when-not (get @cache k)
                          (let [buf (model/mix-frame-buffer mdl fa fb mix)
                                ptr (gl/write-floats (:data buf))]
                            (swap! cache assoc k ptr)))))) ; ptrs leak — demo only
                  (- (System/nanoTime) t0))
        parts-ns (time-it iters (fn [] (ov/particle-point-verts parts0)))
        ;; overlay upload: 4 write-floats passes (viewmodel/ammo constant in real
        ;; life — measured here to size the static-VBO win still on the table)
        hud (ov/hud-bar-verts 640 480 0.6)
        pcv (ov/particle-point-verts parts0)
        vmv (ov/viewmodel-verts true)
        amv (ov/ammo-verts [45.0 70.0])
        up-ns (time-it iters (fn [] (doseq [v [hud pcv vmv amv]]
                                     (ffi/free (gl/write-floats v)))))]
    (p "[perf] enemies: 6 | particles: 16 | iters:" iters
       "| unique poses warmed:" (count @cache))
    (p "[perf] ---- ENEMY path (6 enemies, mix+write-floats) ----")
    (p "[perf]   COLD (rebuild every frame) :" (format "%8.1f us" (us cold-ns iters)) "(pre-cache cost)")
    (p "[perf]   WARM (pose cache, steady) :" (format "%8.1f us" (us warm-ns iters)) "(lookup only)")
    (p "[perf]   savings                   :" (format "%8.1f us" (- (us cold-ns iters) (us warm-ns iters))))
    (p "[perf] ---- OVERLAY (current) ----")
    (p "[perf]   particle-point-verts x16    :" (format "%8.1f us" (us parts-ns iters)))
    (p "[perf]   overlay upload (4x wf)    :" (format "%8.1f us" (us up-ns iters))
       "(viewmodel+ammo constant → static VBO candidate)")
    ;; ---- SIMULATION path (the per-tick game update, added combat) ----------
    ;; step-enemies now integrates physics (collision queries into the solid-cell
    ;; set per substep), step-projectiles flies every projectile, particles are
    ;; stepped, and the light array is packed. Measured against the real m1 cells
    ;; at a realistic combat load.
    (let [cells    (lvl/solid-cells (:blocks m1))
          player-p spawn
          rng      (fn [] (Math/random))
          en6      (mapv #(assoc % :state :follow) grunts)
          projs    (into [] (concat
                              (proj/player-shotgun spawn 0.0 0.0 0.0 rng)      ; 8 shells
                              (mapcat #(proj/enemy-attack % player-p 0.0 rng) en6)))
          ptarget  {:pos player-p :half player/half}
          parts60  (part/spawn-blood spawn 60 rng)
          lights   [{:pos spawn :intensity 10.0 :color [255.0 235.0 205.0]}
                    {:pos [(+ (spawn 0) 50) (spawn 1) (spawn 2)] :intensity 5.0 :color [255.0 128.0 0.0]}]
          se-ns    (time-it iters (fn [] (ent/step-enemies cells player-p en6 1.0 tick rng)))
          sp-ns    (time-it iters (fn [] (proj/step-projectiles cells projs en6 ptarget 1.0 tick)))
          pt-ns    (time-it iters (fn [] (part/step-particles parts60 tick)))
          pl-ns    (time-it iters (fn [] (light/pack-lights lights player-p)))
          pcv60-ns (time-it iters (fn [] (ov/particle-point-verts parts60)))]
      (p "[perf] ---- SIMULATION (per-tick game update) ----")
      (p "[perf]   step-enemies x6 (physics)  :" (format "%8.1f us" (us se-ns iters)))
      (p "[perf]   step-projectiles x" (count projs) "       :" (format "%8.1f us" (us sp-ns iters)))
      (p "[perf]   step-particles x60         :" (format "%8.1f us" (us pt-ns iters)))
      (p "[perf]   pack-lights                :" (format "%8.1f us" (us pl-ns iters)))
      (p "[perf]   particle-point-verts x60     :" (format "%8.1f us" (us pcv60-ns iters)))
      (let [warm (us warm-ns iters)
            parts (us parts-ns iters)
            up (us up-ns iters)
            sim (+ (us se-ns iters) (us sp-ns iters) (us pt-ns iters) (us pl-ns iters))
            total (+ warm parts up sim)]
        (p "[perf] ---- TOTALS ----")
        (p "[perf]   render build (warm)      :" (format "%8.1f us" (+ warm parts up)))
        (p "[perf]   simulation               :" (format "%8.1f us" sim))
        (p "[perf]   TOTAL CPU/frame          :" (format "%8.1f us" total)
           "=> ~" (format "%.0f" (/ 1000000.0 (max total 1.0))) "fps CPU ceiling")))))
