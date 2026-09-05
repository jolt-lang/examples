(ns app.core
  "The HTTP edge and the boot sequence.

  Routes do one of two things: transact a control change into Domino (and let
  the effects decide what that means), or ask the supervisor for something
  imperative like cancelling an in-flight export. Nothing here computes
  anything about the dashboard itself."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup.core :as h]
            [jolt.datastar.core :as ds]
            [ring-chez.adapter :as adapter]
            [app.bus :as bus]
            [app.diagram :as diagram]
            [app.pipeline :as pipeline]
            [app.state :as state]
            [app.tasks :as tasks]
            [app.ui :as ui]))

(defonce config (atom {}))

;; ------------------------------------------------------------------ helpers

(defn- as-double [v]
  (cond (number? v) (double v)
        (string? v) (or (parse-double v) 0.0)
        :else 0.0))

(defn- as-long [v]
  (cond (number? v) (long v)
        (string? v) (long (or (parse-double v) 0))
        :else 0))

(defn- patch [signals] (ds/patch-signals signals))

(defn- toggle-lane! [k]
  (if (pipeline/running? k)
    (pipeline/stop-lane! k)
    (case k
      :a (pipeline/start-lane-a!)
      :b (pipeline/start-lane-b!)
      :c (pipeline/start-lane-c! (get-in (state/db) [:controls :interval-ms] 500))))
  {:status 200 :headers {"Content-Type" "application/json"} :body "{}"})

;; ------------------------------------------------------------------- routes

(defn- control-route
  "Every slider lands here: coerce, transact, and let Domino's effects turn the
  change into whatever the ingestion or task layer has to do about it."
  [path signal value]
  (state/transact! [[path value]])
  (patch {signal value}))

(defn routes [{:keys [uri jolt.datastar/signals]}]
  (case uri
    "/lanes/a/toggle" (toggle-lane! :a)
    "/lanes/b/toggle" (toggle-lane! :b)
    "/lanes/c/toggle" (toggle-lane! :c)

    "/controls/interval" (control-route [:controls :interval-ms] :interval
                                        (max 100 (as-long (:interval signals))))
    "/controls/window"   (control-route [:controls :window] :window
                                        (max 1 (as-long (:window signals))))
    "/controls/warn"     (control-route [:controls :warn] :warn (as-double (:warn signals)))
    "/controls/crit"     (control-route [:controls :crit] :crit (as-double (:crit signals)))
    "/controls/delay"    (control-route [:controls :consumer-delay] :delay
                                        (max 0 (as-long (:delay signals))))
    "/controls/sink"     (control-route [:controls :sink-failure] :sink
                                        (as-double (:sink signals)))
    "/controls/mute"     (let [muted? (not (get-in (state/db) [:controls :muted?]))]
                           (state/transact! [[[:controls :muted?] muted?]])
                           (patch {:muted muted?}))
    "/controls/probe"    (let [slow? (not (get-in (state/db) [:controls :slow-probe?]))]
                           (state/transact! [[[:controls :slow-probe?] slow?]])
                           (patch {:probe slow?}))

    ;; the model wants a map, the slider signal wants the number back
    "/burst" (let [n (as-long (:burst signals))]
               (state/transact! [[[:burst] {:count n}]])
               (patch {:burst n}))

    ;; requesting an export is state the UI shows, so it goes through the model
    "/export" (do (state/transact! [[[:export] {:state :requested}]])
                  (patch {:export "requested"}))
    ;; cancelling is a command, not state: it goes straight to the supervisor,
    ;; which transacts whatever actually happened
    "/export/cancel" (do (bus/request! {:type :cancel-export})
                         (patch {:export "cancelling"}))

    "/model.mmd" {:status 200
                  :headers {"Content-Type" "text/plain; charset=utf-8"}
                  :body (diagram/mermaid state/schema)}

    "/js/datastar.js" {:status 200
                       :headers {"Content-Type" "application/javascript"}
                       :body (slurp "resources/public/js/datastar.js")}

    {:status 404 :body "not found"}))

(defn app
  "Ring handler. `/` is the page, or just the live fragment when the datastar
  middleware flags the request as the SSE stream."
  [{:keys [uri jolt.datastar/sse-request] :as req}]
  (cond
    (and sse-request (= uri "/")) {:status 200 :body (h/html (ui/fragment true))}
    (= uri "/")                   {:status 200 :body (ui/page)}
    :else                         (routes req)))

;; --------------------------------------------------------------------- boot

(defn start!
  "Bring up the three layers in dependency order: state, then the supervisor
  that drains its requests, then the lanes that feed it."
  [cfg]
  (reset! config cfg)
  (state/init! cfg)
  (tasks/start-dispatcher! cfg)
  (pipeline/start-all! cfg)
  (state/log! :domino "dashboard initialized"))

(defn stop! []
  (pipeline/shutdown!)
  (tasks/shutdown!))

(defn -main [& _]
  (jolt.host/block-sigint)
  (let [cfg     (or (try (edn/read-string (slurp "config.edn")) (catch Exception _ nil)) {})
        port    (:port cfg 3000)
        _       (start! cfg)
        ;; the stream re-renders one fragment, so the rate limit is what the
        ;; page's frame rate actually is; 250ms is smooth without asking a
        ;; browser to morph the whole thing ten times a second
        handler (ds/wrap-datastar app {:rate-limit-ms 250})
        ;; :fibers rather than the default thread-per-connection: every open
        ;; dashboard holds an SSE stream for as long as the tab is open, and a
        ;; worker pool would be fully subscribed by a handful of viewers.
        server  (adapter/run-server handler {:port port :strategy :fibers})]
    (println (str "live pipeline dashboard on http://127.0.0.1:" port))
    (jolt.host/add-shutdown-hook (fn [] (stop!) (adapter/stop-server server)))
    (jolt.host/park-until-interrupt)
    (stop!)
    (adapter/stop-server server)))
