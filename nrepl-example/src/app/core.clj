(ns app.core
  "A compiled jolt program that starts an nREPL server in a background thread and
  keeps running, so you can connect from your editor (CIDER / Calva / Cursive)
  and drive the live process. Think a binary deployed to a Raspberry Pi: it runs
  its loop unattended, and you reach in over nREPL to read sensors, flip outputs,
  reload code, or call functions — no SSH + restart cycle.

  Run interpreted:
    joltc run -m app.core
  Or build a standalone binary, then run it:
    joltc build -m app.core -o nrepl-example
    ./nrepl-example [port]

  It listens on 127.0.0.1:7888 (override with a port arg or JOLT_NREPL_PORT),
  writes .nrepl-port, and parks until ^C. Connect an editor to that port and try:
    (app.core/status)        ; snapshot of live state
    (app.core/read-sensor)   ; latest simulated reading
    (app.core/toggle-led!)   ; flip the output, returns the new state
    (app.core/log! \"hi\")    ; append to the on-device log
    (app.core/recent-log)    ; last few log entries"
  (:require [jolt.nrepl :as nrepl]
            ;; required (not aliased) so the build AOT-compiles it into the
            ;; binary; app.core passes its middleware var to jolt.nrepl/start.
            [nrepl.middleware]))

(def ^:private default-port 7888)
(def ^:private tick-ms 1000)

;; The live \"device\" state. defonce so reloading this namespace from a connected
;; editor doesn't wipe whatever the running loop has accumulated.
(defonce system
  (atom {:ticks  0
         :led    false
         :sensor 0.0
         :log    ["system started"]}))

(defn status
  "A snapshot of the live device state (omits the log)."
  []
  (-> @system (dissoc :log) (into (sorted-map))))

(defn read-sensor
  "The latest simulated sensor reading."
  []
  (:sensor @system))

(defn toggle-led!
  "Flip the simulated output; returns the new state."
  []
  (let [v (not (:led @system))]
    (swap! system assoc :led v)
    v))

(defn set-led!
  "Set the simulated output to `on?`; returns the new state."
  [on?]
  (swap! system assoc :led (boolean on?))
  (boolean on?))

(defn log!
  "Append `msg` to the on-device log (capped at 50 entries); returns the new count."
  [msg]
  (swap! system update :log (fn [xs] (-> xs (conj msg) (->> (take-last 50) vec))))
  (count (:log @system)))

(defn recent-log
  "The last `n` log entries, newest last (default 10)."
  ([]
   (recent-log 10))
  ([n]
   (->> (:log @system) (take-last n) vec)))

;; A smooth simulated reading in [0, 100): 50 + 50*sin(i/2).
(defn- sensor-reading [i]
  (double (+ 50.0 (* 50.0 (Math/sin (* i 0.5))))))

(defn start-device-loop!
  "Start a background thread that advances the simulated device every second
  (ticks a counter and updates the sensor reading). Returns a zero-arg stop fn."
  []
  (let [stopped (atom false)]
    (future
      (loop [i 0]
        (when-not @stopped
          (swap! system assoc :ticks i :sensor (sensor-reading i))
          (Thread/sleep tick-ms)
          (recur (inc i)))))
    (fn stop [] (reset! stopped true) :stopped)))

(defn- resolve-port [args]
  (or (some-> (first args) parse-long)
      (parse-long (or (jolt.host/getenv "JOLT_NREPL_PORT")
                      (str default-port)))))

(defn start-nrepl!
  "Start the nREPL server with the session/completion/lookup middleware, on a
  background thread. Mirrors `joltc nrepl`: block SIGINT on this thread first so
  ^C lands here (not on the server's accept loop, which is parked in a foreign
  recv), bind the socket (a port-in-use error throws here), then register stop as
  a shutdown hook so ^C tears it down cleanly. Returns the stop fn."
  [port]
  (jolt.host/block-sigint)
  (let [stop (nrepl/start port ['nrepl.middleware/default-middleware])]
    (jolt.host/add-shutdown-hook stop)
    stop))

(defn -main [& args]
  (let [port (resolve-port args)]
    (println "nrepl-example — a jolt app you can drive live over nREPL")
    (println "  state: app.core/system   api: status, read-sensor, toggle-led!,")
    (println "                               set-led!, log!, recent-log")
    (println)
    (let [stop-server (start-nrepl! port)
          stop-loop   (start-device-loop!)]
      (jolt.host/add-shutdown-hook stop-loop)
      ;; Park the main thread until ^C. The server and device loop run on worker
      ;; threads; the shutdown hooks above close them on the way out. Returning
      ;; here would let the launcher exit and tear the whole process down.
      (jolt.host/park-until-interrupt)
      ;; Reached only if something interrupts the park without running hooks.
      (stop-server)
      (stop-loop))))
