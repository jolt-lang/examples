(ns fps-demo.entity
  "Pure Clojure port of q1k3 enemy AI (entity_enemy.js + entity.js base).

  Everything here is GL-free and deterministic so the full idle→see→chase→
  attack→die state machine is unit-tested headlessly. The FSM is decoupled from
  the map: `step-enemy` takes a precomputed `view` ({:dist :angle :can-see})
  rather than querying the world, so line-of-sight (map_trace) and distance can
  be supplied (and stubbed) by the caller. Randomness is an injected 0-arg `rng`
  -> double in [0,1) for reproducible tests.

  jolt does NOT bridge Math/atan2 or Math/hypot, so this module ships a
  hand-rolled atan2 (rational approx, ~0.005 rad) and an exact anglemod."
  (:require [fps-demo.map :as lvl]))

;; --- animation table (entity_enemy._ANIMS): [period, [frame-indices]] -------
(def ANIMS
  [[1.0  [0]]          ; 0 idle
   [0.40 [1 2 3 4]]    ; 1 walk  (patrol)
   [0.20 [1 2 3 4]]    ; 2 run   (follow / evade)
   [0.25 [0 5 5 5]]    ; 3 attack prepare
   [0.25 [5 0 0 0]]])  ; 4 attack exec

;; --- state table (entity_enemy._STATE_*): {:anim :speed :duration :next?} ---
;; next is the auto-advance target applied at the start of a state-update tick.
(def STATES
  {:idle           {:anim 0 :speed 0.0 :duration 0.1}
   :patrol         {:anim 1 :speed 0.5 :duration 0.5}
   :follow         {:anim 2 :speed 1.0 :duration 0.3}
   :attack-recover {:anim 0 :speed 0.0 :duration 0.1 :next :follow}
   :attack-exec    {:anim 4 :speed 0.0 :duration 0.4 :next :attack-recover}
   :attack-prepare {:anim 3 :speed 0.0 :duration 0.4 :next :attack-exec}
   :attack-aim     {:anim 0 :speed 0.0 :duration 0.1 :next :attack-prepare}
   :evade          {:anim 2 :speed 1.0 :duration 0.8 :next :attack-aim}})

;; --- enemy defaults (entity_enemy._init) ------------------------------------
(def ^:const enemy-half  [12.0 28.0 12.0])   ; AABB half-extents (player is 24 on Y)
(def ^:const step-height 17.0)
(def ^:const enemy-speed 196.0)              ; _speed
(def ^:const wake-distance 700.0)            ; idle/patrol wake range
(def ^:const attack-distance 800.0)
(def ^:const evade-distance 96.0)
(def ^:const attack-chance 0.65)
(def ^:const base-health 50.0)

;; --- trig / vectors ---------------------------------------------------------
;; jolt lacks Math/atan2 and Math/hypot, so ship our own. atan2 uses the
;; Rajan rational approximation (max error ~0.0015 rad): reduce to octant 0
;; (|y|<=|x|), evaluate a degree-3 minimax in a=y/x, then unfold quadrants.
(defn atan2 [y x]
  (let [ay (Math/abs (double y))
        ax (Math/abs (double x))
        mx (max ay ax)]
    (if (zero? mx)
      0.0
      (let [a  (/ (min ay ax) mx)
            s  (* a a)
            r1 (let [c1 -0.0464964749
                     c2  0.15931422
                     c3 -0.327622764]
                 (+ (* (+ (* (+ (* c1 s) c2) s) c3) s a) a))
            r2 (if (> ay ax) (- 1.57079637 r1) r1)
            r3 (if (neg? x) (- Math/PI r2) r2)]
        (if (neg? y) (- r3) r3)))))

;; anglemod: fold to (-pi, pi]. Equivalent to atan2(sin a, cos a) but exact.
(defn anglemod [a]
  (let [twopi (* 2.0 Math/PI)]
    (loop [r a]
      (cond (> r Math/PI)        (recur (- r twopi))
            (<= r (- Math/PI))   (recur (+ r twopi))
            :else                r))))

