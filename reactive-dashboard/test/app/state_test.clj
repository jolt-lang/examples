(ns app.state-test
  "The state layer under test with no pipeline running: pure transactions in,
  pure db out."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [app.state :as state]))

(def config {:window 5 :warn-threshold 0.55 :crit-threshold 0.85})

(use-fixtures :each (fn [f] (state/init! config) (f)))

(defn- sample!
  ([ts cpu mem] (sample! ts cpu mem 0.0))
  ([ts cpu mem io-wait]
   (state/transact! [[[:sample] {:cpu cpu :mem mem :net-rx 0.0 :net-tx 0.0
                                 :io-wait io-wait :ts ts}]])))

(deftest one-sample-drives-the-whole-cascade
  (sample! 1000 0.9 0.9 0.1)
  (let [paths (mapv first (state/change-history))]
    (testing "raw -> history -> stats -> pressure -> alert, in that order"
      (is (= [[:sample] [:history] [:stats] [:pressure] [:alert :level]
              [:alert :since] [:alert :log]]
             paths))))
  (is (= 1 (count (:history (state/db)))))
  (is (= :warn (get-in (state/db) [:alert :level]))))

(deftest handlers-are-idempotent
  (testing "domino runs an event once per changed input, so re-running the
            same sample must not append twice"
    (sample! 1000 0.5 0.5)
    (sample! 1000 0.5 0.5)
    (is (= 1 (count (:history (state/db)))))
    (testing "and a distinct timestamp does append"
      (sample! 2000 0.5 0.5)
      (is (= 2 (count (:history (state/db))))))))

(deftest the-window-control-is-pure-recomputation
  (doseq [[ts cpu] [[1000 1.0] [2000 1.0] [3000 0.0] [4000 0.0]]]
    (sample! ts cpu 0.0))
  (is (= 0.5 (get-in (state/db) [:stats :cpu :avg])))
  (testing "narrowing the window re-derives from the same samples, no new input"
    (let [before (:history (state/db))]
      (state/transact! [[[:controls :window] 2]])
      (is (= before (:history (state/db))))
      (is (= 0.0 (get-in (state/db) [:stats :cpu :avg]))))))

(deftest thresholds-alone-move-the-alert
  ;; pressure = 0.55*cpu-avg + 0.35*mem + 0.10*io-wait = 0.655
  (sample! 1000 1.0 0.3)
  (is (= :warn (get-in (state/db) [:alert :level])))
  (testing "raising the warn threshold clears the alert with no new sample"
    (state/transact! [[[:controls :warn] 0.9]])
    (is (= :ok (get-in (state/db) [:alert :level]))))
  (testing "lowering the critical threshold escalates it"
    (state/transact! [[[:controls :crit] 0.1] [[:controls :warn] 0.05]])
    (is (= :critical (get-in (state/db) [:alert :level])))))

(deftest transitions-are-logged-once
  (sample! 1000 0.9 0.9)
  (sample! 2000 0.9 0.9)
  (sample! 3000 0.9 0.9)
  (testing "three hot samples, one warn entry"
    (is (= [:ok :warn] (mapv :level (get-in (state/db) [:alert :log])))))
  (is (= 1000 (get-in (state/db) [:alert :since]))))

(deftest mute-interceptor-short-circuits-classification
  (sample! 1000 0.0 0.0)
  (is (= :ok (get-in (state/db) [:alert :level])))
  (state/transact! [[[:controls :muted?] true]])
  (sample! 2000 1.0 1.0)
  (testing "pressure keeps moving; the level does not"
    (is (< 0.5 (:pressure (state/db))))
    (is (= :ok (get-in (state/db) [:alert :level]))))
  (testing "unmuting lets the next sample classify again"
    (state/transact! [[[:controls :muted?] false]])
    (sample! 3000 1.0 1.0)
    ;; the cpu term is a window average, so two hot samples after a cold one
    ;; land in warn rather than critical
    (is (= :warn (get-in (state/db) [:alert :level])))
    (testing "and escalates once the cold sample falls out of the window"
      (doseq [ts [4000 5000 6000]] (sample! ts 1.0 1.0))
      (is (= :critical (get-in (state/db) [:alert :level]))))))

(deftest pressure-stays-in-range
  (sample! 1000 5.0 5.0 5.0)
  (is (<= 0.0 (:pressure (state/db)) 1.0)))

(deftest a-failed-transaction-leaves-the-context-untouched
  (sample! 1000 0.5 0.5)
  (let [before (state/db)]
    (state/transact! [[:not-a-path 1]])
    (is (= before (state/db)))
    (is (= :error (:kind (last (state/event-log)))))))
