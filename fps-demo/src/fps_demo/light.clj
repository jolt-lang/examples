(ns fps-demo.light
  "Pure port of q1k3's dynamic point-light buffer (renderer.js r_push_light and
  the fragment light loop). GL-free and tested headlessly.

  q1k3 lights the world with a per-fragment array of point lights that entities
  push each frame: muzzle flashes, projectile glow, explosions, torches. Each is
  {:pos [x y z] :intensity i :color [r g b]} (rgb 0-255). `pack-lights` fades each
  by distance to the camera (full within 768u, gone by 1024u), pre-multiplies the
  colour by fade*intensity*10, and writes up to `max-lights` of them into a flat
  float array of two vec3 per light — [px py pz  r' g' b'] — exactly the layout
  the shader's u_lights[] loop samples (l[i] = position, l[i+1] = colour)."
  (:refer-clojure :exclude [max-lights]))

(def ^:const max-lights 16)          ; array is 2 vec3 per light => u_lights[32]
(def ^:const fade-near  768.0)
(def ^:const fade-far   1024.0)
(def ^:const boost      10.0)        ; q1k3 r_push_light intensity multiplier

(defn- clamp01 [v] (cond (< v 0.0) 0.0 (> v 1.0) 1.0 :else v))

(defn- dist3 [a b]
  (let [dx (- (double (nth a 0)) (double (nth b 0)))
        dy (- (double (nth a 1)) (double (nth b 1)))
        dz (- (double (nth a 2)) (double (nth b 2)))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn fade
  "q1k3 r_push_light fade: clamp(scale(dist,768,1024,1,0),0,1) * intensity * 10.
  Lights fade out as the camera recedes and vanish beyond 1024 units."
  [dist intensity]
  (* (clamp01 (/ (- fade-far dist) (- fade-far fade-near))) (double intensity) boost))

(defn pack-lights
  "Pack `lights` into a float-array of (* max-lights 6) floats (two vec3 per
  light), camera-faded, first-come up to max-lights. Zero-fade lights are
  skipped. Returns [array packed-count]: the shader loops only over `packed-count`
  lights (a dynamic bound), so fragments don't pay for empty slots. `cam` is the
  camera world position [x y z]."
  [lights cam]
  (let [out (float-array (* max-lights 6))]
    (loop [ls (seq lights) slot 0]
      (if (or (nil? ls) (>= slot max-lights))
        [out slot]
        (let [l (first ls)
              f (fade (dist3 (:pos l) cam) (:intensity l))]
          (if (<= f 0.0)
            (recur (next ls) slot)
            (let [pos (:pos l) col (:color l) base (* slot 6)]
              (aset out base       (float (nth pos 0)))
              (aset out (+ base 1) (float (nth pos 1)))
              (aset out (+ base 2) (float (nth pos 2)))
              (aset out (+ base 3) (float (* (double (nth col 0)) f)))
              (aset out (+ base 4) (float (* (double (nth col 1)) f)))
              (aset out (+ base 5) (float (* (double (nth col 2)) f)))
              (recur (next ls) (inc slot)))))))))
