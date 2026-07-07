(ns fps-demo.player
  "Player physics + look for the q1k3 port. Pure Clojure translation of
  q1k3/source/{entity.js,entity_player.js} so the integrator and collision can
  be unit-tested headlessly (no GL).

  State is a plain map {:p [x y z] :v [..] :a [..] :f :on-ground ...}; collision
  is the 128^3 voxel set from fps-demo.map/solid-cells. Movement is semi-implicit
  Euler with per-axis friction, substepped to <=16 units/step, with per-axis AABB
  rollback + stair-stepping — exactly q1k3's _update_physics.

  No atan2/hypot (jolt doesn't bridge them); length uses Math/sqrt."
  (:require [fps-demo.map :as lvl]))

(def ^:const gravity      -1200.0)   ; a.y = gravity * _gravity-factor (player = 1)
(def ^:const substep-size 16.0)      ; max units moved per collision substep
(def ^:const jump-vel     400.0)
(def ^:const accel-speed  3000.0)    ; horizontal acceleration magnitude
(def ^:const mouse-sens   0.00075)
(def ^:const pitch-limit  1.5)
(def ^:const half         [12.0 24.0 12.0])   ; player AABB half-extents
(def ^:const step-height  17.0)

(defn player-half [] half)

;; --- player health / damage / death (entity_player) -------------------------
;; q1k3: 100 hp, subtract on hit, _kill (dead=1) at <=0. No armor, no i-frames.
;; Dead players ignore further damage. Pure: returns a new state map.
(def ^:const player-max-health 100.0)

(defn hurt-player
  "Apply `amount` damage to the player state map ({:health :dead?}). Returns a
  new map with health reduced (clamped to 0) and :dead? true once health hits 0."
  [player amount]
  (if (:dead? player)
    player
    (let [h (- (:health player player-max-health) amount)]
      (if (<= h 0)
        (assoc player :health 0.0 :dead? true)
        (assoc player :health h)))))

(defn respawn-player
  "Reset a dead player back to spawn at full health. q1k3 player/_kill marks
  the player dead, then game_init(map_index) re-spawns after a delay: position
  and velocity reset, view angles zeroed, health restored. Game time and held
  keys are preserved (this only resets the body, not the run)."
  [g spawn-pos]
  (-> g
      (assoc :p spawn-pos)
      (assoc :v [0.0 0.0 0.0])
      (assoc :yaw 0.0)
      (assoc :pitch 0.0)
      (assoc :health player-max-health)
      (assoc :dead? false)))

;; --- voxel AABB collision (q1k3 map_block_at_box / map_block_at) -----------

(defn- floor-div
  "Floor division toward negative infinity (matches JS `>>` on the cell sizes)."
  [x size]
  (int (Math/floor (/ (double x) (double size)))))

(defn block-at-box?
  "True if any solid cell overlaps the world-space AABB [min..max]. Cells are
  32 units wide on X/Z and 16 on Y — q1k3's map_block_at_box."
  [cells minv maxv]
  (let [x0 (floor-div (nth minv 0) 32) x1 (floor-div (nth maxv 0) 32)
        y0 (floor-div (nth minv 1) 16) y1 (floor-div (nth maxv 1) 16)
        z0 (floor-div (nth minv 2) 32) z1 (floor-div (nth maxv 2) 32)]
    (boolean
      (some (fn [k] (contains? cells k))
            (for [cx (range x0 (inc x1))
                  cy (range y0 (inc y1))
                  cz (range z0 (inc z1))]
              (lvl/cell-key cx cy cz))))))

(defn- collides?
  "True if the box centered at point p (half-extents h) hits any solid cell.
  With keep-off-ledges + on-ground set (enemies), q1k3 also treats a spot with no
  floor just beneath the feet as a collision, so AI walks up to but not off
  ledges — map_block_at(p, foot-8) and (foot-24) both empty => ledge => collide."
  ([cells p h] (collides? cells p h false false))
  ([cells p h keep-off-ledges og]
   (or (and keep-off-ledges og
            (not (lvl/block-at? cells (nth p 0) (- (nth p 1) (nth h 1) 8.0) (nth p 2)))
            (not (lvl/block-at? cells (nth p 0) (- (nth p 1) (nth h 1) 24.0) (nth p 2))))
       (block-at-box? cells
                      [(- (nth p 0) (nth h 0)) (- (nth p 1) (nth h 1)) (- (nth p 2) (nth h 2))]
                      [(+ (nth p 0) (nth h 0)) (+ (nth p 1) (nth h 1)) (+ (nth p 2) (nth h 2))]))))

;; --- one collision substep: per-axis rollback + stair-stepping -------------
;; Returns {:p [x y z] :v [x y z] :on-ground bool :stop bool} given the last
;; position lp, this substep's delta, incoming velocity, and flags. Mirrors the
;; three independent axis checks in entity_t._update_physics.

(defn- substep [cells lp delta v og h sh bnc kol]
  (let [lpx (nth lp 0) lpy (nth lp 1) lpz (nth lp 2)
        sx  (nth delta 0) sy (nth delta 1) sz (nth delta 2)
        vx  (nth v 0) vy (nth v 1) vz (nth v 2)
        nx  (+ lpx sx) ny (+ lpy sy) nz (+ lpz sz)]
    ;; X axis: probe at (nx, lpy, lpz)
    (let [[x1 lpy1 vx1 st1]
          (if (collides? cells [nx lpy lpz] h kol og)
            (let [above    (collides? cells [nx (+ lpy sh) lpz] h kol og)
                  can-step (and (pos? sh) og (<= vy 0) (not above))]
              (if can-step
                [nx (+ lpy sh) vx true]              ; step up: keep the x move, raise y
                [lpx lpy (* (- vx) bnc) true]))      ; rollback x, kill v.x
            [nx lpy vx false])
          ;; Z axis: probe at (x1, lpy1, nz)
          [z1 lpy2 vz2 st2]
          (if (collides? cells [x1 lpy1 nz] h kol og)
            (let [above    (collides? cells [x1 (+ lpy1 sh) nz] h kol og)
                  can-step (and (pos? sh) og (<= vy 0) (not above))]
              (if can-step
                [nz (+ lpy1 sh) vz true]
                [lpz lpy1 (* (- vz) bnc) true]))
            [nz lpy1 vz false])
          ;; Y axis: probe the full new position (plain box test, no ledge check —
          ;; the ledge rule only stops horizontal walk-off, not vertical settle)
          y-final (if (collides? cells [x1 ny z1] h)
                    (let [bounce (if (> (Math/abs vy) 200.0) bnc 0.0)]
                      [lpy2 (* (- vy) bounce) (and (neg? vy) (zero? bounce)) true])
                    [ny vy og false])]
      {:p          [x1 (nth y-final 0) z1]
       :v          [vx1 (nth y-final 1) vz2]
       :on-ground  (nth y-final 2)
       :collided-h (or st1 st2)                     ; horizontal (x/z) stop, for AI turns
       :stop       (or st1 st2 (nth y-final 3))})))

;; --- full physics step (velocity integration + substepped move) ------------

(defn step-physics
  "Advance player `state` by one tick (seconds). Applies gravity, integrates
  acceleration + per-axis friction into velocity, then moves in <=16-unit
  substeps with per-axis collision rollback. Pure; `cells` is the voxel set."
  [cells state tick]
  (let [a-in  (get state :a [0.0 0.0 0.0])
        gx    (nth a-in 0 0.0)
        gz    (nth a-in 2 0.0)
        gy    (* gravity (double (get state :gravity 1)))
        f     (get state :f 0)
        ff    (min (* (double f) (double tick)) 1.0)
        v0    (get state :v [0.0 0.0 0.0])
        vx    (+ (* (nth v0 0 0.0) (- 1.0 ff)) (* gx (double tick)))
        vy    (+ (nth v0 1 0.0) (* gy (double tick)))
        vz    (+ (* (nth v0 2 0.0) (- 1.0 ff)) (* gz (double tick)))
        p0    (get state :p [0.0 0.0 0.0])
        mdx   (* vx (double tick)) mdy (* vy (double tick)) mdz (* vz (double tick))
        dist  (Math/sqrt (+ (* mdx mdx) (* mdy mdy) (* mdz mdz)))
        n     (max 1 (int (Math/ceil (/ dist substep-size))))
        inv   (/ 1.0 (double n))
        delta [(* mdx inv) (* mdy inv) (* mdz inv)]
        bnc   (get state :bounciness 0.0)
        sh    (get state :step-height step-height)
        hlf   (get state :half half)
        kol   (get state :keep-off-ledges false)]
    (loop [p p0 v [vx vy vz] og (boolean (:on-ground state)) k 0 col false]
      (if (>= k n)
        {:p p :v v :a a-in :f f
         :on-ground og :gravity (get state :gravity 1)
         :bounciness bnc :step-height sh :collided col}
        (let [r (substep cells p delta v og hlf sh bnc kol)]
          (if (:stop r)
            {:p (:p r) :v (:v r) :a a-in :f f
             :on-ground (:on-ground r) :gravity (get state :gravity 1)
             :bounciness bnc :step-height sh
             :collided (or col (:collided-h r))}
            (recur (:p r) (:v r) (:on-ground r) (inc k)
                   (or col (:collided-h r)))))))))

;; --- mouse look (q1k3 entity_player _update) -------------------------------

(defn look
  "Apply one frame of mouse delta to [yaw pitch]. Returns [yaw' pitch']. Yaw
  wraps mod 2pi; pitch clamps to [-pitch-limit, pitch-limit]. `opts` (a map,
  may be nil) carries :sens (one sensitivity for both axes, default mouse-sens —
  q1k3 uses a single _mouse_sensitivity) and :invert (flip the Y axis)."
  ([yaw pitch mx my]
   (look yaw pitch mx my nil))
  ([yaw pitch mx my opts]
   (let [s  (double (get opts :sens mouse-sens))
         yf (if (get opts :invert) (- s) s)
         yaw'   (mod (+ yaw (* (double mx) s)) (* 2.0 Math/PI))
         pitch' (max (- pitch-limit)
                     (min pitch-limit (+ pitch (* (double my) yf))))]
     [yaw' pitch'])))

