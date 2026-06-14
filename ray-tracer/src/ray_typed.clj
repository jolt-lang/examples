(ns ray-typed)

;; Fully-typed variant of the ray tracer (jolt-3ko / records pivot). Every data
;; structure is a defrecord with a fixed, declared shape — vectors, rays, hits,
;; spheres, the scatter result, and the three materials. Materials sit behind a
;; Scatter protocol instead of a map carrying a `:scatter` closure, so dispatch
;; is a real protocol call (devirtualizable on a proven receiver) and the hot
;; vec math inside each scatter impl reads its fields at the method's own
;; statically-known type. Field hints (^Vec3 / ^Ray) carry the exact nested
;; shape across fn boundaries so a vec read off a ray/hit/result stays Vec3.
;; Compare against ray-baseline (the all-maps original) under JOLT_DIRECT_LINK=1
;; and JOLT_WHOLE_PROGRAM=1.

(defn print+space [data]
  #_(print data)
  #_(print " "))

(defn rand-real [min max]
  (+ min (* (- max min) (rand))))
(defn clamp [n min max]
  (if (< n min)
    min
    (if (< max n)
      max
      n)))
(def pi 3.1415926535897932385)
(defn degrees->radians [deg]
  (/ (* deg pi) 180.0))

(defn sqrt [n] (clojure.math/sqrt n))
(defn tan [n] (clojure.math/tan n))
(defn pow [l r] (clojure.math/pow l r))

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
(defn vec3-print [v samples-per-pixel]
  (let [scale (/ 1.0 samples-per-pixel)
        r (sqrt (* scale (:r v)))
        g (sqrt (* scale (:g v)))
        b (sqrt (* scale (:b v)))]
    (print+space (int (* 256.0 (clamp r 0.0 0.999))))
    (print+space (int (* 256.0 (clamp g 0.0 0.999))))
    (print+space (int (* 256.0 (clamp b 0.0 0.999))))))

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

(defrecord Ray [^Vec3 origin ^Vec3 direction])
(defn ray-create [origin direction]
  (->Ray origin direction))
(defn ray-at [r t]
  (vec3-add (:origin r) (vec3-scale (:direction r) t)))

;; A scatter produces a tinted bounce ray. Both fields carry their record type
;; so ray-cast reads (:attenuation s)/(:ray s) back at Vec3/Ray, not :any.
(defrecord ScatterResult [^Vec3 attenuation ^Ray ray])

(defn reflectance [cosine ref-idx]
  (let [r (/ (- 1.0 ref-idx)
             (+ 1.0 ref-idx))
        r2 (* r r)]
    (* (+ r2 (- 1.0 r2))
       (pow (- 1.0 cosine) 5.0))))

(defrecord HitInfo [^Vec3 point ^Vec3 normal t material front-face?])
(defn hit-info-create [point normal t material front-face?]
  (->HitInfo point normal t material front-face?))

;; Materials behind a protocol: each impl's receiver is statically its own
;; record, so (:albedo m) etc. read at Vec3 with no call-site inference. The
;; dispatch (scatter material ...) devirtualizes wherever the material type is
;; proven, and falls back to one indirect dispatch where it isn't (the material
;; read off a sphere pulled from the reduce-iterated world list).
(defprotocol Scatter
  (scatter [m ray hit]))

(defrecord Lambertian [^Vec3 albedo]
  Scatter
  (scatter [m ray hit]
    (let [scatter-direction (let [dir (vec3-add (:normal hit)
                                                (vec3-rand-unit-in-sphere))]
                              (if (vec3-near-zero? dir)
                                (:normal hit)
                                dir))
          scattered (ray-create (:point hit) scatter-direction)]
      (->ScatterResult (:albedo m) scattered))))

