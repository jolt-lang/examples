(ns fps-demo.hud
  "Pure HUD logic for the q1k3 port: the muzzle-flash visibility window. GL-free
  and unit-tested; render.clj consumes it for the overlay (health/ammo are drawn
  as numbers, see fps-demo.overlay).")

(def ^:const muzzle-duration   0.06)   ; seconds the flash is visible after fire

(defn muzzle-active?
  "True when `now` is within muzzle-duration of the last fire time. A nil
  fire-time (never fired) means the flash is off."
  [now fire-time]
  (and (some? fire-time) (< (- now fire-time) muzzle-duration)))
