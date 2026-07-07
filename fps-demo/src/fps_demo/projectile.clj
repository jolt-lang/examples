(ns fps-demo.projectile
  "Pure Clojure port of q1k3's projectile entities (entity_projectile_*.js) and
  the subset of entity_t._update_physics they use. GL-free and deterministic so
  the flight, wall/entity collision, bounce, and area-damage explosion are
  unit-tested headlessly (see check.clj).

  q1k3 fires *physical* projectiles for every attack: the shotgun throws 8 fast
  shell pellets, the nailgun nails, the enforcer plasma, the ogre/grenade-launcher
  bouncing grenades, the zombie gibs. Each is an entity with gravity, friction,
  bounciness and a die-at timer, integrated with the same substepped per-axis
  collision as the player. This module reproduces that as a data-only simulation.

  A projectile is a plain map:
    {:kind :shell|:nail|:plasma|:grenade|:gib
     :pos [x y z] :vel [x y z]
     :gravity g        ; multiplier of -1200 (0 = no gravity)
     :bounciness b     ; velocity retained per axis on a wall bounce
     :ground-friction / :air-friction   ; q1k3 .f, chosen by :on-ground
     :die-at t         ; game-time at which it expires
     :direct d         ; damage dealt on a direct entity hit
     :radius r         ; explosion radius (grenade); nil = no area damage
     :area d           ; explosion damage at the center (grenade)
     :team :player|:enemy  ; :player projectiles hit enemies, :enemy hit the player
     :on-ground bool :yaw :pitch}

  `step-projectiles` returns [projectiles' events]; events are the effects the
  caller applies against the live enemy list / player (damage) and the particle
  system (impact debris):
    {:t :hit     :team t :idx i :amount d}   ; direct hit (idx into that team)
    {:t :explode :team t :pos p :radius r :amount d}
    {:t :fx      :pos p :n n}                ; spawn n impact particles"
  (:require [fps-demo.player :as player]
            [fps-demo.entity :as ent]))

(def ^:const gravity      -1200.0)   ; entity._update_physics a.y = -1200 * _gravity
(def ^:const substep-size 16.0)      ; max units per collision substep
(def ^:const proj-radius  2.0)       ; q1k3 entity_t default this.s.y for projectiles

;; --- per-kind construction (entity_projectile_*.js _init) -------------------
;; Damage / speed / gravity / lifetime match the originals. Grenade is the only
;; bouncer (bounciness 0.5) and the only area-damage kind; the rest die on the
;; first wall or entity contact. Friction: shell/nail/plasma have none; grenade
;; and gib gain ground friction so they settle (q1k3 sets .f each _update).

(defn- base [kind team pos vel time]
  {:kind kind :team team :pos (vec pos) :vel (vec vel)
   :gravity 0.0 :bounciness 0.0 :ground-friction 0.0 :air-friction 0.0
   :direct 0.0 :radius nil :area 0.0 :on-ground false :yaw 0.0 :pitch 0.0
   ;; shell/nail/plasma pop on the first wall (their _did_collide -> _kill);
   ;; grenade bounces, gib slides — both survive walls (overridden below).
   :die-on-wall true
   :die-at (+ time 3.0)})

(defn shell
  "Shotgun/grunt pellet: no gravity, ~instant (0.1s life at 10000 u/s), 4 dmg.
  entity_projectile_shell_t."
  [team pos vel time]
  (assoc (base :shell team pos vel time) :direct 4.0 :die-at (+ time 0.1)))

(defn nail
  "Nailgun nail: no gravity, 3s life, 9 dmg. entity_projectile_nail_t."
  [team pos vel time]
  (assoc (base :nail team pos vel time) :direct 9.0 :die-at (+ time 3.0)))

(defn plasma
  "Enforcer plasma: no gravity, 3s life, 15 dmg. entity_projectile_plasma_t."
  [team pos vel time]
  (assoc (base :plasma team pos vel time) :direct 15.0 :die-at (+ time 3.0)))

(defn grenade
  "Bouncing grenade: gravity, bounciness 0.5, 2s fuse, then an area-damage
  explosion (radius 196, `dmg` at center scaling to 0 at the rim). Ground
  friction settles it. entity_projectile_grenade_t (dmg defaults to 120, the
  grenade-launcher value; the ogre passes 40)."
  ([team pos vel time] (grenade team pos vel time 120.0))
  ([team pos vel time dmg]
   (assoc (base :grenade team pos vel time)
     :gravity 1.0 :bounciness 0.5 :die-on-wall false :die-at (+ time 2.0)
     :ground-friction 5.0 :air-friction 0.5
     :radius 196.0 :area dmg)))

(defn gib
  "Zombie gib: gravity, no bounce, 2s life, 10 dmg on contact. Ground friction
  15 so it slides to rest. entity_projectile_gib_t."
  [team pos vel time]
  (assoc (base :gib team pos vel time)
    :gravity 1.0 :die-on-wall false :die-at (+ time 2.0) :direct 10.0
    :ground-friction 15.0))

;; --- geometry helpers -------------------------------------------------------

(defn- collides-wall?
  "True if the projectile's small AABB at world point p overlaps a solid cell."
  [cells p]
  (let [px (nth p 0) py (nth p 1) pz (nth p 2)]
    (player/block-at-box? cells
                          [(- px proj-radius) (- py proj-radius) (- pz proj-radius)]
                          [(+ px proj-radius) (+ py proj-radius) (+ pz proj-radius)])))

(defn- target-hit
  "Index of the first live target whose sphere the point p is inside, or nil.
  q1k3 _collides: dist(p, target.p) < proj.s.y + target.s.y. `targets` is a
  vector of {:pos [x y z] :half [hx hy hz] :dead? bool}."
  [targets p]
  (let [px (nth p 0) py (nth p 1) pz (nth p 2)]
    (loop [i 0]
      (if (>= i (count targets))
        nil
        (let [t  (nth targets i)]
          (if (:dead? t)
            (recur (inc i))
            (let [tp (:pos t)
                  dx (- px (nth tp 0)) dy (- py (nth tp 1)) dz (- pz (nth tp 2))
                  d  (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                  r  (+ proj-radius (nth (:half t) 1))]
              (if (< d r) i (recur (inc i))))))))))

;; --- one substep: per-axis wall collision + bounce (entity._update_physics) --
;; Returns {:pos :vel :on-ground :wall? :hit idx-or-nil}. `bnc` is the retained
;; velocity fraction on a wall bounce (0 for non-bouncers, which then stop dead
;; and are killed by the caller). Target hits are detected at the moved position.

(defn- substep [cells lp delta v og bnc targets]
  (let [lpx (nth lp 0) lpy (nth lp 1) lpz (nth lp 2)
        vx  (nth v 0) vy (nth v 1) vz (nth v 2)
        nx  (+ lpx (nth delta 0)) ny (+ lpy (nth delta 1)) nz (+ lpz (nth delta 2))
        ;; X axis
        [x1 vx1 wx] (if (collides-wall? cells [nx lpy lpz])
                      [lpx (* (- vx) bnc) true]
                      [nx vx false])
        ;; Z axis
        [z1 vz1 wz] (if (collides-wall? cells [x1 lpy nz])
                      [lpz (* (- vz) bnc) true]
                      [nz vz false])
        ;; Y axis (ground/ceiling)
        [y1 vy1 og1 wy]
        (if (collides-wall? cells [x1 ny z1])
          (let [bounce (if (> (Math/abs vy) 200.0) bnc 0.0)]
            [lpy (* (- vy) bounce) (and (neg? vy) (zero? bounce)) true])
          [ny vy og false])
        p1  [x1 y1 z1]
        hit (target-hit targets p1)]
    {:pos p1 :vel [vx1 vy1 vz1] :on-ground og1
     :wall? (or wx wz wy) :hit hit}))

;; --- full per-tick step for one projectile ----------------------------------

(defn- expire-events
  "Effects when a projectile reaches its die-at timer with no contact. A grenade
  explodes (area damage + debris); the rest vanish silently (q1k3 base _kill)."
  [proj]
  (if (= :grenade (:kind proj))
    [{:t :explode :team (:team proj) :pos (:pos proj)
      :radius (:radius proj) :amount (:area proj)}
     {:t :fx :pos (:pos proj) :n 20}]
    []))

(defn- contact-events
  "Effects when a projectile touches a wall or an entity. A grenade always
  explodes. A direct hit on an entity emits a :hit (the impact blood is spawned
  by the damaged enemy in the apply layer, as q1k3 does in _receive_damage). A
  wall impact (hit-idx nil) sparks a couple of impact particles — q1k3's
  entity_projectile_{shell,nail,plasma}._did_collide."
  [proj hit-idx]
  (cond
    (= :grenade (:kind proj))
    [{:t :explode :team (:team proj) :pos (:pos proj)
      :radius (:radius proj) :amount (:area proj)}
     {:t :fx :pos (:pos proj) :n 20}]
    hit-idx
    [{:t :hit :team (:team proj) :idx hit-idx :amount (:direct proj)}]
    :else
    [{:t :fx :pos (:pos proj) :n 2}]))

(defn step-projectile
  "Advance one projectile by `tick` seconds against `cells` and `targets`.
  Returns {:proj p'|nil :events [...]}. Mirrors entity_t._update_physics: apply
  gravity, integrate velocity with friction, then move in <=16-unit substeps
  stopping at the first wall/entity contact."
  [cells proj targets time tick]
  (if (< (:die-at proj) time)
    {:proj nil :events (expire-events proj)}
    (let [og   (:on-ground proj)
          f    (if og (:ground-friction proj) (:air-friction proj))
          ff   (min (* f tick) 1.0)
          v0   (:vel proj)
          gy   (* gravity (:gravity proj))
          vx   (* (nth v0 0) (- 1.0 ff))
          vy   (+ (nth v0 1) (* gy tick))
          vz   (* (nth v0 2) (- 1.0 ff))
          md   [(* vx tick) (* vy tick) (* vz tick)]
          dist (Math/sqrt (+ (* (nth md 0) (nth md 0))
                             (* (nth md 1) (nth md 1))
                             (* (nth md 2) (nth md 2))))
          n    (max 1 (int (Math/ceil (/ dist substep-size))))
          inv  (/ 1.0 (double n))
          delta [(* (nth md 0) inv) (* (nth md 1) inv) (* (nth md 2) inv)]
          bnc  (:bounciness proj)]
      (loop [p (:pos proj) v [vx vy vz] og og k 0]
        (if (>= k n)
          {:proj (assoc proj :pos p :vel v :on-ground og) :events []}
          (let [r (substep cells p delta v og bnc targets)]
            (cond
              ;; entity contact: damage (or explode) and die
              (:hit r)
              {:proj nil :events (contact-events (assoc proj :pos (:pos r)) (:hit r))}
              ;; wall contact
              (:wall? r)
              (if (:die-on-wall proj)
                ;; shell/nail/plasma: pop on impact (spark particles)
                {:proj nil :events (contact-events (assoc proj :pos (:pos r)) nil)}
                ;; grenade bounces, gib slides: keep the substep's reflected/zeroed
                ;; velocity and stay alive (q1k3 stops the substep loop, s = steps)
                {:proj (assoc proj :pos (:pos r) :vel (:vel r) :on-ground (:on-ground r))
                 :events []})
              :else
              (recur (:pos r) (:vel r) (:on-ground r) (inc k)))))))))

;; --- fold every projectile ---------------------------------------------------
;; Player-team projectiles collide with `enemies`; enemy-team with the single
;; `player` target. Both are {:pos :half :dead?} lists (player wrapped in a 1-vec).

(defn step-projectiles
  "Step every projectile one tick. `enemies` is the live enemy list (each carries
  :pos/:half); `player-target` is {:pos :half}. Returns [projectiles' events]."
  [cells projectiles enemies player-target time tick]
  (let [ptargets (mapv (fn [e] {:pos (:pos e) :half (:half e) :dead? (:dead? e)}) enemies)
        etargets [{:pos (:pos player-target) :half (:half player-target) :dead? false}]]
    (loop [in (seq projectiles) out [] evs []]
      (if-let [pr (first in)]
        (let [targets (if (= :player (:team pr)) ptargets etargets)
              {:keys [proj events]} (step-projectile cells pr targets time tick)]
          (recur (next in)
                 (if proj (conj out proj) out)
                 (into evs events)))
        [out evs]))))

;; --- area-damage helper (scale center damage to 0 at the rim) ---------------
;; q1k3: entity._receive_damage(scale(dist, 0, radius, amount, 0)). Callers use
;; this to turn an :explode event into per-target damage.

(defn splash-damage
  "Damage a target at `dist` takes from an explosion of `amount` at radius `r`:
  linear falloff, 0 beyond the rim. Mirrors q1k3's scale(dist,0,r,amount,0)."
  [dist r amount]
  (if (>= dist r) 0.0 (* amount (- 1.0 (/ dist r)))))

;; --- attack spawn helpers (weapons.js + entity_enemy_*._attack) -------------
;; Both aim with the port's player/forward so the projectile travels exactly
;; where the shooter looks (the same convention core uses for the camera and the
;; old hitscan). q1k3 velocity = rotate_yaw_pitch((0,0,speed), yaw, pitch);
;; that equals speed * forward(yaw, pitch) in this port's convention.

(defn- scaled-forward [yaw pitch speed]
  (let [f (player/forward yaw pitch)]
    [(* speed (nth f 0)) (* speed (nth f 1)) (* speed (nth f 2))]))

(def ^:const shell-speed   10000.0)
(def ^:const plasma-speed    800.0)
(def ^:const grenade-speed   600.0)
(def ^:const gib-speed       600.0)
(def ^:const ogre-grenade-damage 40.0)

(defn- jitter
  "q1k3 shotgun/grunt spread: Math.random()*0.08 - 0.04 (a +/-0.04 rad wobble)."
  [rng]
  (- (* 0.08 (rng)) 0.04))

(defn player-shotgun
  "8 shell pellets from `eye` along the look direction (yaw/pitch), each with
  q1k3's +/-0.04 rad yaw & pitch spread. weapon_shotgun_t._spawn_projectile."
  [eye yaw pitch time rng]
  (mapv (fn [_]
          (let [y (+ yaw   (jitter rng))
                p (+ pitch (jitter rng))
                f (player/forward y p)
                o [(+ (nth eye 0) (* (nth f 0) 8.0))
                   (+ (nth eye 1) (* (nth f 1) 8.0))
                   (+ (nth eye 2) (* (nth f 2) 8.0))]]
            (shell :player o (scaled-forward y p shell-speed) time)))
        (range 8)))

(defn- aim-pitch
  "Vertical aim angle (this port's forward convention) from `shooter` toward
  `target`: forward's y = sin(pitch), so pitch = atan2(dy_up, horizontal-dist)
  points the shot at the target. q1k3 computes the mirror angle and negates it
  inside rotate_yaw_pitch; the net direction is the same."
  [shooter target]
  (ent/atan2 (- (double (nth target 1)) (double (nth shooter 1)))
             (ent/dist-2d shooter target)))

(defn enemy-attack
  "Projectiles an enemy spawns when it fires (entity_enemy_*._attack). Dispatches
  on the enemy's :model. Grunt: 3 spread shells. Enforcer: 1 plasma. Ogre: 1
  lobbed grenade (40 dmg). Zombie: 1 lobbed gib. Hound (melee) and unknown types
  fire nothing. Aimed from the enemy's yaw with a pitch toward the player."
  [enemy player-pos time rng]
  (let [ep    (:pos enemy)
        yaw   (:yaw enemy)
        aim   (aim-pitch ep player-pos)]
    (case (:model enemy)
      :grunt    (mapv (fn [_] (shell :enemy ep
                                     (scaled-forward (+ yaw (jitter rng))
                                                     (+ aim (jitter rng))
                                                     shell-speed)
                                     time))
                      (range 3))
      :enforcer [(plasma :enemy ep (scaled-forward yaw aim plasma-speed) time)]
      ;; ogre lobs: +0.4 rad upward (q1k3 pitch_offset -0.4 on the mirror angle)
      :ogre     [(grenade :enemy ep (scaled-forward yaw (+ aim 0.4) grenade-speed)
                          time ogre-grenade-damage)]
      :zombie   [(gib :enemy ep (scaled-forward yaw (+ aim 0.5) gib-speed) time)]
      [])))
