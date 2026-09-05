(ns app.state
  "The Domino layer: the whole application's logic as a data-flow graph.

  Nothing in this namespace performs IO, spawns a fiber or reads a clock. A
  sample arrives as one `transact`, and Domino runs the cascade

      raw sample -> window stats -> pressure index -> alert level

  in a single pass. Effects at the edge only ever post a request onto
  `app.bus`; the async work happens in `app.tasks`.

  Three things shape the schema below, all learned from the engine rather than
  guessed:

  1. Domino executes an event once per changed input path, not once per
     transaction. Handlers must therefore be idempotent -- see
     `:record-history`, which is keyed on the sample's timestamp, and
     `:classify-alert`, which only appends on an actual transition. The sample
     itself is one model path holding one map, so ingestion is one change.
  2. Effects run only when their inputs actually changed, which is what makes
     `:announce-alert` fire on transitions instead of on every sample.
  3. Interceptors are collected from an event's :inputs, so a :pre/:post on a
     model path wraps the events that READ that path, not the ones that write
     it.

  The live context sits in a glimmer ratom, so every SSE-connected page
  re-renders when it changes."
  (:require [domino.core :as domino]
            [glimmer.ratom :as ratom]
            [app.bus :as bus]))

;; ------------------------------------------------------------------ helpers

(def ^:private history-cap
  "Hard cap on retained samples. The :window control selects how many of these
  the stats look at, which is why the window slider is pure recomputation and
  never touches the ingestion lanes."
  300)

(defn- summarize
  "Window of numbers -> {:last :avg :min :max :n}."
  [xs]
  (if (seq xs)
    {:last (double (last xs))
     :avg  (/ (reduce + 0.0 xs) (count xs))
     :min  (double (apply min xs))
     :max  (double (apply max xs))
     :n    (count xs)}
    {:last 0.0 :avg 0.0 :min 0.0 :max 0.0 :n 0}))

(defn- clamp [x] (-> (or x 0.0) double (max 0.0) (min 1.0)))

;; --------------------------------------------------------------- interceptors

(defn- clamp-pressure
  "A :post interceptor, hung on [:stats] because that is what
  `:compute-pressure` reads. The weights are meant to sum to 1, but a composite
  index is exactly the kind of number that quietly drifts out of range when
  someone retunes it, so the model itself keeps it in [0,1]."
  [handler]
  (fn [result]
    (handler (update result :pressure clamp))))

(defn- unless-muted
  "A :pre interceptor, hung on [:pressure] because that is what
  `:classify-alert` reads. Returning nil short-circuits the handler, so the
  alert level simply stops moving while muted -- enforced by the model rather
  than by every caller remembering to check."
  [handler]
  (fn [ctx inputs outputs]
    (when-not (get-in ctx [::domino/db :controls :muted?])
      (handler ctx inputs outputs))))

;; --------------------------------------------------------------------- model

(def model
  [;; one path, one map: a sample is a single change, so the cascade runs once
   [:sample   {:id :sample}]
   [:history  {:id :history}]
   ;; Interceptors attach to the paths an event READS: domino collects them
   ;; from each event's :inputs, so :post here wraps :compute-pressure (which
   ;; reads :stats) and :pre on [:pressure] wraps :classify-alert.
   [:stats    {:id :stats :post [clamp-pressure]}]
   [:pressure {:id :pressure :pre [unless-muted]}]
   [:alert
    [:level    {:id :alert-level}]
    [:since    {:id :alert-since}]
    [:delivery {:id :alert-delivery}]
    [:log      {:id :alert-log}]]
   [:controls
    [:window         {:id :window}]
    [:warn           {:id :warn-threshold}]
    [:crit           {:id :crit-threshold}]
    [:interval-ms    {:id :interval-ms}]
    [:consumer-delay {:id :consumer-delay}]
    [:sink-failure   {:id :sink-failure}]
    [:slow-probe?    {:id :slow-probe?}]
    [:muted?         {:id :muted?}]]
   [:lanes
    [:a {:id :lane-a}]
    [:b {:id :lane-b}]
    [:c {:id :lane-c}]]
   [:probe  {:id :probe}]
   [:export {:id :export}]
   [:burst  {:id :burst}]])

;; -------------------------------------------------------------------- events

