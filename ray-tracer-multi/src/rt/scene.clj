(ns rt.scene
  (:require [rt.vec :as v]
            [rt.types :as t :refer [Ray Sphere HitInfo]]
            [rt.material :as m]))

;; Scene intersection + the recursive ray cast, in their own namespace. ray-cast
;; is recursive (never inlined) and its `r` param is a Ray supplied by the caller
;; in rt.render — a DIFFERENT namespace. Per-namespace inference can't see that
;; caller, so r stays :any and (:direction r) is a generic lookup; whole-program
;; inference sees rt.render's call site and proves r is a Ray. That cross-ns
;; param propagation is what this multi-namespace demo measures.

(defn ray-at [^Ray r tt]
  (v/vec3-add (:origin r) (v/vec3-scale (:direction r) tt)))

(defn hit-sphere [^Sphere hittable t-min t-max ^Ray ray]
  (let [center (:center hittable)
        radius (:radius hittable)
        oc (v/vec3-sub (:origin ray) center)
        a (v/vec3-length-squared (:direction ray))
        half-b (v/vec3-dot oc (:direction ray))
        c (- (v/vec3-length-squared oc) (* radius radius))
        discriminant (- (* half-b half-b) (* a c))]
    (if (< discriminant 0)
      nil
      (let [sqrt-d (v/sqrt discriminant)
            root (let [root (/ (- (- 0 half-b) sqrt-d) a)]
                   (if (or (< root t-min) (< t-max root))
                     (/ (+ (- 0 half-b) sqrt-d) a)
                     root))]
        (if (or (< root t-min) (< t-max root))
          nil
          (let [point (ray-at ray root)
                outward-normal (v/vec3-div (v/vec3-sub point center) radius)
                front-face? (< (v/vec3-dot (:direction ray) outward-normal) 0.0)]
            (t/hit-info-create point
                               (if front-face?
                                 outward-normal
                                 (v/vec3-sub (v/vec3-create 0 0 0) outward-normal))
                               root
                               (:material hittable)
                               front-face?)))))))

(defn hit-all [t-min t-max ray hittables]
  (:hit-info
       (reduce (fn [acc hittable]
                 (let [hit-info (hit-sphere hittable
                                            t-min
                                            (:closest-so-far acc)
                                            ray)]
                   (if (some? hit-info)
                     (t/->HitAcc (:t hit-info) hit-info)
                     acc)))
               (t/->HitAcc t-max nil)
               hittables)))

(defn ray-cast [^Ray r max-ray-bounces hittables]
  (if (< max-ray-bounces 0)
    (v/vec3-create 0 0 0)
    (let [normalize-direction (v/vec3-normalize (:direction r))
          tt (* 0.5 (+ (:g normalize-direction) 1.0))
          hit-info (hit-all 0.001 99999999 r hittables)]
      (if (some? hit-info)
        (let [material (:material hit-info)
              scattered (m/scatter material r hit-info)]
          (if (some? scattered)
            (v/vec3-mul (ray-cast (:ray scattered)
                                  (dec max-ray-bounces)
                                  hittables)
                        (:attenuation scattered))
            (v/vec3-create 0 0 0)))
        (v/vec3-add (v/vec3-scale (v/vec3-create 1.0 1.0 1.0) (- 1.0 tt))
                    (v/vec3-scale (v/color 246 81 29) tt))))))

(defn rand-scene! []
  (reduce (fn [acc i]
            (let [x (- (mod i 21) 10)
                  z (- (/ i 21) 6)
                  choose-mat (rand)
                  center (v/vec3-create (+ x (* 0.9 (rand)))
                                        0.2
                                        (+ z (* 0.9 (rand))))]
              (if (< 0.9 (v/vec3-length (v/vec3-sub center (v/vec3-create 4 0.2 0))))
                (conj acc (if (< choose-mat 0.8)
                            (t/->Sphere center 0.2 (m/->Lambertian (v/rand-color)))
                            (if (< choose-mat 0.95)
                              (t/->Sphere center 0.2 (m/->Metal (v/rand-color) (v/rand-real 0 0.5)))
                              (t/->Sphere center 0.2 (m/->Dielectric 1.5)))))
                acc)))
          [(t/->Sphere (v/vec3-create 0 -1000 0) 1000 (m/->Lambertian (v/color 79 71 137)))
           (t/->Sphere (v/vec3-create -4 1 0) 1 (m/->Lambertian (v/color 246 81 29)))
           (t/->Sphere (v/vec3-create 0 1 0) 1 (m/->Dielectric 1.5))
           (t/->Sphere (v/vec3-create 4 1 0) 1 (m/->Metal (v/color 0 165 207) 0))]
          (range 0 200)))