;; vec3_2d_angle / vec3_dist (horizontal plane). yaw 0 = +Z forward.
(defn angle-to-player [enemy-pos player-pos]
  (atan2 (- (double (nth player-pos 0)) (double (nth enemy-pos 0)))
         (- (double (nth player-pos 2)) (double (nth enemy-pos 2)))))

(defn dist-2d [a b]
  (let [dx (- (double (nth a 0)) (double (nth b 0)))
        dz (- (double (nth a 2)) (double (nth b 2)))]
    (Math/sqrt (+ (* dx dx) (* dz dz)))))

;; --- entity construction / state --------------------------------------------
;; Enemy state is a plain map (no mutation): step-enemy returns a new map each
;; tick. Mirrors entity_enemy.js: anim index + period/frames carried for the
;; renderer; state-update-at carries the q1k3 duration jitter.
(defn- anim-for [idx] (nth ANIMS idx))

(defn set-state [enemy state-key game-time rng]
  (let [spec           (get STATES state-key)
        [period frames] (anim-for (:anim spec))
        dur            (:duration spec)
        at             (+ game-time dur (* dur 0.25 (rng)))]
    (assoc enemy
      :state          state-key
      :anim-period    period
      :anim-frames    frames
      :anim-time      0.0
      :state-update-at at)))

(defn make-enemy [pos yaw]
  (set-state
    {:pos pos, :vel [0.0 0.0 0.0], :yaw yaw, :target-yaw yaw,
     :turn-bias 0.0, :state :idle,
     :health base-health, :on-ground true, :dead? false,
     :speed enemy-speed, :half enemy-half, :step-height step-height}
    :idle 0.0 (fn [] 0.0)))

;; --- FSM decision tick (entity_enemy._update, the if-tree) ------------------
;; Runs only when state-update-at < game-time. Faithful port: auto-advance via
;; a state's :next happens FIRST, then the per-state checks are a sequence of
;; independent ifs (so multiple can fire as state mutates mid-tick). `view` is
;; precomputed by the caller: {:dist :angle :can-see} where can-see = clear LOS.
(def ^:const half-pi 1.5707963267948966)

(defn- state-speed [state-key]
  (:speed (get STATES state-key)))

(defn- decide [enemy dist angle can-see game-time rng]
  (let [e1 (if (= (:state enemy) :follow)
             (let [e (if can-see (assoc enemy :target-yaw angle) enemy)]
               (if (< dist attack-distance)
                 (if (or (< dist evade-distance) (> (rng) attack-chance))
                   (let [e2 (set-state e :evade game-time rng)]
                     (assoc e2 :target-yaw (+ (:target-yaw e2) half-pi (* Math/PI (rng)))))
                   (set-state e :attack-aim game-time rng))
                 e))
             enemy)
        e2 (if (= (:state e1) :attack-recover)
             (assoc e1 :target-yaw angle)
             e1)
        e3 (if (or (= (:state e2) :patrol) (= (:state e2) :idle))
             (if (and (< dist wake-distance) can-see)
               (set-state e2 :attack-aim game-time rng)
               e2)
             e2)
        e4 (if (= (:state e3) :attack-aim)
             (let [e (assoc e3 :target-yaw angle)]
               (if (not can-see) (set-state e :evade game-time rng) e))
             e3)
        e5 (if (= (:state e4) :attack-exec)
             (assoc e4 :fire-now true)
             e4)]
    e5))

;; The full decision tick: pick a new turn-bias, auto-advance via :next, decide.
(defn state-update [enemy view game-time rng]
  (let [{:keys [dist angle can-see]} view
        r0 (rng)
        tb (if (> r0 0.5) 0.5 -0.5)
        e0 (assoc enemy :turn-bias tb)
        nxt (get (get STATES (:state e0)) :next)
        e1 (if nxt (set-state e0 nxt game-time rng) e0)]
    (decide e1 dist angle can-see game-time rng)))