(def events
  [{:id      :record-history
    :doc     "Append the sample to the series. Keyed on :ts: re-running the
              handler for the same sample overwrites its slot instead of
              appending twice, and a sample-less db (ts 0) records nothing."
    :inputs  [:sample]
    :outputs [:history]
    :handler (fn [_ {:keys [sample]} {:keys [history]}]
               (let [{:keys [ts cpu mem net-rx net-tx io-wait]} sample
                     entry {:ts ts :cpu (clamp cpu) :mem (clamp mem)
                            :net (+ (or net-rx 0.0) (or net-tx 0.0))
                            :io-wait (clamp io-wait)}
                     series (vec history)]
                 {:history
                  (cond
                    (or (nil? ts) (zero? ts))     series
                    (= ts (:ts (peek series)))    (conj (pop series) entry)
                    :else (let [s (conj series entry)]
                            (if (> (count s) history-cap)
                              (subvec s (- (count s) history-cap))
                              s)))}))}

   {:id      :compute-stats
    :doc     "Window stats. :window is an input, so moving the window slider
              re-runs this event (and everything downstream) with no help from
              the ingestion layer."
    :inputs  [:history :window]
    :outputs [:stats]
    :handler (fn [_ {:keys [history window]} _]
               (let [w      (max 1 (or window 60))
                     recent (take-last w history)]
                 {:stats {:cpu (summarize (map :cpu recent))
                          :mem (summarize (map :mem recent))
                          :net (summarize (map :net recent))
                          :io-wait (summarize (map :io-wait recent))}}))}

   {:id      :compute-pressure
    :doc     "One composite index the alert logic can be stated against:
              sustained CPU, current memory, and IO wait as a tie-breaker."
    :inputs  [:stats]
    :outputs [:pressure]
    :handler (fn [_ {:keys [stats]} _]
               {:pressure (+ (* 0.55 (get-in stats [:cpu :avg] 0.0))
                             (* 0.35 (get-in stats [:mem :last] 0.0))
                             (* 0.10 (get-in stats [:io-wait :last] 0.0)))})}

   {:id      :classify-alert
    :doc     "Pressure against the two thresholds. Deliberately blind to the
              sample: an input that changes earlier in the cascade would make
              the engine re-run this handler with a stale pressure and clobber
              the level it just computed."
    :inputs  [:pressure :warn-threshold :crit-threshold]
    :outputs [:alert-level]
    :handler (fn [_ {:keys [pressure warn-threshold crit-threshold]} _]
               {:alert-level (cond
                               (>= pressure (or crit-threshold 0.85)) :critical
                               (>= pressure (or warn-threshold 0.55)) :warn
                               :else                                  :ok)})}

   {:id      :stamp-alert
    :doc     "Timestamps the transition and logs it. Both inputs change during
              a sample cascade, so the handler is written to converge in
              either execution order: a level already at the head of the log
              is a no-op."
    :inputs  [:alert-level :history]
    :outputs [:alert-since :alert-log]
    :handler (fn [_ {:keys [alert-level history]} {:keys [alert-since alert-log]}]
               (let [ts   (:ts (last history) 0)
                     head (last alert-log)]
                 (if (= alert-level (:level head))
                   {:alert-since (or (:at head) alert-since) :alert-log alert-log}
                   {:alert-since ts
                    :alert-log   (vec (take-last 20 (conj (vec alert-log)
                                                          {:level alert-level :at ts})))})))}])

;; ------------------------------------------------------------------- effects

(def effects
  [{:id      :announce-alert
    :doc     "Alert transitions ask for delivery. Effects run only when their
              inputs actually changed, so this fires on transitions, not on
              every sample."
    :inputs  [:alert-level]
    :handler (fn [_ {:keys [alert-level]}]
               (bus/request! {:type :alert :level alert-level}))}

   {:id      :dispatch-export
    :inputs  [:export]
    :handler (fn [_ {:keys [export]}]
               (when (= :requested (:state export))
                 (bus/request! {:type :export})))}

   {:id      :dispatch-burst
    :inputs  [:burst]
    :handler (fn [_ {:keys [burst]}]
               (bus/request! {:type :burst :count (:count burst 0)}))}

   {:id      :dispatch-retune
    :doc     "Changing the sample interval is a restart, not a flag: the effect
              asks the supervisor to cancel lane C and spawn it again."
    :inputs  [:interval-ms]
    :handler (fn [_ {:keys [interval-ms]}]
               (bus/request! {:type :retune :interval-ms interval-ms}))}

   {:id      :dispatch-consumer-delay
    :inputs  [:consumer-delay]
    :handler (fn [_ {:keys [consumer-delay]}]
               (bus/request! {:type :consumer-delay :ms consumer-delay}))}

   {:id      :dispatch-slow-probe
    :inputs  [:slow-probe?]
    :handler (fn [_ {:keys [slow-probe?]}]
               (bus/request! {:type :slow-probe :on? (boolean slow-probe?)}))}])

