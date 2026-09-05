(ns app.pipeline
  "The Ebb layer: everything that streams, sleeps, retries or gets cancelled.

  Three lanes run side by side, each demonstrating a different discipline
  against a live source:

    lane A  push, lossy      a 50Hz producer through `m/observe`, relieved.
                             The producer never waits; when the consumer lags,
                             `m/relieve` keeps the newest value and the rest
                             are dropped -- and counted.
    lane B  pull, throttling the same producer consumed demand-driven through
                             `m/via m/blk`. Nothing is dropped; instead the
                             consumer's demand reaches the OS pipe and the
                             producer itself stalls.
    lane C  polled           /proc read on a timer. procfs materializes its
                             files on read, so there is no push to subscribe
                             to and this lane is a poll loop.

  Lane C is the dashboard's metric source: it transacts `[:sample]` and the
  Domino cascade does the rest. Lanes A and B carry the same kind of data but
  exist to be compared, so they keep their own counters instead of competing
  for the sample path.

  Cancellation is real in both directions. `m/observe`'s cleanup destroys the
  child process; for the pull lane, cancelling cannot interrupt a thread
  already parked in `.readLine`, so `stop-lane!` destroys the process itself
  to release it."
  (:require [ebb.core :as m]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [app.metrics :as metrics]
            [app.state :as state]))

(defn- now [] (System/currentTimeMillis))

;; A producer fast enough to overwhelm a browser: one /proc/stat line every
;; 20ms, sequence-numbered so the consumer can tell how far behind real time
;; the producer has fallen. `read -r line < /proc/stat` avoids forking a
;; process per sample; only `sleep` costs a fork.
(def producer-script
  ;; `|| exit 0` is the orphan guard: once our end of the pipe is gone the echo
  ;; fails with EPIPE and the producer exits instead of looping forever on
  ;; broken-pipe errors. It covers the exits a shutdown hook does not -- and on
  ;; this jolt the hook does not run on SIGINT at all.
  "i=0; while :; do i=$((i+1)); read -r line < /proc/stat; echo \"$i $line\" || exit 0; sleep 0.02; done")

(def producer-hz 50)

;; ------------------------------------------------------------ live controls

