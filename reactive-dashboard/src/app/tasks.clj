(ns app.tasks
  "The effectful half of the integration pattern.

  A Domino effect never performs IO; it posts a request onto `app.bus`. One
  supervisor fiber drains that mailbox and turns each request into an Ebb
  task, and every task transacts its own result back into Domino. So the
  round trip is

      state change -> effect -> mailbox -> ebb task -> state change

  and each half stays in its own world: Domino handlers are pure and
  synchronous, retries and cancellation live here."
  (:require [ebb.core :as m]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [app.bus :as bus]
            [app.state :as state]
            [app.pipeline :as pipe]))

(defn- now [] (System/currentTimeMillis))

(defonce ^{:doc "Cancellers for tasks that outlive their request."}
  in-flight (atom {}))

(defonce ^{:doc "Load-generator processes spawned by the burst control."}
  spinners (atom []))

(defn- spawn-task!
  "Run `task` in the background under `k`, cancelling any previous one."
  [k task on-error]
  (when-let [cancel (get @in-flight k)] (cancel))
  (let [cancel (task (fn [_] (swap! in-flight dissoc k))
                     (fn [e] (swap! in-flight dissoc k) (on-error e)))]
    (swap! in-flight assoc k cancel)
    cancel))

(defn cancel-task! [k]
  (when-let [cancel (get @in-flight k)]
    (cancel)
    (swap! in-flight dissoc k)
    true))

;; ------------------------------------------------------- alerts, with retry

(defn- sink-failure-rate []
  (or (get-in (state/db) [:controls :sink-failure]) 0.0))

(defn- deliver-once
  "The notification sink: a task that takes a moment and sometimes refuses.
  The failure rate is a control on the dashboard, so retries can be exercised
  deterministically."
  [level attempt]
  (m/sp
    (m/? (m/sleep 150))
    (when (< (rand) (sink-failure-rate))
      (throw (ex-info "notification sink unavailable"
                      {:level level :attempt attempt})))
    {:level level :at (now)}))

(defn alert-task
  "Deliver an alert, retrying with linear backoff. Every attempt is written
  back into the model, which is what puts `attempt 3/5` on the screen."
  [level max-attempts]
  (m/sp
    (loop [attempt 1]
      (state/transact! [[[:alert :delivery]
                         {:status :sending :level level
                          :attempt attempt :max max-attempts :at (now)}]])
      (let [outcome (m/? (m/attempt (deliver-once level attempt)))]
        (if-let [err (try (outcome) nil (catch Exception e e))]
          (cond
            ;; a cancel is not a sink failure: the alert cleared (or a newer
            ;; one replaced this task) and there is nothing left to retry
            (m/cancelled? err)
            (do (state/log! :ebb (str "alert " (name level) ": delivery cancelled"))
                (throw err))

            (< attempt max-attempts)
            (do
              (state/log! :ebb (str "alert " (name level) ": attempt " attempt
                                    " failed (" (ex-message err) "), retrying"))
              (state/transact! [[[:alert :delivery]
                                 {:status :retrying :level level
                                  :attempt attempt :max max-attempts
                                  :error (ex-message err) :at (now)}]])
              (m/? (m/sleep (* 200 attempt)))
              (recur (inc attempt)))

            :else
            (do
              (state/log! :ebb (str "alert " (name level) ": giving up after "
                                    attempt " attempts"))
              (state/transact! [[[:alert :delivery]
                                 {:status :failed :level level
                                  :attempt attempt :max max-attempts
                                  :error (ex-message err) :at (now)}]])))
          (do
            (state/log! :ebb (str "alert " (name level) ": delivered on attempt " attempt))
            (state/transact! [[[:alert :delivery]
                               {:status :delivered :level level
                                :attempt attempt :max max-attempts :at (now)}]])))))))

;; --------------------------------------------------------------- the export

(defn- csv-rows [history]
  (cons "ts,cpu,mem,net_bytes_per_s,io_wait"
        (map (fn [{:keys [ts cpu mem net io-wait]}]
               (str ts "," cpu "," mem "," net "," io-wait))
             history)))

(defn export-task
  "Write the retained history to disk in chunks.

  The task parks between chunks, so a cancel lands mid-write and the file is
  still consistent: it writes to a `.part` file and renames on success, so a
  cancelled export never leaves a partial file under the final name."
  [dir]
  (m/sp
    (let [rows  (vec (csv-rows (:history (state/db))))
          stamp (now)
          path  (str dir "/dashboard-" stamp ".csv")
          part  (str path ".part")
          total (count rows)]
      (io/make-parents part)
      (state/transact! [[[:export] {:state :running :path path :written 0 :total total}]])
      (state/log! :ebb (str "export: writing " total " rows to " path))
      (try
        (with-open [w (io/writer part)]
          (loop [remaining rows written 0]
            (if-let [chunk (seq (take 20 remaining))]
              (do
                (doseq [line chunk] (.write w (str line "\n")))
                (.flush w)
                (let [written (+ written (count chunk))]
                  (state/transact! [[[:export] {:state :running :path path
                                                :written written :total total}]])
                  ;; the pause a real export would spend on IO -- and the
                  ;; point where a cancel becomes observable
                  (m/? (m/sleep 250))
                  (recur (drop 20 remaining) written)))
              nil)))
        (.renameTo (io/file part) (io/file path))
        (state/transact! [[[:export] {:state :done :path path :total total
                                      :written total :at (now)}]])
        (state/log! :ebb (str "export: finished " path))
        (catch Exception e
          (.delete (io/file part))
          (if (m/cancelled? e)
            (do (state/log! :ebb "export: cancelled, partial file removed")
                (state/transact! [[[:export] {:state :cancelled :path path :at (now)}]]))
            (do (state/log! :error (str "export failed: " (ex-message e)))
                (state/transact! [[[:export] {:state :failed :error (ex-message e)}]])))
          (throw e))))))

