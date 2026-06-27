(ns gl-demo.player
  "First-person controller + level interaction logic.

  The scene-driving state lives in reactive atoms (`r/atom`) produced by
  `make-player`; mutating them recomputes the scene plan. All of the *pure*
  math (look vectors, wish vector, AABB collision, movement resolution) is
  split into plain functions below so it can be exercised headlessly by
  `gl-demo.check` without a GL surface.

  Convention: +X is 'east', +Y is up, +Z is 'south'. yaw=0 faces +X. The level
  is laid out along +X: entry -> corridor -> rotunda -> exit."
  (:require [glimmer.ratom :as r]))

;; --- tunables ----------------------------------------------------------------
(def ^:const eye-height 1.6)     ; player eye height (flat floor, no gravity)
(def ^:const radius     0.40)    ; player collision half-extent (XZ box)
(def ^:const move-speed 3.20)    ; world units / second
(def ^:const look-sens  2.20)    ; radians per unit of normalized mouse delta
(def ^:const max-pitch  1.35)    ; clamp pitch so you can't flip over
(def ^:const gate-rate  6.0)     ; gate swing speed (rad / second)

(defn make-player
  "Build a fresh player state map of reactive atoms. `spawn` is [x y z]."
  [spawn]
  {:pos        (r/atom spawn)
   :yaw        (r/atom 0.0)      ; 0 -> facing +X
   :pitch      (r/atom 0.0)
   :keys       (r/atom #{})      ; held movement keywords (:up/:down/:left/:right)
   :gate-open  (r/atom false)    ; discrete puzzle state (drives collision)
   :gate-angle (r/atom 0.0)      ; rendered angle, lerped toward target
   :gate-target (r/atom 0.0)
   :won        (r/atom false)
   ;; interaction bookkeeping (plain atoms are fine — not scene-driving)
   :on-button? (atom false)      ; was player overlapping the button last frame
   :looking    (atom false)      ; mouse button held (drag-to-look engaged)
   :drag-init? (atom false)      ; first motion event after a grab (no delta)
   :last-nx    (atom 0.0)
   :last-ny    (atom 0.0)})

;; --- pure look/move math -----------------------------------------------------

(defn forward-vert
  "Full 3D look direction for the camera (unit when yaw,pitch are real)."
  [yaw pitch]
  (let [cy (Math/cos yaw)  sy (Math/sin yaw)
        cp (Math/cos pitch) sp (Math/sin pitch)]
    [(* cy cp) sp (* sy cp)]))

(defn forward-horiz
  "Horizontal movement forward for a heading (unit length)."
  [yaw]
  (let [cy (Math/cos yaw) sy (Math/sin yaw)] [cy 0.0 sy]))

(defn right-horiz
  "Strafe direction = forward_h x up (unit length)."
  [yaw]
  (let [sy (Math/sin yaw) cy (Math/cos yaw)] [(- sy) 0.0 cy]))

(defn wrap-yaw
  "Wrap a yaw value into [0, 2π). jolt's `mod` is integer-only, so use the
  float identity  y - b·floor(y/b), which is correct for negative y too."
  [y]
  (let [two-pi (* 2.0 Math/PI)]
    (- y (* two-pi (Math/floor (/ y two-pi))))))

(defn wish-vec
  "Unit desired-move direction in the XZ plane from a set of held movement
  keywords, or [0 0] when nothing is held. Diagonals are normalized."
  [held yaw]
  (let [[fx _ fz] (forward-horiz yaw)
        [rx _ rz] (right-horiz yaw)
        ax (+ (if (held :up)    fx 0.0)
              (if (held :down)  (- fx) 0.0)
              (if (held :right) rx 0.0)
              (if (held :left)  (- rx) 0.0))
        az (+ (if (held :up)    fz 0.0)
              (if (held :down)  (- fz) 0.0)
              (if (held :right) rz 0.0)
              (if (held :left)  (- rz) 0.0))
        mag (Math/sqrt (+ (* ax ax) (* az az)))]
    (if (zero? mag) [0.0 0.0] [(/ ax mag) (/ az mag)])))

;; --- pure collision (axis-aligned boxes in XZ) ------------------------------

(defn overlaps?
  "Does a player box of half-extent r centred at (x,z) intersect AABB?"
  [x z r aabb]
  (let [[xmin xmax zmin zmax] aabb]
    (and (> (+ x r) xmin) (< (- x r) xmax)
         (> (+ z r) zmin) (< (- z r) zmax))))

(defn collides?
  "True if the player box at (x,z) intersects any AABB in `walls`."
  [x z r walls]
  (boolean (some #(overlaps? x z r %) walls)))

(defn point-in-aabb?
  "Is the point (x,z) inside AABB (inclusive)? Used for trigger volumes."
  [x z aabb]
  (let [[xmin xmax zmin zmax] aabb]
    (and (>= x xmin) (<= x xmax) (>= z zmin) (<= z zmax))))

(defn resolve-move
  "Move from (px,pz) by (dx,dz) against `walls`, sliding along walls. Each axis
  is resolved independently against the already-resolved other axis, which gives
  smooth wall-sliding for grid levels and never sticks on corners."
  [px pz dx dz r walls]
  (let [nx (if (collides? (+ px dx) pz r walls) px (+ px dx))
        nz (if (collides? nx (+ pz dz) r walls) pz (+ pz dz))]
    [nx nz]))

;; --- input handlers (mutate player cells) ------------------------------------

(def ^:private move-kw
  "Map every movement key to its canonical direction. WASD and arrows collapse
  to the same four keywords so the held-set stays small."
  {:up :up :down :down :left :left :right :right
   :w :up :s :down :a :left :d :right})

(defn on-key
  "Keyboard handler for `:on-key`. Maintains the held-movement set."
  [p kw pressed?]
  (when-let [mk (move-kw kw)]
    (if pressed?
      (swap! (:keys p) conj mk)
      (swap! (:keys p) disj mk))))

(defn on-button
  "Mouse-button handler for `:on-button`. Engages drag-to-look on press."
  [p pressed?]
  (reset! (:looking p) pressed?)
  (when pressed? (reset! (:drag-init? p) false)))

(defn on-motion
  "Normalized-pointer handler for `:on-motion`. While a button is held, mouse
  deltas drive yaw/pitch (drag-to-look)."
  [p nx ny]
  (if-not @(:looking p)
    (do (reset! (:last-nx p) nx) (reset! (:last-ny p) ny))
    (if-not @(:drag-init? p)
      (do (reset! (:last-nx p) nx) (reset! (:last-ny p) ny)
          (reset! (:drag-init? p) true))
      (let [dx (- nx @(:last-nx p))
            dy (- ny @(:last-ny p))]
         (swap! (:yaw p)
                (fn [y] (wrap-yaw (+ y (* (- dx) look-sens)))))
        (swap! (:pitch p)
               (fn [pp] (max (- max-pitch) (min max-pitch (+ pp (* dy look-sens))))))
        (reset! (:last-nx p) nx) (reset! (:last-ny p) ny)))))

;; --- per-frame simulation ----------------------------------------------------

(defn- lerp-angle
  "Step `cur` toward `target` at `gate-rate` rad/s, returning the new value."
  [cur target dt]
  (let [diff (- target cur)]
    (if (zero? diff) cur
        (let [step (* (if (pos? diff) 1.0 -1.0)
                      (min (Math/abs diff) (* gate-rate dt)))]
          (+ cur step)))))

(defn tick!
  "Advance the simulation by `dt` seconds. `ctx` is the level context map:
    :walls       vector of static AABBs [xmin xmax zmin zmax]
    :gate-aabb   AABB that blocks the exit when the gate is closed
    :button-aabb AABB whose overlap presses the button (edge-triggered)
    :seam        optional {:trigger AABB :dest [x z yaw]} teleport, or nil
    :win-aabb    AABB that wins the game when the gate is open"
  [p dt ctx]
  (when-not @(:won p)
    (let [held    @(:keys p)
          yaw     @(:yaw p)
          [wx wz] (wish-vec held yaw)
          dist    (* move-speed dt)
          [px _ pz] @(:pos p)
          ;; gate blocks the exit only while closed
          ctx-walls (:walls ctx)
          walls   (if @(:gate-open p) ctx-walls (conj ctx-walls (:gate-aabb ctx)))
          [ax az] (resolve-move px pz (* wx dist) (* wz dist) radius walls)
          ;; button press — edge triggered so holding the button presses once
          now-btn (overlaps? ax az radius (:button-aabb ctx))]
      (when (and now-btn (not @(:on-button? p)))
        (swap! (:gate-open p) not)
        (reset! (:gate-target p) (if @(:gate-open p) (* 0.5 Math/PI) 0.0)))
      (reset! (:on-button? p) now-btn)
      ;; non-Euclidean teleport seam (the impossible-geometry moment)
      (let [seam (:seam ctx)]
        (if (and seam (point-in-aabb? ax az (:trigger seam)))
          (let [[tx tz tyaw] (:dest seam)]
            (reset! (:pos p) [tx eye-height tz])
            (reset! (:yaw p) tyaw))
          (when (or (not= ax px) (not= az pz))
            (reset! (:pos p) [ax eye-height az]))))
      ;; animate the gate toward its target angle
      (let [cur @(:gate-angle p)]
        (when-not (= cur @(:gate-target p))
          (reset! (:gate-angle p) (lerp-angle cur @(:gate-target p) dt))))
      ;; win: only once the gate is open and the player reaches the exit
      (when (and @(:gate-open p)
                 (point-in-aabb? (first @(:pos p)) (nth @(:pos p) 2) (:win-aabb ctx)))
        (reset! (:won p) true)))))
