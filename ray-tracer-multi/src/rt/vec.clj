(ns rt.vec)

;; Foundation namespace: the Vec3 record and all vector arithmetic, plus the
;; colour helpers. Everything downstream (rays, hits, materials, scene, render)
;; depends on this. Vec3 itself has no record-typed fields, so no cross-ns hints
;; originate here — but every OTHER namespace hints its fields ^Vec3, which is
;; the cross-namespace-hint path this demo exercises.

(defn rand-real [min max]
  (+ min (* (- max min) (rand))))
(defn sqrt [n] (clojure.math/sqrt n))

(defrecord Vec3 [r g b])
(defn vec3-create [r g b] (->Vec3 r g b))
(defn vec3-scale [l n]
  (->Vec3 (* (:r l) n) (* (:g l) n) (* (:b l) n)))
(defn vec3-add [l r]
  (->Vec3 (+ (:r l) (:r r)) (+ (:g l) (:g r)) (+ (:b l) (:b r))))
(defn vec3-sub [l r]
  (->Vec3 (- (:r l) (:r r)) (- (:g l) (:g r)) (- (:b l) (:b r))))
(defn vec3-mul [l r]
  (->Vec3 (* (:r l) (:r r)) (* (:g l) (:g r)) (* (:b l) (:b r))))
(defn vec3-div [l n]
  (->Vec3 (/ (:r l) n) (/ (:g l) n) (/ (:b l) n)))
(defn vec3-length-squared [v]
  (+ (+ (* (:r v) (:r v))
        (* (:g v) (:g v)))
     (* (:b v) (:b v))))
(defn vec3-length [v]
  (sqrt (vec3-length-squared v)))
(defn vec3-dot [l r]
  (+ (+ (* (:r l) (:r r))
        (* (:g l) (:g r)))
     (* (:b l) (:b r))))
(defn vec3-cross [l r]
  (vec3-create (- (* (:g l) (:b r))
                  (* (:b l) (:g r)))
               (- (* (:b l) (:r r))
                  (* (:r l) (:b r)))
               (- (* (:r l) (:g r))
                  (* (:g l) (:r r)))))
(defn vec3-normalize [v]
  (vec3-div v (vec3-length v)))
(defn vec3-rand []
  (vec3-create (rand) (rand) (rand)))
(defn vec3-rand+clamp [min max]
  (vec3-create (rand-real min max) (rand-real min max) (rand-real min max)))
(defn vec3-rand-in-sphere []
  (let [v (vec3-rand+clamp -1 1)]
    (if (< 1.0 (vec3-length-squared v))
      v
      (vec3-rand-in-sphere))))
(defn vec3-rand-unit-in-sphere []
  (vec3-normalize (vec3-rand-in-sphere)))
(defn vec3-rand-in-unit-disk []
  (let [p (vec3-create (rand-real -1 1) (rand-real -1 1) 0)]
    (if (< 1 (vec3-length-squared p))
      (vec3-rand-in-unit-disk)
      p)))
(defn vec3-near-zero? [v]
  (let [epsilon 0.0000008]
    (and (and (< (abs (:r v)) epsilon)
              (< (abs (:g v)) epsilon))
         (< (abs (:b v)) epsilon))))
(defn vec3-reflect [v n]
  (vec3-sub v (vec3-scale n (* 2 (vec3-dot v n)))))
(defn vec3-refract [uv n etai-over-etat]
  (let [cos-theta (min (vec3-dot (vec3-sub (vec3-create 0 0 0)
                                           uv)
                                 n)
                       1.0)
        r-out-perp (vec3-scale (vec3-add uv (vec3-scale n cos-theta))
                               etai-over-etat)
        r-out-parallel (vec3-scale n (- 0.0 (sqrt (abs (- 1.0 (vec3-length-squared r-out-perp))))))]
    (vec3-add r-out-perp r-out-parallel)))

(defn color [r g b]
  (vec3-create (/ r 255.0) (/ g 255.0) (/ b 255.0)))
(def colors [(color 246 81 29)
             (color 251 131 15)
             (color 255 180 0)
             (color 44 84 139)
             (color 9 97 141)
             (color 0 165 207)
             (color 79 71 137)
             (color 95 88 148)])
(defn rand-color []
  (rand-nth colors))