;; --- movement acceleration from key state (entity_player _update) ----------
;; wishdir = rotate_y((right-left, 0, up-down), yaw) * speed * (ground?1:0.3)

(defn wish-accel
  "Horizontal acceleration vector [ax ay az] (ay left 0; gravity fills it) from
  intent map {:forward :back :left :right} (each 0/1), the current yaw, and
  whether the player is grounded."
  [intent yaw grounded]
  (let [ix (- (get intent :right 0) (get intent :left 0))
        iz (- (get intent :forward 0) (get intent :back 0))
        siny (Math/sin yaw) cosy (Math/cos yaw)
        ;; rotate_y([ix,0,iz], yaw): [iz*sin + ix*cos, 0, iz*cos - ix*sin]
        rx (+ (* iz siny) (* ix cosy))
        rz (- (* iz cosy) (* ix siny))
        scale (* accel-speed (if grounded 1.0 0.3))]
    [(* rx scale) 0.0 (* rz scale)]))

;; --- camera forward vector --------------------------------------------------
;; The first-person view looks along `forward`. Its horizontal projection MUST
;; agree with wish-accel's forward (iz=1) so pressing W walks toward screen
;; center. wish-accel forward at yaw is [sin yaw, cos yaw] in (x,z); forward
;; below projects to the same [sin yaw, cos yaw] (pitch only scales both by
;; cos pitch). At yaw=0 forward=+Z.

(defn forward
  "Unit view direction [x y z] for a first-person camera at yaw (about Y) and
  pitch (about X). yaw=0,pitch=0 -> [0 0 1] (+Z)."
  [yaw pitch]
  [(double (* (Math/cos pitch) (Math/sin yaw)))
   (double (Math/sin pitch))
   (double (* (Math/cos pitch) (Math/cos yaw)))])
