(ns fps-demo.core
  "Phase 2 app: first-person walk-through of the real q1k3 level. A GTK4 window
  with a GLArea runs the q1k3 draw loop; the player spawns at info_player_start,
  WASD accelerates (q1k3 entity_player friction/accel + gravity), Space jumps,
  mouse-motion always looks (mouse-look), and left-click fires the shotgun
  (hitscan: map-trace + enemy AABB, nearest-wins). Collision is the 128^3 voxel
  set from fps-demo.map. Run with `joltc -M:run`; smoke-test headlessly with
  `FPS_DEMO_AUTO_QUIT_MS=3000 joltc -M:run`."
  (:require [glimmer.core   :as ui]
            [glimmer-gl.gtk :as ggtk]
            [fps-demo.render :as render]
            [fps-demo.player :as player]
            [fps-demo.map    :as lvl]
             [fps-demo.entity :as ent]
             [fps-demo.projectile :as proj]
             [fps-demo.particle :as part]
             [fps-demo.pickup :as pkup]
             [fps-demo.capture :as capture]
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

;; The player's spawn as a COLLISION CENTER (info_player_start). player-spawn
;; returns the eye position; the center is eye-offset lower. Both the initial
;; state and respawn use this so death drops you back exactly where you started.
(def ^:private spawn-center
  [(nth spawn-eye 0) (- (nth spawn-eye 1) eye-offset) (nth spawn-eye 2)])

;; Initial enemies: spawned from the real q1k3 map entities (m1) via
;; ent/spawn-from-entities — every grunt/enforcer/ogre/zombie the level declares,
;; at its world position with its patrol direction. Falls back to a single grunt
;; ahead of the player if the map declares none (keeps combat observable).
(defn- spawn-grunts []
  (let [ents (:entities (first (lvl/decode-container lvl-data/bytes)))
        spawned (ent/spawn-from-entities ents)]
    (if (seq spawned)
      spawned
      [(ent/make-grunt [(+ (nth spawn-eye 0) 200.0)
                        (nth spawn-eye 1)
                        (nth spawn-eye 2)]
                       0.0 0)])))

(def ^:private game (atom {:p       spawn-center
                           :v       [0.0 0.0 0.0]
                           :a       [0.0 0.0 0.0]
                           :f       10
                           :on-ground false
                            :yaw     0.0
                            :pitch   0.0
                            :mouse-sens   player/mouse-sens
                            :mouse-invert false
                             :keys    (or (held-seed) #{})
                            :last-x  nil
                            :last-y  nil
                            :fire-request false
                            :cursor-locked? false
                            :health  player/player-max-health
                            :dead?   false
                            :death-time nil
                            :time    0.0
                            :enemies (spawn-grunts)
                            :projectiles []
                            :particles []
                            :lights []
                            :pickups  [(pkup/make-health-pickup
                                         [(nth spawn-eye 0)
                                          (nth spawn-eye 1)
                                          (+ (nth spawn-eye 2) 100.0)])]}))

;; WASD + arrows -> movement keyword; Space jumps.
(def ^:private keyval-kw
  {0xff52 :forward 0xff54 :back 0xff51 :left 0xff53 :right   ; arrows
   0x77 :forward 0x57 :forward                               ; w W
   0x73 :back    0x53 :back                                  ; s S
   0x61 :left    0x41 :left                                  ; a A
   0x64 :right   0x44 :right                                 ; d D
   0x20 :space})

(def ^:const escape-keyval 65307)   ; GDK_KEY_Escape

(defn- on-key [area keyval pressed?]
  ;; Escape unlocks the pointer (restores the cursor). q1k3 exits pointer lock
  ;; on Esc; here it's the blank-cursor lock only (GTK4 has no warp, see #66).
  (when (and pressed? (= (int keyval) escape-keyval))
    (swap! game assoc :cursor-locked? false :last-x nil :last-y nil)
    (ggtk/show-cursor! area)
    (capture/exit!))
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

(defn- on-button [area _btn pressed? _x _y]
  ;; First click locks the pointer (hides the cursor); left button also
  ;; edge-triggers one shotgun shell (cleared by on-tick).
  (swap! game assoc :lmb-down pressed?)
  (when (and pressed? (not (:cursor-locked? @game)))
    (swap! game assoc :cursor-locked? true)
    (ggtk/hide-cursor! area)
    (capture/enter!)
    (capture/delta))                        ; flush any pre-capture accumulator
  (when pressed?
    (swap! game (fn [g] (assoc g :fire-request true :fire-time (:time g))))))

(defn- on-motion [_area x y]
  ;; While the pointer is captured, look is driven by CoreGraphics deltas in
  ;; on-tick (GDK motion goes quiet once the cursor is dissociated), so
  ;; drag-to-look only runs when NOT locked — the free-cursor fallback.
  (when-not (:cursor-locked? @game)
    (swap! game
           (fn [g]
             (let [lx (:last-x g) ly (:last-y g)]
               (if (or (nil? lx) (nil? ly))
                 (assoc g :last-x x :last-y y)
                 (let [dx (- x lx) dy (- y ly)
                       [yaw' pitch'] (player/look (:yaw g) (:pitch g) dx dy
                                                   {:sens (:mouse-sens g)
                                                    :invert (:mouse-invert g)})]
                   (assoc g :yaw yaw' :pitch pitch' :last-x x :last-y y))))))))

(defn- dist3 [a b]
  (let [dx (- (double (nth a 0)) (double (nth b 0)))
        dy (- (double (nth a 1)) (double (nth b 1)))
        dz (- (double (nth a 2)) (double (nth b 2)))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn- explosion-light
  "A transient q1k3 explosion flash (entity_light: bright, fades over ~0.2 s)."
  [pos time]
  {:pos pos :color [64.0 140.0 255.0] :intensity0 200.0
   :spawn time :die-at (+ time 0.2)})

(defn- apply-projectile-events
  "Fold the effect events from proj/step-projectiles into the world. Player-team
  hits/explosions damage enemies (via ent/receive-damage, so idle enemies wake);
  enemy-team hits/explosions accumulate player damage; :fx events request impact
  particles; explosions also emit a transient flash light. Returns
  {:enemies e' :player-dmg d :fx [particles] :lights [flashes]}. `enemies` must be
  the same list step-projectiles saw (a :hit :idx indexes into it)."
  [events enemies player-pos time rng]
  (loop [evs (seq events) enemies enemies pdmg 0.0 fx [] lights []]
    (if-let [ev (first evs)]
      (case (:t ev)
        :hit
        (if (= :player (:team ev))
          (let [i (:idx ev) e (nth enemies i nil)]
            (recur (next evs)
                   (if e (assoc enemies i (ent/receive-damage e (:amount ev) player-pos time rng))
                         enemies)
                   pdmg
                   ;; blood at the struck enemy (q1k3 enemy._receive_damage)
                   (if e (into fx (part/spawn-blood (:pos e) 3 rng)) fx)
                   lights))
          (recur (next evs) enemies (+ pdmg (:amount ev)) fx lights))
        :explode
        (let [lights' (conj lights (explosion-light (:pos ev) time))]
          (if (= :player (:team ev))
            (recur (next evs)
                   (mapv (fn [e]
                           (let [d (proj/splash-damage (dist3 (:pos e) (:pos ev))
                                                       (:radius ev) (:amount ev))]
                             (if (pos? d) (ent/receive-damage e d player-pos time rng) e)))
                         enemies)
                   pdmg fx lights')
            (recur (next evs) enemies
                   (+ pdmg (proj/splash-damage (dist3 player-pos (:pos ev))
                                               (:radius ev) (:amount ev)))
                   fx lights')))
        :fx
        (recur (next evs) enemies pdmg
               (into fx (part/spawn-blood (:pos ev) (:n ev) rng)) lights)
        (recur (next evs) enemies pdmg fx lights))
      {:enemies enemies :player-dmg pdmg :fx fx :lights lights})))

(defn- decay-lights
  "Advance transient flash lights: drop expired ones, and set each survivor's
  current :intensity by linear fade from :intensity0 over [spawn, die-at]."
  [lights time]
  (->> lights
       (keep (fn [l]
               (when (> (:die-at l) time)
                 (assoc l :intensity
                        (* (:intensity0 l)
                           (/ (- (:die-at l) time) (- (:die-at l) (:spawn l))))))))
       vec))

(defn- on-tick [_area]
  ;; Per-frame update. A dead player is frozen (q1k3 player/_kill) and re-spawns
  ;; 2 s later at info_player_start (game_init). While alive: build acceleration
  ;; from held keys and integrate the player; step the enemy squad — each ranged
  ;; enemy that fires this tick spawns a projectile (q1k3 entity_enemy._attack),
  ;; and lunging hounds deal melee contact damage; a pending fire request throws
  ;; 8 shotgun shell projectiles from the eye; then step every projectile against
  ;; the enemies + the player, apply the resulting damage/impact particles, fold
  ;; in pickups, and check for death.
  (swap! game
         (fn [g]
           (if (:dead? g)
             ;; DEAD: only the respawn timer advances. After 2 s, reset to spawn.
             (let [time' (+ (:time g) tick)]
               (if (> (- time' (or (:death-time g) time')) 2.0)
                 (-> (player/respawn-player g spawn-center)
                     (assoc :enemies (spawn-grunts)
                            :projectiles [] :particles [] :lights []
                            :death-time nil :time time'))
                 (assoc g :time time')))
             ;; ALIVE: full per-frame update.
             (let [rng      (fn [] (Math/random))
                   intent   (zipmap [:forward :back :left :right]
                                    (map #(if ((:keys g) %) 1 0) [:forward :back :left :right]))
                   grounded (:on-ground g)
                   [mdx mdy]     (if (:cursor-locked? g) (capture/delta) [0.0 0.0])
                   [yaw' pitch'] (if (:cursor-locked? g)
                                   (player/look (:yaw g) (:pitch g) mdx mdy
                                                {:sens (:mouse-sens g) :invert (:mouse-invert g)})
                                   [(:yaw g) (:pitch g)])
                   a        (player/wish-accel intent yaw' grounded)
                   phys     (player/step-physics
                              cells
                              {:p (:p g) :v (:v g) :a a :f (if grounded 10 2.5)
                               :on-ground grounded}
                              tick)
                   time'    (+ (:time g) tick)
                   pcenter  (:p phys)
                   ;; step enemy AI (collecting the ranged attackers that fired)
                   ;; then resolve hound melee contact against the player.
                   [enemies0 fired]     (ent/step-enemies cells pcenter (:enemies g)
                                                          time' tick rng)
                   [enemies1 melee-dmg] (ent/step-melee enemies0 pcenter player/half)
                   ;; spawn this tick's projectiles: enemy ranged attacks + (on a
                   ;; pending fire request) 8 player shotgun shells from the eye.
                   enemy-shots  (into [] (mapcat #(proj/enemy-attack % pcenter time' rng) fired))
                   eye          [(nth pcenter 0) (+ (nth pcenter 1) eye-offset) (nth pcenter 2)]
                   player-shots (if (:fire-request g)
                                  (proj/player-shotgun eye yaw' pitch' time' rng)
                                  [])
                   projectiles0 (-> (:projectiles g) (into enemy-shots) (into player-shots))
                   ;; fly every projectile one tick against enemies + the player.
                   player-target         {:pos pcenter :half player/half}
                   [projectiles1 events] (proj/step-projectiles cells projectiles0 enemies1
                                                                player-target time' tick)
                   {ev-enemies :enemies proj-dmg :player-dmg fx :fx new-lights :lights}
                   (apply-projectile-events events enemies1 pcenter time' rng)
                   enemies2   (filterv #(not (:dead? %)) ev-enemies)
                   particles' (part/step-particles (into (:particles g) fx) tick)
                   ;; transient flash lights: add this tick's explosions, decay all
                   lights'    (decay-lights (into (:lights g) new-lights) time')
                   base       (-> g
                                  (assoc :yaw yaw' :pitch pitch'
                                         :p pcenter :v (:v phys) :a a
                                         :on-ground (:on-ground phys)
                                         :time time'
                                         :enemies enemies2
                                         :projectiles projectiles1
                                         :particles particles'
                                         :lights lights'
                                         :fire-request false))
                   ;; fold in pickups: heals the player within pickup radius
                   [p3 pickups'] (pkup/apply-pickups base (:pickups base))
                   p4         (assoc p3 :pickups pickups')
                   ;; enemy fire + melee -> hurt; death if health drained
                   total-dmg  (+ melee-dmg proj-dmg)
                   p5         (if (pos? total-dmg) (player/hurt-player p4 total-dmg) p4)
                   p6         (if (<= (:health p5) 0.0)
                                (assoc p5 :dead? true :death-time time')
                                p5)]
               p6)))))

(defn- realize! [area]
  ;; The "realize" signal does NOT make the GL context current (only "render"
  ;; does) — so init!'s GL calls (texture upload, shader compile) must run AFTER
  ;; make-current, or they hit a void context on macOS and build broken objects
  ;; (texture "unloadable", invalid enum, invalid framebuffer op each frame).
  ;; gl_demo's on-realize does the same; this is what makes it work on macOS.
  (ggtk/make-current area)
  (when-let [err (ggtk/gl-area-error-message area)]
    (binding [*out* *err*] (println "[fps-demo] GLArea context error:" err)))
  (reset! render-state (render/init!))
  (binding [*out* *err*] (println "[fps-demo] GL realized; spawn eye:" spawn-eye)))

(defn- resize!  [_area w h] (reset! size [w h]))

(def ^:private render-crash-dumped (atom false))
(def ^:private shot-taken (atom false))
(def ^:private frame-count (atom 0))
(def ^:private frame-t0 (atom nil))
(def ^:private draw-ns-sum (atom 0))

(defn- tick-fps! [draw-ns]
  ;; FPS_DEMO_FPS: print the real render frame rate + avg draw! CPU time every 60
  ;; frames (stderr). Comparing the two says whether we're draw-bound or vsync-idle.
  (when (System/getenv "FPS_DEMO_FPS")
    (let [now (System/nanoTime) n (swap! frame-count inc)]
      (swap! draw-ns-sum + draw-ns)
      (when (nil? @frame-t0) (reset! frame-t0 now))
      (when (>= n 60)
        (let [dt (/ (double (- now @frame-t0)) 1.0e9)]
          (binding [*out* *err*]
            (println (format "[fps-demo] %.1f fps  (%.2f ms/frame, draw! %.2f ms)"
                             (/ 60.0 dt) (* 1000.0 (/ dt 60.0))
                             (/ (double @draw-ns-sum) 60.0 1.0e6)))))
        (reset! frame-count 0)
        (reset! frame-t0 now)
        (reset! draw-ns-sum 0)))))

(defn- render!  [_area]
  (when-let [s @render-state]
    (let [[w h] @size
          g    @game
          ;; camera eye = collision center + eye-offset (eye at top of head)
          eye  [(nth (:p g) 0) (+ (nth (:p g) 1) eye-offset) (nth (:p g) 2)]
          cam  {:eye eye :yaw (:yaw g) :pitch (:pitch g)}
          hud  {:health      (:health g)
                :particles   (:particles g)
                :projectiles (:projectiles g)
                :lights      (:lights g)
                :fire-time   (:fire-time g)
                :time        (:time g)}]
      (try
        (let [t0 (System/nanoTime)]
          (render/draw! s w h cam (:enemies g) hud)
          (tick-fps! (- (System/nanoTime) t0)))
        ;; headless visual check: FPS_DEMO_SHOT=/path dumps one settled frame
        ;; (after the scene has run ~1.5 s so enemies are awake) as raw RGBA.
        (when-let [path (System/getenv "FPS_DEMO_SHOT")]
          (when (and (not @shot-taken)
                     (> (:time g) (or (some-> (System/getenv "FPS_DEMO_SHOT_AT")
                                              Double/parseDouble)
                                      1.5)))
            (reset! shot-taken true)
            (let [[sw sh] (render/save-screenshot! w h path)]
              (binding [*out* *err*] (println (str "[fps-demo] SHOT " sw " " sh " " path))))))
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
  ;; Just the GLArea — no controls row. A [:scale] above the pane was grabbing
  ;; keyboard focus and swallowing the arrow keys; with only the area as the
  ;; root widget, the EventControllerKey on the toplevel window gets them (and
  ;; the area fills the whole window).
  [:gl-area {:hexpand true :vexpand true :halign :fill :valign :fill
             :on-realize realize!
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
