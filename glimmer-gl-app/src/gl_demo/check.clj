(ns gl-demo.check
  "Headless verification (no GL context, no display) of the first-person game's
  pure logic: look/move math, AABB collision + sliding, key normalization, the
  button→gate puzzle, and that the level's reactive scene compiles to a render
  plan with a camera, a light, and materials that all resolve. Real GL runs only
  at runtime via core.clj; this catches data/shape/logic bugs."
  (:require [clojure.string    :as str]
            [glimmer-gl.app    :as glapp]
            [glimmer-gl.scene  :as gscene]
            [glimmer-gl.mesh   :as mesh]
            [glimmer-gl.matrix :as m]
            [gl-demo.player    :as player]
            [gl-demo.level     :as level]
            [glimmer-gl.gtk]))                         ; side-effect: widget registry

(defn- approx= [a b] (< (Math/abs (- a b)) 1e-9))

(defn- approx-v [a b]
  (and (= (count a) (count b))
       (every? (fn [[x y]] (approx= x y)) (map vector a b))))

(defn -main [& _]
  (require 'gl-demo.core)   ; compile-check the runtime module without the GUI

  ;; --- look / move math ------------------------------------------------------
  (let [fwd (player/forward-horiz 0.0)
        rgt (player/right-horiz   0.0)
        dot (+ (* (fwd 0) (rgt 0)) (* (fwd 1) (rgt 1)) (* (fwd 2) (rgt 2)))]
    (println "forward @yaw0:" fwd " right @yaw0:" rgt " dot:" dot)
    (assert (approx-v fwd [1.0 0.0 0.0]) "yaw=0 should face +X")
    (assert (approx= dot 0.0) "forward and right must be orthogonal"))

  (let [two-pi (* 2.0 Math/PI)]
    (assert (approx= (player/wrap-yaw 0.0) 0.0) "yaw 0 stays 0")
    (assert (approx= (player/wrap-yaw two-pi) 0.0) "yaw 2pi wraps to 0")
    (assert (and (>= (player/wrap-yaw -0.05) 0.0)
                 (<  (player/wrap-yaw -0.05) two-pi))
            "negative yaw wraps into [0, 2pi)")
    (println "wrap-yaw -0.05 ->" (player/wrap-yaw -0.05)
             "  wrap-yaw 7.0 ->" (player/wrap-yaw 7.0)))

  (let [w1 (player/wish-vec #{:up} 0.0)
        w2 (player/wish-vec #{:up :right} 0.0)]
    (println "wish up:" w1 "  wish up+right (normalized):" w2)
    (assert (approx-v w1 [1.0 0.0]) "holding only :up moves +X")
    (assert (approx= 1.0 (Math/sqrt (+ (* (w2 0) (w2 0)) (* (w2 1) (w2 1)))))
            "diagonal wish must be unit length"))

  ;; --- collision + sliding ---------------------------------------------------
  (let [wall [0.0 1.0 0.0 1.0]]
    (assert (player/collides? 0.5 0.5 0.4 [wall]) "player centred in a 1x1 box collides")
    (assert (not (player/collides? 2.0 2.0 0.4 [wall])) "player far away does not collide")
    ;; player east of the wall moves west into it: X is blocked, Z still slides
    (let [[nx nz] (player/resolve-move 1.5 0.5 -1.0 2.0 0.4 [wall])]
      (println "slide into wall X: moved to" [nx nz])
      (assert (approx= nx 1.5) "X move into wall is blocked")
      (assert (approx= nz 2.5) "Z axis slides past the wall")))

  ;; --- keyboard normalization ------------------------------------------------
  (assert (= :up    (glapp/keyval->kw 0xff52)) "arrow up -> :up")
  (assert (= :w     (glapp/keyval->kw 0x77))  "w -> :w")
  (assert (= :right (glapp/keyval->kw 0xff53)) "arrow right -> :right")
  (assert (nil?     (glapp/keyval->kw 0xffffff)) "unknown key -> nil")
  (let [p (player/make-player [0.0 player/eye-height 0.0])]
    (player/on-key p :w true)                       ; WASD 'w' maps to :up
    (assert (contains? @(:keys p) :up) "pressing :w holds :up")
    (player/on-key p :w false)
    (assert (not (contains? @(:keys p) :up)) "releasing :w clears :up"))
  (println "key normalization: ok")

  ;; --- simulation: movement + the button→gate puzzle -------------------------
  (let [p (player/make-player level/spawn)]
    ;; holding forward walks +X each tick
    (player/on-key p :up true)
    (let [x0 (first @(:pos p))]
      (player/tick! p 0.1 level/ctx)
      (assert (> (first @(:pos p)) x0) "holding :up advances the player along +X"))
    ;; standing on the button (edge-triggered) opens the gate
    (reset! (:pos p) [10.0 player/eye-height 2.6])
    (player/tick! p 0.016 level/ctx)
    (println "after stepping on button: gate-open =" @(:gate-open p)
             " target =" @(:gate-target p))
    (assert (true? @(:gate-open p)) "button press should open the gate")
    (assert (approx= @(:gate-target p) (* 0.5 Math/PI)) "open target is +pi/2"))

  ;; --- the level compiles to a render plan -----------------------------------
  (let [p        (player/make-player level/spawn)
        scene-fn (level/scene-root p)
        plan     (gscene/flatten (gscene/expand (scene-fn)))
        items    (:items plan)
        unique   (into #{} (map :geom items))
        tris     (reduce + (map (fn [g] (count (mesh/triangles g))) unique))]
    (println "level scene:")
    (println (format "  items=%d  unique meshes=%d  triangles=%d  lights=%d  camera=%s"
                     (count items) (count unique) tris (count (:lights plan))
                     (some? (:camera plan))))
    (assert (pos? (count items)) "level produced no render items")
    (assert (:camera plan)       "level has no camera")
    (assert (= 1 (count (:lights plan))) "level should have exactly one light")
    (let [used (into #{} (map :material items))]
      (assert (every? #(contains? level/materials %) used)
              (str "unresolved materials: " used))
      (println "  materials:" (str/join " " (sort used)))))

  (println "check: ok"))
