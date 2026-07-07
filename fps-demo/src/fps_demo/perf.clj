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
        parts-ns (time-it iters (fn [] (ov/particle-box-verts parts0)))
        ;; overlay upload: 4 write-floats passes (viewmodel/ammo constant in real
        ;; life — measured here to size the static-VBO win still on the table)
        hud (ov/hud-bar-verts 640 480 0.6)
        pcv (ov/particle-box-verts parts0)
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
    (p "[perf]   particle-box-verts x16    :" (format "%8.1f us" (us parts-ns iters)))
    (p "[perf]   overlay upload (4x wf)    :" (format "%8.1f us" (us up-ns iters))
       "(viewmodel+ammo constant → static VBO candidate)")
    (let [warm (us warm-ns iters)
          parts (us parts-ns iters)
          up (us up-ns iters)
          total (+ warm parts up)]
      (p "[perf]   TOTAL CPU/frame (warm)   :" (format "%8.1f us" total)
         "=> ~" (format "%.0f" (/ 1000000.0 (max total 1.0))) "fps CPU ceiling"))))
