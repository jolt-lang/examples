(ns fps-demo.timing
  "Pure-compute timing of everything init!/core top-level do at realize/load
  time — NO GL calls, runs headless. Run: joltc -M:timing. Flushes each line.
  If one step eats tens of seconds, that's the startup/black-screen cause
  (render! stays nil->black until init! returns)."
  (:require [fps-demo.map         :as lvl]
            [fps-demo.maps.l      :as lvl-data]
            [fps-demo.texture     :as ttt]
            [fps-demo.textures    :as textures]
            [fps-demo.textured    :as tex]
            [fps-demo.model       :as model]
            [fps-demo.models.unit :as unit]
            [fps-demo.entity      :as ent]
            [jolt.ffi             :as ffi]))

(defn- p [& xs]
  (apply prn xs)
  (flush))

(defn- tm [tag f]
  (let [t0 (System/nanoTime) r (f)]
    (p tag (int (/ (double (- (System/nanoTime) t0)) 1000000.0)) "ms")
    r))

(defn- lcg-rng []
  (let [s (atom (long 123456789))]
    (fn []
      (let [n (long (mod (+ (* @s 1103515245) 12345) 2147483648))]
        (reset! s n)
        (/ (double n) 2147483648.0)))))

(defn- resample-to-64 [w h px]
  (let [at (fn [x y]
             (let [sx (int (* (/ x 64.0) w))
                   sy (int (* (/ y 64.0) h))
                   so (* (+ (* sy w) sx) 4)]
               [(aget px so) (aget px (+ so 1)) (aget px (+ so 2)) (aget px (+ so 3))]))]
    (vec (for [y (range 64) x (range 64) c (at x y)] c))))

(defn- level-geo [blocks]
  (loop [in blocks data [] count 0]
    (if (empty? in)
      {:data data :count count}
      (let [b (first in)
            {:keys [min size]} (lvl/world-box b)
            [sx sy sz] size
            tile (max 1.0 (/ (max sx (max sy sz)) 32.0))
            box (tex/box min size tile (or (:tex b) 0))]
        (recur (rest in) (into data (:data box)) (+ count (:count box)))))))

(defn- render-player-spawn []
  (let [m1 (first (lvl/decode-container lvl-data/bytes))
        spawn (first (filter #(= 0 (:type %)) (:entities m1)))]
    (if spawn
      [(* (:x spawn) 32) (+ (* (:y spawn) 16) 48.0) (* (:z spawn) 32)]
      [256.0 64.0 256.0])))

(defn -main [& _]
  (prn "=== init!/load compute timing ===") (flush)
  (let [m1     (tm :decode-map      #(first (lvl/decode-container lvl-data/bytes)))
        _      (p :blocks (count (:blocks m1)) :entities (count (:entities m1)))
        cells  (tm :solid-cells      #(lvl/solid-cells (:blocks m1)))
        _      (p :cells (count cells))
        _      (tm :player-spawn     #(render-player-spawn))
        _      (tm :spawn-grunts     #(ent/spawn-from-entities (:entities m1)))
        ts     (tm :tex-decode-all   #(ttt/decode-all textures/data (lcg-rng)))
        _      (p :tex-count (count ts))
        ;; per-texture individual decode (draw-prev disabled via [] defs) to localize hogs
        _      (do (prn "--- per-texture (no draw-prev) ---") (flush)
                   (doseq [i (range (count textures/data))]
                     (let [t0 (System/nanoTime)]
                       (ttt/decode-texture (nth textures/data i) [] (lcg-rng))
                       (when (zero? (mod i 1))
                         (prn :tex i (int (/ (double (- (System/nanoTime) t0)) 1000000.0)) "ms")
                         (flush)))))
        layers (tm :resample-all     #(mapv (fn [t] (resample-to-64 (:w t) (:h t) (:pixels t))) ts))
        blob   (tm :blob-concat      #(reduce into [] layers))
        _      (tm :byte-array+write #(let [ptr (ffi/alloc (count blob))]
                                        (ffi/write-array ptr (byte-array (map int blob)))
                                        (ffi/free ptr)))
        geo    (tm :level-geometry   #(level-geo (:blocks m1)))
        _      (p :level-verts (:count geo))
        mdl    (tm :enemy-init-model #(model/init-model unit/bytes 2.5 2.2 2.5))
        _      (p :enemy-verts (:num-verts mdl))]
    (prn "=== done ===") (flush)))
