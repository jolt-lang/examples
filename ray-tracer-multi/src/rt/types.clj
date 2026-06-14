(ns rt.types
  (:require [rt.vec :refer [Vec3]]))

;; The scene's data records, split out from the vector math. Every Vec3-typed
;; field carries a ^Vec3 hint that resolves ACROSS the namespace boundary (Vec3
;; lives in rt.vec, referred here) — that's what keeps a vec read back out of a
;; ray/hit/result typed instead of :any. ScatterResult's ^Ray hint is same-ns,
;; so this namespace exercises both the cross-ns and the local hint path.

(defrecord Ray [^Vec3 origin ^Vec3 direction])
(defn ray-create [origin direction]
  (->Ray origin direction))

(defrecord ScatterResult [^Vec3 attenuation ^Ray ray])

(defrecord HitInfo [^Vec3 point ^Vec3 normal t material front-face?])
(defn hit-info-create [point normal t material front-face?]
  (->HitInfo point normal t material front-face?))

(defrecord Sphere [^Vec3 center radius material])

(defrecord HitAcc [closest-so-far hit-info])