(defonce ^{:doc "Milliseconds of simulated render work per item. The slider
  that makes lane A drop and lane B stall."}
  consumer-delay-ms (atom 0))

(defonce ^{:doc "When set, lane C's enrichment probe takes seconds instead of
  milliseconds -- see the timeout in `sample-once`."}
  slow-probe? (atom false))

(defonce ^{:doc "Per-lane runtime: canceller, child process handle, counters."}
  lanes (atom {}))

(defn lane [k] (get @lanes k))
(defn running? [k] (some? (:cancel (lane k))))

(defn- pid [proc]
  (try (.pid (:proc proc)) (catch Exception _ nil)))

(defn- kill! [proc]
  (when proc
    (try (p/destroy-tree proc) (catch Exception _ (p/destroy proc)))))

;; ------------------------------------------------------------- the producer

(defn- spawn-producer! [k]
  ;; stderr is discarded: when a lane is cancelled the reader closes and the
  ;; producer's next echo dies with EPIPE, which is expected behaviour and not
  ;; worth logging.
  (let [proc (p/process {:out :stream :err :write :err-file (io/file "/dev/null")}
                        "bash" "-c" producer-script)]
    (swap! lanes assoc-in [k :proc] proc)
    (state/log! :ebb (str "lane " (name k) ": spawned producer pid " (pid proc)))
    proc))

(defn parse-line
  "\"<seq> cpu <jiffies...>\" -> {:seq n :busy n :total n}, or nil for noise."
  [line]
  (let [[n rest-of] (str/split (str/trim (or line "")) #"\s+" 2)]
    (when (and rest-of (str/starts-with? rest-of "cpu"))
      (let [{:keys [aggregate]} (metrics/parse-stat rest-of)]
        (when-let [seq-n (parse-long n)]
          {:seq seq-n :busy (:busy aggregate) :total (:total aggregate)})))))

;; ------------------------------------------------- lane A: push, then relieve

(defn- observed-lines
  "A flow of producer lines, pushed from a reader thread. `m/observe` has no
  backpressure of its own -- an unrelieved consumer would fail -- so the
  caller pairs it with `m/relieve`."
  [k produced]
  (m/observe
   (fn [!]
     (let [proc  (spawn-producer! k)
           rdr   (io/reader (:out proc))
           open? (atom true)
           t     (Thread.
                  (fn []
                    (try
                      (loop []
                        (when @open?
                          (when-let [line (.readLine rdr)]
                            (swap! produced inc)
                            (! line)
                            (recur))))
                      (catch Exception _ nil))))]
       (.start t)
       (fn cleanup []
         (reset! open? false)
         (kill! proc)
         (state/log! :ebb (str "lane " (name k) ": cancelled, destroyed pid " (pid proc))))))))

(defn- lane-a-flow [produced delivered]
  (let [source (m/relieve (fn [_ x] x) (observed-lines :a produced))]
    (m/ap
     (let [line (m/?> source)]
       ;; the simulated render cost: parking here lets the upstream relieve
       ;; collapse values
       (when (pos? @consumer-delay-ms)
         (m/? (m/sleep @consumer-delay-ms)))
       (swap! delivered inc)
       (parse-line line)))))

;; ------------------------------------------ lane B: demand-driven, no drops

(defn- pulled-lines
  "A flow that reads one line per unit of demand. `m/via m/blk` moves the
  blocking read off the flow's thread; because nothing reads ahead, the pipe
  fills and the producer blocks in `write(2)`."
  [k]
  (let [proc (spawn-producer! k)
        rdr  (io/reader (:out proc))]
    (m/ap
     (loop []
       (if-let [line (m/? (m/via m/blk (.readLine rdr)))]
         (m/amb line (recur))
         (m/amb))))))

(defn- lane-b-flow [consumed]
  (m/ap
   (let [line (m/?> (pulled-lines :b))]
     (when (pos? @consumer-delay-ms)
       (m/? (m/sleep @consumer-delay-ms)))
     (swap! consumed inc)
     (parse-line line))))

;; --------------------------------------------------- lane C: the /proc poll

(defn- ticker
  "A flow emitting immediately and then every `interval-ms`."
  [interval-ms]
  (m/ap (loop [] (m/amb (m/? (m/sleep 0 :tick))
                        (do (m/? (m/sleep interval-ms)) (recur))))))

(defn- read-proc []
  {:stat (metrics/parse-stat (slurp "/proc/stat"))
   :mem  (metrics/parse-meminfo (slurp "/proc/meminfo"))
   :net  (metrics/parse-netdev (slurp "/proc/net/dev"))
   :at   (now)})

(defn- probe-task
  "The enrichment step of scenario 4: a dependency that is usually quick and
  occasionally is not."
  []
  (m/sp
   (let [started (now)]
     (m/? (m/sleep (if @slow-probe? 3000 5)))
     {:status :ok :took (- (now) started) :at (now)})))

(defn- derive-sample [prev cur]
  (let [{:keys [cpu cores io-wait]} (metrics/cpu-load (:stat prev) (:stat cur))
        rates (metrics/net-rates (:net prev) (:net cur) (- (:at cur) (:at prev 0)))]
    {:cpu cpu :cores cores :io-wait io-wait
     :mem (:used-frac (:mem cur))
     :mem-used-kb (:used-kb (:mem cur))
     :mem-total-kb (:total-kb (:mem cur))
     :net-rx (:rx-bps rates) :net-tx (:tx-bps rates)
     :ts (:at cur)}))

(defn- lane-c-flow [interval-ms]
  (->> (m/ap (let [_ (m/?> (ticker interval-ms))]
               ;; slurp is a blocking read: keep it off the flow's thread
               (m/? (m/via m/blk (read-proc)))))
       (m/reductions (fn [{:keys [raw]} cur]
                       {:raw cur :sample (when raw (derive-sample raw cur))})
                     {:raw nil :sample nil})
       (m/eduction (keep :sample))))

;; ------------------------------------------------------------- supervision

(defn- start!
  "Run `flow` in the background, remembering how to cancel it."
  [k flow on-value]
  (let [cancel ((m/reduce (fn [_ v] (when v (on-value v)) nil) nil flow)
                (fn [_] (state/log! :ebb (str "lane " (name k) ": completed")))
                (fn [e] (state/log! :ebb (str "lane " (name k) ": "
                                              (if (m/cancelled? e)
                                                "cancelled"
                                                (str "failed -- " (ex-message e)))))))]
    (swap! lanes assoc-in [k :cancel] cancel)
    (state/transact! [[[:lanes k] (merge (get-in (state/db) [:lanes k])
                                         {:running? true :started-at (now)})]])
    cancel))

(defn stop-lane!
  "Cancel the lane and reap its child.

  Both steps are needed: cancelling releases the flow, but a thread parked
  in `.readLine` only wakes when the process it is reading from goes away."
  [k]
  (when-let [{:keys [cancel proc]} (lane k)]
    (when cancel (cancel))
    (kill! proc)
    (swap! lanes update k dissoc :cancel :proc)
    (state/transact! [[[:lanes k] (merge (get-in (state/db) [:lanes k])
                                         {:running? false :pid nil})]])
    (state/log! :ebb (str "lane " (name k) ": stopped"))))

(defn start-lane-a! []
  (when-not (running? :a)
    (let [produced (atom 0) delivered (atom 0) started (now)]
      (swap! lanes update :a merge {:produced produced :delivered delivered
                                    :started started :last nil})
      (start! :a (lane-a-flow produced delivered)
              (fn [row] (swap! lanes assoc-in [:a :last] row))))))

(defn start-lane-b! []
  (when-not (running? :b)
    (let [consumed (atom 0) started (now)]
      (swap! lanes update :b merge {:consumed consumed :started started :last nil})
      (start! :b (lane-b-flow consumed)
              (fn [row] (swap! lanes assoc-in [:b :last] row))))))

(defn start-lane-c! [interval-ms]
  (when-not (running? :c)
    (let [samples (atom 0)]
      (swap! lanes update :c merge {:samples samples :interval interval-ms})
      (start! :c (lane-c-flow interval-ms)
              (fn [sample]
                (swap! samples inc)
                (state/transact! [[[:sample] sample]])))
      ;; the probe rides alongside the sampler rather than inside it, so a slow
      ;; probe can never delay a sample
      (start! :probe
              (m/ap (let [_ (m/?> (ticker (max 250 interval-ms)))]
                      (m/? (m/timeout (probe-task) 250 {:status :stale :at (now)}))))
              (fn [res] (state/transact! [[[:probe] res]]))))))

(defn retune-lane-c!
  "Changing the interval restarts the lane rather than setting a flag the
  loop checks."
  [interval-ms]
  (state/log! :ebb (str "lane c: retune to " interval-ms "ms -- cancel and respawn"))
  (stop-lane! :c)
  (stop-lane! :probe)
  (start-lane-c! interval-ms))

;; --------------------------------------------------------------- telemetry

(defn lane-stats
  "What the lanes look like right now, as plain data for the UI."
  []
  (let [{:keys [a b]} @lanes
        elapsed  (fn [t] (max 1 (- (now) (or t (now)))))
        produced @(:produced a (atom 0))
        delivered @(:delivered a (atom 0))
        consumed @(:consumed b (atom 0))
        b-seq    (:seq (:last b) 0)
        free-run (long (* producer-hz (/ (elapsed (:started b)) 1000.0)))]
    {:a {:running? (running? :a) :pid (pid (:proc a))
         :produced produced :delivered delivered
         :dropped (max 0 (- produced delivered))
         :last (:last a)}
     :b {:running? (running? :b) :pid (pid (:proc b))
         :consumed consumed :producer-seq b-seq
         :free-running free-run
         :stalled-by (max 0 (- free-run b-seq))
         :last (:last b)}}))

(defn- telemetry-flow [interval-ms]
  (m/ap (let [_ (m/?> (ticker interval-ms))]
          (lane-stats))))

(defn start-publisher!
  "Mirror the model into the render cell at a rate a browser can absorb.

  The pipeline writes the model as fast as the machine produces samples; this
  is the only thing that touches the reactive cell, and it is wrapped so that a
  misbehaving SSE watcher can stall the view without ever reaching ingestion."
  [hz]
  (when-not (running? :publisher)
    (start! :publisher
            (m/ap (let [_ (m/?> (ticker (long (/ 1000 hz))))] :tick))
            (fn [_]
              (try
                (state/publish!)
                (catch Exception e
                  (state/log! :error (str "publish failed: " (ex-message e)))))))))

(defn start-telemetry! []
  (when-not (running? :telemetry)
    (start! :telemetry (telemetry-flow 250)
            (fn [{:keys [a b]}]
              (state/transact! [[[:lanes :a] (merge (get-in (state/db) [:lanes :a]) a)]
                                [[:lanes :b] (merge (get-in (state/db) [:lanes :b]) b)]])))))

;; ------------------------------------------------------------------- boot

(defn start-all! [config]
  (start-lane-c! (:sample-interval-ms config 500))
  (start-lane-a!)
  (start-lane-b!)
  (start-telemetry!)
  (start-publisher! (:publish-hz config 10)))

(defn shutdown! []
  (doseq [k [:publisher :telemetry :probe :c :b :a]]
    (stop-lane! k))
  (state/log! :ebb "pipeline shut down"))
