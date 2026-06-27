(ns gl-demo.core
  "First-person puzzle ('The Rotunda') built on glimmer-gl.

  The whole game mounts as one reactive GL area: the scene function (level) and
  every input handler deref/mutate cells on a single player-state map, so a
  keypress or a mouse-drag simply recomputes the scene plan. A label under the
  area shows controls and reacts to win / gate state."
  (:require [glimmer.core       :as ui]
            [glimmer-gl.app     :as glapp]
            [glimmer-gl.gtk     :as gtk]
            [gl-demo.level      :as level]
            [gl-demo.player     :as player]))

;; Single player state for the lifetime of the process.
(defonce player-state (player/make-player level/spawn))

;; Last frame's monotonic timestamp (microseconds), for frame-rate-independent dt.
(defonce last-us (atom nil))

(defn- tick-wrap [_area]
  (let [now-us  (gtk/g-get-monotonic-time)
        prev-us (or @last-us now-us)
        ;; microseconds -> seconds, clamped so a stalled frame can't catapult
        ;; the player through a wall.
        dt      (min 0.05 (max 0.0 (/ (double (- now-us prev-us)) 1000000.0)))]
    (reset! last-us now-us)
    (player/tick! player-state dt level/ctx)))

(defn- status-text []
  (cond
    @(:won player-state)        "✦ You escaped the Rotunda — close the window to quit."
    @(:gate-open player-state)  "The gate has swung open — walk toward the light at the far end."
    :else                       (str "Drag to look  ·  arrow keys / WASD to walk  ·  "
                                    "step into the glowing button to realign the gate.")))

(defn app []
  [:vbox {}
   [:gl-area (merge {:vexpand true :hexpand true}
                    (glapp/reactive-area (level/scene-root player-state)
                      {:bg          [0.04 0.04 0.06]
                       :ambient     0.28
                       :fog         {:near 8.0 :far 46.0 :color [0.04 0.04 0.06]}
                       :shadow-bias 0.002
                       :materials   level/materials
                       :on-motion   (partial player/on-motion player-state)
                       :on-key      (partial player/on-key player-state)
                       :on-button   (partial player/on-button player-state)
                       :on-tick     tick-wrap}))]
   [:label {:label (status-text) :margin 8 :xalign 0.0}]])

(defn -main [& _args]
  (ui/run app
          :app-id "glimmer.gl.rotunda"
          :title  "glimmer-gl — The Rotunda (drag to look, arrows to walk)"
          :width 960 :height 600))