;; ------------------------------------------------------- the load generator

(def ^:private spinner-script
  ;; A spinner writes nothing, so unlike the producers it gets no EPIPE when
  ;; this process dies. It instead holds our stdin pipe open and waits on it:
  ;; when we exit the pipe reaches EOF, `read` returns, and the wrapper kills
  ;; the load it started. No dependence on shutdown hooks.
  "yes > /dev/null & p=$!; read -r _ || true; kill $p 2>/dev/null")

(defn- spawn-spinner! []
  (p/process {:in :stream :out :write :out-file (io/file "/dev/null")
              :err :write :err-file (io/file "/dev/null")}
             "bash" "-c" spinner-script))

(defn set-burst!
  "Match the number of running spinners to `n`. These are real processes
  consuming real CPU, so the load the dashboard then reports is not
  simulated."
  [n]
  (let [n       (max 0 (min 8 (or n 0)))
        current (count @spinners)]
    (cond
      (> n current) (let [added (repeatedly (- n current) spawn-spinner!)]
                      (swap! spinners into added)
                      (state/log! :ebb (str "burst: spawned " (count added)
                                            " spinner(s), now " n)))
      (< n current) (let [[keep drop-them] (split-at n @spinners)]
                      (doseq [proc drop-them]
                        (try (p/destroy-tree proc) (catch Exception _ (p/destroy proc))))
                      (reset! spinners (vec keep))
                      (state/log! :ebb (str "burst: stopped " (count drop-them)
                                            " spinner(s), now " n)))
      :else nil)))

;; ------------------------------------------------------------- the dispatch

(defn handle!
  "One request from the bus. Long-running work is spawned, never awaited: the
  supervisor must stay free to take the next request."
  [config req]
  (case (:type req)
    :alert
    (if (= :ok (:level req))
      (when (or (cancel-task! :alert) (get-in (state/db) [:alert :delivery]))
        (state/log! :ebb "alert: recovered, delivery stood down")
        (state/transact! [[[:alert :delivery] nil]]))
      (spawn-task! :alert
                   (alert-task (:level req) (:alert-max-attempts config 5))
                   (fn [e] (when-not (m/cancelled? e)
                             (state/log! :error (str "alert task: " (ex-message e)))))))

    :export
    (spawn-task! :export
                 (export-task (:export-dir config "target/exports"))
                 (fn [_] nil))

    :cancel-export
    (when-not (cancel-task! :export)
      (state/log! :ebb "export: nothing in flight to cancel"))

    :burst
    (set-burst! (:count req))

    :retune
    (pipe/retune-lane-c! (:interval-ms req))

    :consumer-delay
    (do (reset! pipe/consumer-delay-ms (or (:ms req) 0))
        (state/log! :ebb (str "consumer delay set to " (:ms req) "ms")))

    :slow-probe
    (do (reset! pipe/slow-probe? (boolean (:on? req)))
        (state/log! :ebb (str "slow probe " (if (:on? req) "enabled" "disabled"))))

    (state/log! :error (str "unknown request: " (pr-str req)))))

(defn- drain-task
  "Take one request at a time and handle it before taking the next.

  An `m/sp` loop rather than `m/ap` over the mailbox: `m/amb` branches
  overlap, and a supervisor that can process a recovery before the alert it
  is meant to supersede has a race in it."
  [config]
  (m/sp
    (loop []
      (let [req (m/? bus/requests)]
        (try
          (handle! config req)
          (catch Exception e
            (when (m/cancelled? e) (throw e))
            (state/log! :error (str "dispatch failed: " (ex-message e)))))
        (recur)))))

(defn start-dispatcher!
  "Drain the bus forever. Returns the canceller."
  [config]
  (when-not (get @in-flight :dispatcher)
    (let [task (drain-task config)
          cancel (task (fn [_] nil)
                       (fn [e] (when-not (m/cancelled? e)
                                 (state/log! :error (str "dispatcher died: " (ex-message e))))))]
      (swap! in-flight assoc :dispatcher cancel)
      (state/log! :ebb "supervisor: draining the request bus")
      cancel)))

(defn shutdown! []
  (set-burst! 0)
  (doseq [[k cancel] @in-flight]
    (when (fn? cancel) (cancel))
    (swap! in-flight dissoc k))
  (state/log! :ebb "tasks shut down"))
