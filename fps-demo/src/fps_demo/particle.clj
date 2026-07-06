(ns fps-demo.particle
  "Pure particle system for the q1k3 port (blood / gibs / muzzle debris).
  GL-free and deterministic: spawn / step / cull are unit-tested headlessly.
  Mirrors q1k3 entity_particle_t (bounciness, friction) and
  entity_t._spawn_particles(amount, speed, model, texture, lifetime).

  A particle is a plain map {:pos [x y z] :vel [x y z] :life s :max-life s}.
  Randomness comes from an injected 0-arg rng -> double in [0,1) so tests are
  reproducible. No Math/atan2 or Math/hypot (jolt lacks them).")

(def ^:const blood-speed   240.0)   ; _spawn_particles speed for blood
(def ^:const blood-life     0.6)    ; lifetime seconds
(def ^:const particle-grav -1200.0) ; same gravity magnitude as player/enemy

(defn- rand-around [rng] (- (rng) 0.5))   ; q1k3 (rand-0.5)

(defn spawn-blood
  "Spawn n blood particles at pos. q1k3 entity_t._spawn_particles:
  v = ((rand-0.5)*spd, rand*spd, (rand-0.5)*spd). rng is a 0-arg fn -> [0,1)."
  [pos n rng]
  (let [[x y z] pos]
    (loop [i 0 acc []]
      (if (>= i n) acc
          (recur (inc i)
                 (conj acc {:pos [x y z]
                            :vel [(* (rand-around rng) blood-speed)
                                  (* (rng)              blood-speed)
                                  (* (rand-around rng) blood-speed)]
                            :life      blood-life
                            :max-life  blood-life}))))))

(defn step-particles
  "Semi-implicit Euler: vy += g*dt then pos += vel*dt. Cull particles whose
  remaining life drops to zero or below."
  [particles dt]
  (let [g (* particle-grav dt)]
    (loop [ps particles acc []]
      (if (empty? ps) acc
          (let [p   (first ps)
                [vx vy vz] (:vel p)
                vy2 (+ vy g)
                life2 (- (:life p) dt)]
            (if (<= life2 0.0)
              (recur (rest ps) acc)
              (recur (rest ps)
                     (conj acc (assoc p
                                 :vel [vx vy2 vz]
                                 :life life2
                                 :pos [(+ (nth (:pos p) 0) (* vx dt))
                                       (+ (nth (:pos p) 1) (* vy2 dt))
                                       (+ (nth (:pos p) 2) (* vz dt))])))))))))