(defrecord Metal [^Vec3 albedo fuzz]
  Scatter
  (scatter [m ray hit]
    (let [reflected (vec3-reflect (vec3-normalize (:direction ray))
                                  (:normal hit))
          scattered (ray-create (:point hit)
                                (vec3-add reflected
                                          (vec3-scale (vec3-rand-unit-in-sphere)
                                                      (:fuzz m))))]
      (if (< 0 (vec3-dot (:direction scattered) (:normal hit)))
        (->ScatterResult (:albedo m) scattered)
        nil))))

(defrecord Dielectric [index-of-refraction]
  Scatter
  (scatter [m ray hit]
    (let [attenuation (vec3-create 1 1 1)
          ir (:index-of-refraction m)
          refraction-ratio (if (:front-face? hit)
                             (/ 1.0 ir)
                             ir)
          unit-direction (vec3-normalize (:direction ray))
          normal (:normal hit)
          cos-theta (min (vec3-dot (vec3-sub (vec3-create 0 0 0)
                                             unit-direction)
                                   normal)
                         1.0)
          sin-theta (sqrt (- 1.0 (* cos-theta cos-theta)))
          cannot-refract? (< 1.0 (* refraction-ratio sin-theta))
          direction (if (or cannot-refract?
                            (< (rand) (reflectance cos-theta refraction-ratio)))
                      (vec3-reflect unit-direction normal)
                      (vec3-refract unit-direction normal refraction-ratio))]
      (->ScatterResult attenuation (ray-create (:point hit) direction)))))

(defrecord Sphere [^Vec3 center radius material])

(defn hit-sphere [hittable t-min t-max ray]
  (let [center (:center hittable)
        radius (:radius hittable)
        oc (vec3-sub (:origin ray) center)
        a (vec3-length-squared (:direction ray))
        half-b (vec3-dot oc (:direction ray))
        c (- (vec3-length-squared oc) (* radius radius))
        discriminant (- (* half-b half-b) (* a c))]
    (if (< discriminant 0)
      nil
      (let [sqrt-d (sqrt discriminant)
            root (let [root (/ (- (- 0 half-b) sqrt-d) a)]
                   (if (or (< root t-min) (< t-max root))
                     (/ (+ (- 0 half-b) sqrt-d) a)
                     root))]
        (if (or (< root t-min) (< t-max root))
          nil
          (let [point (ray-at ray root)
                outward-normal (vec3-div (vec3-sub point center) radius)
                front-face? (< (vec3-dot (:direction ray) outward-normal) 0.0)]
            (hit-info-create point
                             (if front-face?
                               outward-normal
                               (vec3-sub (vec3-create 0 0 0) outward-normal))
                             root
                             (:material hittable)
                             front-face?)))))))

;; Per-pixel hit accumulator: closest hit so far + its HitInfo. A record so the
;; reduce reads (:closest-so-far acc) at num and rebuilds via ->HitAcc instead
;; of assoc'ing a map every step.
(defrecord HitAcc [closest-so-far hit-info])

(defn hit-all [t-min t-max ray hittables]
  (:hit-info
       (reduce (fn [acc hittable]
                 (let [hit-info (hit-sphere hittable
                                            t-min
                                            (:closest-so-far acc)
                                            ray)]
                   (if (some? hit-info)
                     (->HitAcc (:t hit-info) hit-info)
                     acc)))
               (->HitAcc t-max nil)
               hittables)))

(defn ray-cast [r max-ray-bounces hittables]
  (if (< max-ray-bounces 0)
    (vec3-create 0 0 0)
    (let [normalize-direction (vec3-normalize (:direction r))
          t (* 0.5 (+ (:g normalize-direction) 1.0))
          hit-info (hit-all 0.001 99999999 r hittables)]
      (if (some? hit-info)
        (let [material (:material hit-info)
              scattered (scatter material r hit-info)]
          (if (some? scattered)
            (vec3-mul (ray-cast (:ray scattered)
                                (dec max-ray-bounces)
                                hittables)
                      (:attenuation scattered))
            (vec3-create 0 0 0)))
        (vec3-add (vec3-scale (vec3-create 1.0 1.0 1.0) (- 1.0 t))
                  (vec3-scale (color 246 81 29) t))))))

