(ns app.core-test
  "Drives app.core over a real nREPL connection: starts the server in-process
  (built-in handler + the library middleware), connects a client, and checks that
  the live state can be read and mutated remotely — the whole point of the
  example. Run with: joltc -M:test"
  (:require [app.core :as core]
            [jolt.nrepl :as server]
            [nrepl.core :as nrepl]
            [nrepl.middleware]))

(def ^:private test-port 7917)

(def failures (atom 0))

(defn check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— expected" (pr-str expected) "got" (pr-str actual)))))

(defn- eval-value
  "Connect-side: eval `code` and return its single read value."
  [t code]
  (-> (nrepl/message t {:op "eval" :code code})
      (nrepl/response-values)
      first))

(defn- run-remote-checks [t]
  (println "nREPL eval round-trips")
  (check "(+ 1 2) => 3" 3 (eval-value t "(+ 1 2)"))

  (println "inspect + mutate live state over the wire")
  (check "led starts off" false (:led (eval-value t "(app.core/status)")))
  (check "toggle-led! => on" true (eval-value t "(app.core/toggle-led!)"))
  (check "status reflects the toggle" true (:led (eval-value t "(app.core/status)")))
  (check "log! returns the new count" 2 (eval-value t "(app.core/log! \"from-test\")"))
  (check "recent-log carries the entry" ["system started" "from-test"]
         (eval-value t "(app.core/recent-log)")))

(defn- run-loop-check []
  (println "background device loop advances state")
  (let [stop-loop (core/start-device-loop!)
        before    (:ticks @core/system)]
    (Thread/sleep 1300)
    (let [after (:ticks @core/system)]
      (stop-loop)
      (check "ticks increased while running" true (> after before)))))

(defn -main [& _]
  (let [stop (server/start test-port ['nrepl.middleware/default-middleware])]
    (try
      (let [t (nrepl/connect "127.0.0.1" test-port)]
        (try
          (run-remote-checks t)
          (run-loop-check)
          (finally (nrepl/close t))))
      (finally (stop)))
    (println)
    (if (zero? @failures)
      (println "all passed")
      (println @failures "FAILED"))
    (when (pos? @failures)
      (throw (ex-info "test failures" {:n @failures})))))
