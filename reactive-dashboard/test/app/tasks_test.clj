(ns app.tasks-test
  "The effect -> mailbox -> task -> state round trip."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as p]
            [ebb.core :as m]
            [app.bus :as bus]
            [app.state :as state]
            [app.pipeline :as pipe]
            [app.tasks :as tasks]))

(def export-dir "target/test-exports")
(def config {:alert-max-attempts 3 :export-dir export-dir})

(defn- take-request
  "Next request off the bus, or ::none within `ms`."
  [ms]
  (m/? (m/timeout (m/sp (m/? bus/requests)) ms ::none)))

(defn- drain-bus! []
  (loop [] (when-not (= ::none (take-request 50)) (recur))))

(defn- pause [ms] (m/? (m/sleep ms)))

(defn- yes-processes []
  (-> (p/shell {:out :string :continue true} "bash" "-c" "ps -eo args | grep -c '^yes$'")
      :out str/trim parse-long))

(use-fixtures :each
  (fn [f]
    (state/init! {:window 200 :alert-sink-failure-rate 0.0})
    (drain-bus!)
    (try (f)
         (finally
           (tasks/shutdown!)
           ;; the :retune handler starts lane C, so clear the pipeline too
           (pipe/shutdown!)
           (drain-bus!)))))

(deftest effects-post-requests-instead-of-performing-them
  (testing "an alert transition asks for delivery rather than delivering"
    (state/transact! [[[:sample] {:cpu 1.0 :mem 1.0 :io-wait 1.0 :net-rx 0.0 :net-tx 0.0 :ts 1000}]])
    (is (= :critical (get-in (state/db) [:alert :level])))
    (is (= {:type :alert :level :critical} (take-request 200)))
    (testing "and nothing happened yet: no dispatcher is running"
      (is (nil? (get-in (state/db) [:alert :delivery])))))
  (testing "control changes post too"
    (state/transact! [[[:controls :interval-ms] 250]])
    (is (= {:type :retune :interval-ms 250} (take-request 200)))))

(deftest alert-delivery-retries-then-gives-up
  (state/transact! [[[:controls :sink-failure] 1.0]])
  (tasks/start-dispatcher! config)
  (bus/request! {:type :alert :level :critical})
  (pause 1800)
  (let [{:keys [status attempt max]} (get-in (state/db) [:alert :delivery])]
    (is (= :failed status))
    (is (= 3 attempt))
    (is (= 3 max)))
  (testing "every attempt was reported on the way"
    (is (some #(str/includes? (:text %) "retrying") (state/event-log)))))

(deftest alert-delivery-succeeds-on-a-healthy-sink
  (state/transact! [[[:controls :sink-failure] 0.0]])
  (tasks/start-dispatcher! config)
  (bus/request! {:type :alert :level :warn})
  (pause 600)
  (let [{:keys [status attempt]} (get-in (state/db) [:alert :delivery])]
    (is (= :delivered status))
    (is (= 1 attempt))))

(deftest recovery-stands-down-an-in-flight-delivery
  (state/transact! [[[:controls :sink-failure] 1.0]])
  (tasks/start-dispatcher! config)
  (bus/request! {:type :alert :level :critical})
  (pause 400)
  (is (#{:sending :retrying} (get-in (state/db) [:alert :delivery :status])))
  (bus/request! {:type :alert :level :ok})
  (pause 500)
  (testing "the retry loop is cancelled, not left running"
    (is (nil? (get-in (state/db) [:alert :delivery])))
    (is (nil? (get @tasks/in-flight :alert)))))

(deftest export-writes-a-file-and-reports-it
  (doseq [ts (range 1 6)]
    (state/transact! [[[:sample] {:cpu 0.5 :mem 0.5 :io-wait 0.0 :net-rx 1.0 :net-tx 2.0 :ts ts}]]))
  (tasks/start-dispatcher! config)
  (bus/request! {:type :export})
  (pause 900)
  (let [{:keys [state path total written]} (:export (state/db))]
    (is (= :done state))
    (is (= 6 total) "five samples plus a header row")
    (is (= total written))
    (let [lines (str/split-lines (slurp path))]
      (is (= "ts,cpu,mem,net_bytes_per_s,io_wait" (first lines)))
      (is (= 6 (count lines))))))

(deftest cancelling-an-export-leaves-nothing-behind
  (doseq [ts (range 1 121)]
    (state/transact! [[[:sample] {:cpu 0.5 :mem 0.5 :io-wait 0.0 :net-rx 0.0 :net-tx 0.0 :ts ts}]]))
  (tasks/start-dispatcher! config)
  (bus/request! {:type :export})
  (pause 400)
  (is (= :running (get-in (state/db) [:export :state])))
  (let [path (get-in (state/db) [:export :path])]
    (bus/request! {:type :cancel-export})
    (pause 400)
    (is (= :cancelled (get-in (state/db) [:export :state])))
    (testing "neither the export nor a half-written .part survives"
      (is (not (.exists (io/file path))))
      (is (not (.exists (io/file (str path ".part"))))))))

(deftest burst-spawns-and-reaps-real-processes
  (let [before (yes-processes)]
    (tasks/set-burst! 2)
    (pause 300)
    (is (= (+ before 2) (yes-processes)))
    (testing "lowering the count kills the extras"
      (tasks/set-burst! 1)
      (pause 300)
      (is (= (inc before) (yes-processes))))
    (testing "and shutdown reaps the rest"
      (tasks/shutdown!)
      (pause 300)
      (is (= before (yes-processes))))))

(deftest an-effect-whose-handler-transacts-does-not-deadlock
  ;; Regression: a mailbox post drives the supervisor inline until it parks
  ;; (ebb ADR-001). When effects posted from inside transact!'s lock and the
  ;; supervisor's handler transacted, both sides stopped forever -- every write
  ;; in the process froze while reads carried on looking healthy.
  (tasks/start-dispatcher! config)
  (doseq [[label changes] [["export"  [[[:export] {:state :requested}]]]
                           ["retune"  [[[:controls :interval-ms] 700]]]]]
    (let [done (atom false)
          t    (Thread. (fn [] (state/transact! changes) (reset! done true)))]
      (.start t)
      (.join t 3000)
      (is @done (str "transact! returned for " label))))
  (pause 1200)
  (is (#{:running :done} (get-in (state/db) [:export :state]))
      "and the work the effect asked for actually happened"))

(deftest the-supervisor-handles-requests-in-order
  (let [seen (atom [])]
    (with-redefs [tasks/handle! (fn [_ req]
                                  (when (= :ordering-probe (:type req))
                                    (swap! seen conj (:n req)))
                                  (Thread/sleep 5))]
      (tasks/start-dispatcher! config)
      (dotimes [i 15] (bus/request! {:type :ordering-probe :n i}))
      (pause 600)
      (is (= (vec (range 15)) @seen)))))