(defn rand-scene! []
  (reduce (fn [acc i]
            (let [x (- (mod i 21) 10)
                  z (- (/ i 21) 6)
                  choose-mat (rand)
                  center (vec3-create (+ x (* 0.9 (rand)))
                                      0.2
                                      (+ z (* 0.9 (rand))))]
              (if (< 0.9 (vec3-length (vec3-sub center (vec3-create 4 0.2 0))))
                (conj acc (if (< choose-mat 0.8)
                            (->Sphere center 0.2 (->Lambertian (rand-color)))
                            (if (< choose-mat 0.95)
                              (->Sphere center 0.2 (->Metal (rand-color) (rand-real 0 0.5)))
                              (->Sphere center 0.2 (->Dielectric 1.5)))))
                acc)))
          [(->Sphere (vec3-create 0 -1000 0) 1000 (->Lambertian (color 79 71 137)))
           (->Sphere (vec3-create -4 1 0) 1 (->Lambertian (color 246 81 29)))
           (->Sphere (vec3-create 0 1 0) 1 (->Dielectric 1.5))
           (->Sphere (vec3-create 4 1 0) 1 (->Metal (color 0 165 207) 0))]
          (range 0 200)))

(defn ray []
  (let [aspect-ratio (/ 16.0 9.0)
        image-width 100
        image-height (int (/ image-width aspect-ratio))
        samples-per-pixel 2
        max-ray-bounces 10

        look-from (vec3-create 13 2 3)
        look-at (vec3-create 0 0 0)
        focus-distance 10
        camera-up (vec3-create 0 1 0)
        field-of-view 20
        field-of-view-theta (degrees->radians field-of-view)
        viewport-height (* 2 (tan (/ field-of-view-theta 2.0)))
        viewport-width (* aspect-ratio viewport-height)
        camera-w (vec3-normalize (vec3-sub look-from look-at))
        camera-u (vec3-normalize (vec3-cross camera-up camera-w))
        camera-v (vec3-cross camera-w camera-u)

        origin look-from
        horizontal (vec3-scale camera-u (* viewport-width focus-distance))
        vertical (vec3-scale camera-v (* viewport-height focus-distance))
        lower-left-corner (vec3-sub (vec3-sub (vec3-sub origin (vec3-div horizontal 2))
                                              (vec3-div vertical 2))
                                    (vec3-scale camera-w focus-distance))

        hittables (rand-scene!)
        y-counter (reverse (range 0 image-height))
        x-counter (range 0 image-width)
        sample-counter (range 0 samples-per-pixel)]

    ;(println "P3")
    ;(print+space image-width) (println image-height)
    ;(println 255)
    (doseq [y y-counter]
      (doseq [x x-counter]
        (let [sample (reduce (fn [acc _sample-count]
                               (let [u (/ (+ x (rand)) (- image-width 1))
                                     v (/ (+ y (rand)) (- image-height 1))
                                     offset (vec3-create 0 0 0)
                                     ray (ray-create (vec3-add origin offset)
                                                     (vec3-sub (vec3-add (vec3-add lower-left-corner
                                                                                   (vec3-scale horizontal u))
                                                                         (vec3-scale vertical v))
                                                               (vec3-sub origin offset)))]
                                 (vec3-add acc (ray-cast ray max-ray-bounces hittables))))
                             (vec3-create 0 0 0)
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
    (println "runs:" (mapv (fn [t] (/ (Math/round (* t 10.0)) 10.0)) times))
    (println "mean:" (/ (Math/round (* mean 10.0)) 10.0) "ms")))

(defn -main [& args]
  (bench (if (seq args) (Integer/parseInt (first args)) 3)))
