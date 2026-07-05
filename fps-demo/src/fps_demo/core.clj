(ns fps-demo.core
  "Phase 2 app: first-person walk-through of the real q1k3 level. A GTK4 window
  with a GLArea runs the q1k3 draw loop; the player spawns at info_player_start,
  WASD accelerates (q1k3 entity_player friction/accel + gravity), Space jumps,
  mouse-motion always looks (mouse-look), and left-click fires the shotgun
  (hitscan: map-trace + enemy AABB, nearest-wins). Collision is the 128^3 voxel
  set from fps-demo.map. Run with `joltc -M:run`; smoke-test headlessly with
  `FPS_DEMO_AUTO_QUIT_MS=3000 joltc -M:run`."
  (:require [glimmer.core   :as ui]
            [glimmer-gl.gtk]
            [fps-demo.render :as render]
            [fps-demo.player :as player]
            [fps-demo.map    :as lvl]
            [fps-demo.entity :as ent]
            [fps-demo.weapon :as wpn]
            [fps-demo.maps.l :as lvl-data]))

(def ^:private ^:const tick 0.016666667)   ; fixed 1/60 s per frame (q1k3 fixed-step)

(def ^:private render-state (atom nil))
(def ^:private size         (atom [640 480]))

;; Collision voxel set + spawn eye for m1 (the Slipgate Complex), built once.
(def ^:private m1        (first (lvl/decode-container lvl-data/bytes)))
(def ^:private cells      (lvl/solid-cells (:blocks m1)))
(def ^:private spawn-eye  (render/player-spawn))

