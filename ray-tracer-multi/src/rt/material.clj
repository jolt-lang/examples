(ns rt.material
  (:require [rt.vec :as v :refer [Vec3]]
            [rt.types :as t :refer [Ray HitInfo]]))

;; Materials behind a Scatter protocol, in their own namespace. Each impl's
;; receiver is statically its record type, so (:albedo m) reads at Vec3 with no
;; call-site inference; the ^Vec3 albedo hints resolve cross-ns to rt.vec. The
;; scatter results are rt.types/ScatterResult values, built cross-ns.

(defn pow [l r] (clojure.math/pow l r))

(defn reflectance [cosine ref-idx]
  (let [r (/ (- 1.0 ref-idx)
             (+ 1.0 ref-idx))
        r2 (* r r)]
    (* (+ r2 (- 1.0 r2))
       (pow (- 1.0 cosine) 5.0))))

(defprotocol Scatter
  (scatter [m ^Ray ray ^HitInfo hit]))

(defrecord Lambertian [^Vec3 albedo]
  Scatter
  (scatter [m ^Ray ray ^HitInfo hit]
    (let [scatter-direction (let [dir (v/vec3-add (:normal hit)
                                                  (v/vec3-rand-unit-in-sphere))]
                              (if (v/vec3-near-zero? dir)
                                (:normal hit)
                                dir))
          scattered (t/ray-create (:point hit) scatter-direction)]
      (t/->ScatterResult (:albedo m) scattered))))

(defrecord Metal [^Vec3 albedo fuzz]
  Scatter
  (scatter [m ^Ray ray ^HitInfo hit]
    (let [reflected (v/vec3-reflect (v/vec3-normalize (:direction ray))
                                    (:normal hit))
          scattered (t/ray-create (:point hit)
                                  (v/vec3-add reflected
                                              (v/vec3-scale (v/vec3-rand-unit-in-sphere)
                                                            (:fuzz m))))]
      (if (< 0 (v/vec3-dot (:direction scattered) (:normal hit)))
        (t/->ScatterResult (:albedo m) scattered)
        nil))))

(defrecord Dielectric [index-of-refraction]
  Scatter
  (scatter [m ^Ray ray ^HitInfo hit]
    (let [attenuation (v/vec3-create 1 1 1)
          ir (:index-of-refraction m)
          refraction-ratio (if (:front-face? hit)
                             (/ 1.0 ir)
                             ir)
          unit-direction (v/vec3-normalize (:direction ray))
          normal (:normal hit)
          cos-theta (min (v/vec3-dot (v/vec3-sub (v/vec3-create 0 0 0)
                                                 unit-direction)
                                     normal)
                         1.0)
          sin-theta (v/sqrt (- 1.0 (* cos-theta cos-theta)))
          cannot-refract? (< 1.0 (* refraction-ratio sin-theta))
          direction (if (or cannot-refract?
                            (< (rand) (reflectance cos-theta refraction-ratio)))
                      (v/vec3-reflect unit-direction normal)
                      (v/vec3-refract unit-direction normal refraction-ratio))]
      (t/->ScatterResult attenuation (t/ray-create (:point hit) direction)))))
