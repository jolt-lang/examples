(ns fps-demo.hud
  "Pure HUD logic for the q1k3 port: the only non-trivial math in the HUD is the
  health->bar fill fraction (clamped) and the muzzle-flash visibility window.
  Both are GL-free and unit-tested; render.clj consumes them for the overlay.")

(def ^:const player-max-health 100.0)
(def ^:const muzzle-duration   0.06)   ; seconds the flash is visible after fire

(defn bar-fill
  "Health -> [0,1] bar fill, clamped at both ends (q1k3 health bar never inverts
  or overflows: a dead player is an empty bar, overheal a full one)."
  [health]
  (let [f (/ health player-max-health)]
    (cond
      (<= f 0.0) 0.0
      (>= f 1.0) 1.0
      :else f)))

(defn muzzle-active?
  "True when `now` is within muzzle-duration of the last fire time. A nil
  fire-time (never fired) means the flash is off."
  [now fire-time]
  (and (some? fire-time) (< (- now fire-time) muzzle-duration)))