;; --- every-frame (yaw lerp + velocity + anim advance) -----------------------
(defn- yaw-lerp [enemy]
  (let [d (anglemod (- (:target-yaw enemy) (:yaw enemy)))]
    (update enemy :yaw #(+ % (* d 0.1)))))

(defn- apply-velocity [enemy]
  (if (not (:on-ground enemy))
    enemy
    (let [fwd (* (state-speed (:state enemy)) (:speed enemy))
          ty  (:target-yaw enemy)
          vx  (* (Math/sin ty) fwd)
          vz  (* (Math/cos ty) fwd)
          vy  (nth (:vel enemy) 1)]
      (assoc enemy :vel [vx vy vz]))))

(defn- advance-anim [enemy dt]
  (update enemy :anim-time #(+ % dt)))

(defn step-enemy [enemy view game-time dt rng]
  (let [enemy (dissoc enemy :fire-now)
        after (if (< (:state-update-at enemy) game-time)
                (state-update enemy view game-time rng)
                enemy)]
    (-> (yaw-lerp after)
        (apply-velocity)
        (advance-anim dt))))

;; --- damage / death / grunt spec --------------------------------------------
;; entity_enemy._receive_damage: base subtracts health & kills; enemy override
;; wakes idle/patrol -> follow (facing the attacker's source = player). On death
;; the enemy is marked dead? and frozen (caller culls/gibs); further damage is a
;; no-op. angle is taken to the player (q1k3's sole damage source).
(defn receive-damage [enemy amount player-pos game-time rng]
  (let [e (assoc enemy :health (- (:health enemy) amount))]
    (cond
      (<= (:health e) 0)                       (assoc e :health 0.0 :dead? true)
      (or (= (:state e) :idle) (= (:state e) :patrol))
      (let [ang (angle-to-player (:pos e) player-pos)]
        (set-state (assoc e :target-yaw ang) :follow game-time rng))
      :else e)))

(def ^:const grunt-health 40.0)
(def ^:const grunt-texture 17)

;; entity_enemy_grunt._init: model grunt, texture 17, health 40. patrol-dir is
;; 0 (idle) or +/-1 (patrol, strafe along +/-X at yaw +/- pi/2).
(defn make-grunt [pos yaw patrol-dir]
  (let [g (-> (make-enemy pos yaw)
              (assoc :health grunt-health :texture grunt-texture :model :grunt))]
    (if (zero? patrol-dir)
      g
      (let [e (set-state g :patrol 0.0 (fn [] 0.0))]
        (assoc e :target-yaw (* half-pi patrol-dir))))))

;; enemy-view: the geometry bridge. Given solid cells, the player position and an
;; enemy, produce the FSM view {dist angle can-see} that step-enemy consumes.
;; Pure over its inputs (no GL); map-trace is the only map dependency. can-see is
;; true when map-trace finds NO solid voxel between enemy and player.
(defn enemy-view [cells player-pos enemy]
  (let [ep (:pos enemy)]
    {:dist    (dist-2d ep player-pos)
     :angle   (angle-to-player ep player-pos)
     :can-see (nil? (lvl/map-trace cells ep player-pos))}))

;; Per-shot damage a grunt deals when it fires (q1k3 entity_enemy fires ~4 dmg).
(def ^:const enemy-shot-damage 4.0)

;; step-enemies: pure per-tick fold. Steps every enemy against its view of the
;; player, then folds any :fire-now into an accumulated damage total. Returns
;; [new-enemies total-damage-this-tick]. Dead enemies are culled.
(defn step-enemies [cells player-pos enemies game-time dt rng]
  (loop [in   (seq enemies)
         out  []
         dmg  0.0]
    (if-let [e (first in)]
      (let [view (enemy-view cells player-pos e)
            e'   (step-enemy e view game-time dt rng)
            hit  (if (:fire-now e') enemy-shot-damage 0.0)]
        (recur (next in)
               (conj out (dissoc e' :fire-now))
               (+ dmg hit)))
      [out dmg])))
