(ns fps-demo.pickup
  "Pure pickup logic for the q1k3 port (health / ammo). GL-free and deterministic
  so player<->pickup collision + effect application is unit-tested headlessly.
  Mirrors q1k3 entity_pickup_t._update (vec3_dist(this.p, player.p) < 40) and the
  per-type _pickup() overrides (entity_pickup_health: +25 hp; nails: +50 ammo).

  A pickup is a plain map {:type :health :pos [x y z] :amount n :consumed? bool}.
  apply-pickups returns [player' pickups'] — a new player map with effects folded
  in and a new pickup list with consumed ones marked. Distance is true 3D
  (q1k3 vec3_dist), computed with Math/sqrt (no Math/hypot in jolt).")

(def ^:const pickup-radius 40.0)   ; entity_pickup_t._update pickup distance
(def ^:const health-amount 25.0)   ; entity_pickup_health_t._pickup

(defn make-health-pickup [pos]
  {:type :health :pos pos :amount health-amount :consumed? false})

(defn- dist3 [a b]
  (let [dx (- (nth a 0) (nth b 0))
        dy (- (nth a 1) (nth b 1))
        dz (- (nth a 2) (nth b 2))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn apply-pickups
  "Fold all pickups against player. q1k3 entity_pickup_t._update fires pickup()
  when vec3_dist(pickup.pos, player.pos) < pickup-radius. Returns [player' pickups']."
  [player pickups]
  (loop [ps pickups pl player out []]
    (if (empty? ps)
        [pl out]
        (let [pk (first ps)]
          (cond
            (:consumed? pk)                        (recur (rest ps) pl (conj out pk))
            (>= (dist3 (:pos pk) (:p player))
               pickup-radius)                      (recur (rest ps) pl (conj out pk))
            :else (let [pl2 (assoc pl :health (+ (:health pl) (:amount pk)))]
                    (recur (rest ps) pl2 (conj out (assoc pk :consumed? true)))))))))