;; Held-key set (keywords) + drag-to-look state. The game atom carries player
;; physics state {:p :v :a :f :on-ground} plus look angles. FPS_DEMO_HOLD=W
;; pre-seeds a held key so the headless smoke test can prove the full
;; input→accel→physics→position movement chain.
(defn- held-seed []
  (when-let [k (System/getenv "FPS_DEMO_HOLD")]
    (condp = (.toLowerCase ^String k)
      "w" #{:forward} "s" #{:back} "a" #{:left} "d" #{:right} #{})))

(def ^:private eye-offset (nth player/half 1))   ; eye sits half-height above center

;; Initial grunts: one ahead of the spawn so combat is observable. Positioned in
;; world units (same frame as the player :p), on the ground. yaw 0 = +Z forward.
(defn- spawn-grunts []
  [(ent/make-grunt [(+ (nth spawn-eye 0) 200.0)
                    (nth spawn-eye 1)
                    (+ (nth spawn-eye 2) 0.0)]
                   0.0 0)])

(def ^:private game (atom {:p       [(nth spawn-eye 0) (- (nth spawn-eye 1) eye-offset) (nth spawn-eye 2)]
                           :v       [0.0 0.0 0.0]
                           :a       [0.0 0.0 0.0]
                           :f       10
                           :on-ground false
                           :yaw     0.0
                           :pitch   0.0
                            :keys    (or (held-seed) #{})
                            :last-x  nil
                            :last-y  nil
                            :fire-request false
                           :health  player/player-max-health
                           :dead?   false
                           :time    0.0
                           :enemies (spawn-grunts)}))

;; WASD + arrows -> movement keyword; Space jumps.
(def ^:private keyval-kw
  {0xff52 :forward 0xff54 :back 0xff51 :left 0xff53 :right   ; arrows
   0x77 :forward 0x57 :forward                               ; w W
   0x73 :back    0x53 :back                                  ; s S
   0x61 :left    0x41 :left                                  ; a A
   0x64 :right   0x44 :right                                 ; d D
   0x20 :space})

(defn- on-key [_area keyval pressed?]
  (when-let [kw (keyval-kw (int keyval))]
    (if (= :space kw)
      ;; edge-triggered jump: only on press, only from the ground
      (when pressed?
        (swap! game (fn [g]
                      (if (:on-ground g)
                        (assoc g :v (assoc (:v g) 1 player/jump-vel))
                        g))))
      (swap! game (fn [g]
                    (let [ks (if pressed? (conj (:keys g) kw) (disj (:keys g) kw))]
                      (assoc g :keys ks)))))))

(defn- on-button [_area _btn pressed? _x _y]
  ;; Left button press edge-triggers one shotgun shell (cleared by on-tick);
  ;; release just drops the held-down marker.
  (swap! game assoc :lmb-down pressed?)
  (when pressed?
    (swap! game assoc :fire-request true)))

(defn- on-motion [_area x y]
  ;; Always look (drag-to-look dropped in favor of left-click-to-fire): the
  ;; first motion seeds the anchor, subsequent ones yaw/pitch by the delta.
  (swap! game
         (fn [g]
           (let [lx (:last-x g) ly (:last-y g)]
             (if (or (nil? lx) (nil? ly))
               (assoc g :last-x x :last-y y)
               (let [dx (- x lx) dy (- y ly)
                     [yaw' pitch'] (player/look (:yaw g) (:pitch g) dx (- dy))]
                 (assoc g :yaw yaw' :pitch pitch' :last-x x :last-y y)))))))

(defn- on-tick [_area]
  ;; Build this frame's acceleration from held keys + grounded state, integrate,
  ;; then step the enemy squad against the new player position: each enemy that
  ;; fires this tick deals enemy-shot-damage, applied to the player in one fold.
  (swap! game
         (fn [g]
           (let [intent   (zipmap [:forward :back :left :right]
                                  (map #(if ((:keys g) %) 1 0) [:forward :back :left :right]))
                 grounded (:on-ground g)
                 a        (player/wish-accel intent (:yaw g) grounded)
                 phys     (player/step-physics
                            cells
                            {:p (:p g) :v (:v g) :a a :f (if grounded 10 2.5)
                             :on-ground grounded}
                            tick)
                 time'    (+ (:time g) tick)
                 [enemies dmg] (ent/step-enemies cells (:p phys) (:enemies g)
                                                 time' tick (fn [] (Math/random)))
                  p0       (-> g
                               (assoc :p (:p phys) :v (:v phys) :a a
                                      :on-ground (:on-ground phys)
                                      :time time'
                                      :enemies enemies))
                  ;; Consume a pending fire request: raycast from the eye along
                  ;; the view direction, apply shotgun damage to the nearest hit.
                  p1       (if (:fire-request p0)
                             (let [eye [(nth (:p phys) 0)
                                        (+ (nth (:p phys) 1) eye-offset)
                                        (nth (:p phys) 2)]
                                   dir (player/forward (:yaw p0) (:pitch p0))
                                   [en' _] (wpn/fire-shot cells eye dir (:enemies p0)
                                                           time' (fn [] (Math/random)))]
                               (assoc p0 :enemies en' :fire-request false))
                             p0)]
              (if (pos? dmg)
                (player/hurt-player p1 dmg)
                p1)))))

(defn- realize! [_area]
  (reset! render-state (render/init!))
  (binding [*out* *err*] (println "[fps-demo] GL realized; spawn eye:" spawn-eye)))

(defn- resize!  [_area w h] (reset! size [w h]))

(def ^:private render-crash-dumped (atom false))

(defn- render!  [_area]
  (when-let [s @render-state]
    (let [[w h] @size
          g    @game
          ;; camera eye = collision center + eye-offset (eye at top of head)
          eye  [(nth (:p g) 0) (+ (nth (:p g) 1) eye-offset) (nth (:p g) 2)]
          cam  {:eye eye :yaw (:yaw g) :pitch (:pitch g)}]
      (try
        (render/draw! s w h cam (:enemies g))
        (catch Throwable e
          (when (not @render-crash-dumped)
            (reset! render-crash-dumped true)
            (binding [*out* *err*]
              (println "[fps-demo] RENDER CRASH — full state + trace:")
              (println "  enemies:" (pr-str (:enemies g)))
              (println "  exception:" (.toString e))
              (doseq [el (.getStackTrace e)]
                (println "    at" (.toString el)))))
          (throw e))))))

(defn app []
  [:gl-area {:on-realize realize!
             :on-resize  resize!
             :on-render  render!
             :on-tick    on-tick
             :on-key     on-key
             :on-button  on-button
             :on-motion  on-motion}])

(defn- auto-quit-ms []
  (let [v (System/getenv "FPS_DEMO_AUTO_QUIT_MS")]
    (when v
      (try (let [ms (Integer/parseInt v)] (when (pos? ms) ms))
           (catch Exception _ nil)))))

(defn -main [& _]
  (ui/run app
          :app-id "com.joltlang.fps-demo"
          :title  "fps-demo"
          :width  640
          :height 480
          :auto-quit-ms (auto-quit-ms)))
