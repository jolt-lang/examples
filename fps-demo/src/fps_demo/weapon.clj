(ns fps-demo.weapon
  "Player shotgun: hitscan raycast against map geometry (fps-demo.map/map-trace)
  and enemy AABBs (slab test), nearest-wins, plus damage via entity/receive-damage.
  Pure / GL-free so hit selection and damage are unit-tested in check.clj. The
  caller supplies the unit ray direction (fps-demo.player/forward yaw pitch)."
  (:require [fps-demo.map    :as lvl]
            [fps-demo.entity :as ent]))

(def ^:const shotgun-range  2048.0)   ; effective reach
(def ^:const shotgun-damage 24.0)     ; 40hp grunt dies in two shells

;; --- ray vs AABB (slab method) -----------------------------------------------
;; `dir` must be a unit vector. Returns the entry distance t>=0, or nil on miss.
(defn ray-aabb [origin dir center half]
  (loop [axis 0 tnear 0.0 tfar Double/POSITIVE_INFINITY]
    (if (= axis 3)
      (if (and (>= tfar 0.0) (<= tnear tfar)) (max 0.0 tnear) nil)
      (let [d (double (nth dir axis))
            o (double (nth origin axis))
            c (double (nth center axis))
            h (double (nth half axis))
            lo (- c h) hi (+ c h)]
        (if (zero? d)
          (if (or (< o lo) (> o hi))
            nil                                   ; ray parallel & outside the slab
            (recur (inc axis) tnear tfar))
          (let [invd (/ 1.0 d)
                t1  (* (- lo o) invd)
                t2  (* (- hi o) invd)
                [tmin tmax] (if (< t1 t2) [t1 t2] [t2 t1])
                tn  (max tnear tmin)
                tf  (min tfar tmax)]
            (if (> tn tf) nil (recur (inc axis) tn tf))))))))

;; --- distance to the first solid voxel along `dir` (nil if none in range) -----
(defn- wall-dist [cells eye dir]
  (let [ex (nth eye 0) ey (nth eye 1) ez (nth eye 2)
        dx (nth dir 0) dy (nth dir 1) dz (nth dir 2)
        endp [(+ ex (* dx shotgun-range))
              (+ ey (* dy shotgun-range))
              (+ ez (* dz shotgun-range))]]
    (when-let [[hx hy hz] (lvl/map-trace cells eye endp)]
      (let [gx (- hx ex) gy (- hy ey) gz (- hz ez)]
        (Math/sqrt (+ (* gx gx) (* gy gy) (* gz gz)))))))

;; --- nearest target along the ray --------------------------------------------
(defn hitscan
  "Nearest target along unit `dir` from `eye` within shotgun range. Returns nil
  (missed/sky), {:kind :wall :dist d}, or {:kind :enemy :idx i :dist d}. A wall
  occludes any enemy behind it."
  [cells eye dir enemies]
  (let [wd        (or (wall-dist cells eye dir) Double/POSITIVE_INFINITY)
        wall-hit? (< wd shotgun-range)
        en        (loop [i 0 best-i nil best-t Double/POSITIVE_INFINITY]
                    (if (= i (count enemies))
                      (when best-i [best-i best-t])
                      (let [e (nth enemies i)]
                        (if-let [t (and (not (:dead? e))
                                        (ray-aabb eye dir (:pos e) (:half e)))]
                          (if (< t best-t) (recur (inc i) i t) (recur (inc i) best-i best-t))
                          (recur (inc i) best-i best-t)))))]
    (cond
      (and en (< (second en) wd)) {:kind :enemy :idx (first en) :dist (second en)}
      wall-hit?                    {:kind :wall :dist wd}
      :else                        nil)))

;; --- fire one shell ----------------------------------------------------------
(defn fire-shot
  "Fire one shotgun shell from `eye` along unit `dir`. Returns [enemies' hit]:
  the hit enemy takes shotgun-damage (waking if idle, culled if killed) via
  entity/receive-damage; `hit` is the hitscan descriptor (nil on a miss)."
  [cells eye dir enemies game-time rng]
  (let [hit (hitscan cells eye dir enemies)]
    (if (= :enemy (:kind hit))
      (let [i  (:idx hit)
            e  (nth enemies i)
            e' (ent/receive-damage e shotgun-damage eye game-time rng)]
        [(filterv #(not (:dead? %)) (assoc enemies i e')) hit])
      [enemies hit])))