(def schema {:model model :events events :effects effects})

;; ---------------------------------------------------------------- initial db

(defn initial-db [config]
  {:sample   {:cpu 0.0 :cores [] :mem 0.0 :net-rx 0.0 :net-tx 0.0 :io-wait 0.0 :ts 0}
   :history  []
   :stats    {}
   :pressure 0.0
   :alert    {:level :ok :since 0 :delivery nil :log []}
   :controls {:window         (:window config 60)
              :warn           (:warn-threshold config 0.55)
              :crit           (:crit-threshold config 0.85)
              :interval-ms    (:sample-interval-ms config 500)
              :consumer-delay (:consumer-delay-ms config 0)
              :sink-failure   (:alert-sink-failure-rate config 0.4)
              :slow-probe?    false
              :muted?         false}
   :lanes    {:a {:label "50Hz producer · observe → relieve" :mode :push :running? false
                  :produced 0 :delivered 0 :dropped 0 :pid nil :last nil}
              :b {:label "50Hz producer · demand-driven read" :mode :pull :running? false
                  :consumed 0 :producer-seq 0 :free-running 0 :stalled-by 0
                  :pid nil :last nil}
              :c {:label "/proc · polled sampler" :mode :poll :running? false
                  :samples 0}}
   :probe    {:status :off}
   :export   {:state :idle}
   :burst    {:count 0}})

;; ------------------------------------------------------------- the live cell

;; The authoritative context is a plain atom, and exactly one glimmer ratom --
;; `view` -- is published from it on a timer. That split is deliberate.
;;
;; A glimmer write notifies every watcher synchronously in the writing thread,
;; and each SSE connection registers a watcher per render. A stream whose
;; client vanished leaves its watcher behind (the datastar middleware only
;; unregisters it when its channel closes, which for an abandoned stream never
;; happens), so writes get progressively more expensive and eventually throw
;; out of the notify path -- measured here as "Exception in fork-thread:
;; failed: Resource temporarily unavailable" after 50 dead watchers and a few
;; thousand writes. Ingestion writes far more often than any browser can
;; render, so it has no business paying that cost: the pipeline writes to the
;; atom at whatever rate the machine produces samples, and the publisher
;; mirrors a consistent snapshot to the view at a rate a browser can absorb.

(defonce ^{:doc "Authoritative domino context. Not reactive on purpose."}
  ctx (atom nil))

(defonce ^:private log-entries (atom []))

(defonce ^{:doc "The single reactive cell the SSE renders subscribe to: one
  consistent snapshot of everything the page displays."}
  view (ratom/atom {:db nil :cascade [] :log []}))

(defn db [] (::domino/db @ctx))

(defn change-history
  "The paths the last transaction actually wrote, in execution order. This is
  the cascade, straight from the engine."
  []
  (::domino/change-history @ctx))

(defn log!
  "Append one line to the visible event log. `kind` is :domino, :ebb or :error."
  [kind text]
  (swap! log-entries
         (fn [entries]
           (vec (take-last 60 (conj (vec entries)
                                    {:kind kind :text text
                                     :at (System/currentTimeMillis)})))))
  nil)

(defn event-log [] @log-entries)

(defn publish!
  "Mirror the authoritative state into the view. One caller, on a timer."
  []
  (ratom/reset! view {:db (db) :cascade (change-history) :log @log-entries})
  nil)

(def ^:private write-lock (Object.))

(defn transact!
  "Serialize a transaction against the live context.

  Several ebb lanes plus the request threads all write here, so the lock is
  doing real work. Effects fire inside `domino/transact`, and their requests
  are collected rather than posted: posting drives the supervisor fiber inline
  and it would immediately ask for this very lock. They go out below, once the
  lock is released. See `app.bus`."
  [changes]
  (let [pending (atom [])
        result  (locking write-lock
                  (let [before @ctx]
                    (try
                      (let [after (binding [bus/*collector* pending]
                                    (domino/transact before changes))]
                        (reset! ctx after)
                        after)
                      (catch Exception e
                        (log! :error (str "transaction failed: " (ex-message e)))
                        before))))]
    (bus/post-all! @pending)
    result))

(defn init!
  "Build the engine and publish it. Safe to call again at the REPL."
  [config]
  (reset! ctx (domino/initialize schema (initial-db config)))
  (reset! log-entries [])
  (publish!)
  @ctx)
