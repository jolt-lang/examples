(ns rt.render
  (:require [rt.vec :as v]
            [rt.types :as t]
            [rt.scene :as s]))

;; Camera + render loop + bench harness. Calls rt.scene/ray-cast with a Ray it
;; constructs here — the cross-namespace call site whose argument type only
;; reaches ray-cast's `r` param under whole-program inference.

(defn print+space [data]
  #_(print data)
  #_(print " "))

(defn clamp [n min max]
  (if (< n min)
    min
    (if (< max n)
      max
      n)))
(def pi 3.1415926535897932385)
(defn degrees->radians [deg]
  (/ (* deg pi) 180.0))
(defn tan [n] (clojure.math/tan n))

(defn vec3-print [vv samples-per-pixel]
  (let [scale (/ 1.0 samples-per-pixel)
        r (v/sqrt (* scale (:r vv)))
        g (v/sqrt (* scale (:g vv)))
        b (v/sqrt (* scale (:b vv)))]
    (print+space (int (* 256.0 (clamp r 0.0 0.999))))
    (print+space (int (* 256.0 (clamp g 0.0 0.999))))
    (print+space (int (* 256.0 (clamp b 0.0 0.999))))))

(defn ray []
  (let [aspect-ratio (/ 16.0 9.0)
        image-width 100
        image-height (int (/ image-width aspect-ratio))
        samples-per-pixel 2
        max-ray-bounces 10

        look-from (v/vec3-create 13 2 3)
        look-at (v/vec3-create 0 0 0)
        focus-distance 10
        camera-up (v/vec3-create 0 1 0)
        field-of-view 20
        field-of-view-theta (degrees->radians field-of-view)
        viewport-height (* 2 (tan (/ field-of-view-theta 2.0)))
        viewport-width (* aspect-ratio viewport-height)
        camera-w (v/vec3-normalize (v/vec3-sub look-from look-at))
        camera-u (v/vec3-normalize (v/vec3-cross camera-up camera-w))
        camera-v (v/vec3-cross camera-w camera-u)

        origin look-from
        horizontal (v/vec3-scale camera-u (* viewport-width focus-distance))
        vertical (v/vec3-scale camera-v (* viewport-height focus-distance))
        lower-left-corner (v/vec3-sub (v/vec3-sub (v/vec3-sub origin (v/vec3-div horizontal 2))
                                                  (v/vec3-div vertical 2))
                                      (v/vec3-scale camera-w focus-distance))

        hittables (s/rand-scene!)
        y-counter (reverse (range 0 image-height))
        x-counter (range 0 image-width)
        sample-counter (range 0 samples-per-pixel)]

    (doseq [y y-counter]
      (doseq [x x-counter]
        (let [sample (reduce (fn [acc _sample-count]
                               (let [u (/ (+ x (rand)) (- image-width 1))
                                     vv (/ (+ y (rand)) (- image-height 1))
                                     offset (v/vec3-create 0 0 0)
                                     rr (t/ray-create (v/vec3-add origin offset)
                                                      (v/vec3-sub (v/vec3-add (v/vec3-add lower-left-corner
                                                                                          (v/vec3-scale horizontal u))
                                                                              (v/vec3-scale vertical vv))
                                                                  (v/vec3-sub origin offset)))]
                                 (v/vec3-add acc (s/ray-cast rr max-ray-bounces hittables))))
                             (v/vec3-create 0 0 0)
                             sample-counter)]
          (vec3-print sample samples-per-pixel))))))

(defn bench [n]
  (dotimes [_ 2] (ray)) ; warmup
  (let [times (mapv (fn [_]
                      (let [t0 (System/nanoTime)]
                        (ray)
                        (/ (- (System/nanoTime) t0) 1000000.0)))
                    (range n))
        mean (/ (reduce + times) n)]
    (println "runs:" (mapv (fn [tt] (/ (Math/round (* tt 10.0)) 10.0)) times))
    (println "mean:" (/ (Math/round (* mean 10.0)) 10.0) "ms")))

(defn -main [& args]
  (bench (if (seq args) (Integer/parseInt (first args)) 3)))
