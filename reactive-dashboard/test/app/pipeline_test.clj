(ns app.pipeline-test
  "The ingestion lanes, exercised against real child processes -- the claims
  being tested (drops, throttling, reaping) are only meaningful end to end."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ebb.core :as m]
            [app.state :as state]
            [app.pipeline :as pipe]))

(use-fixtures :each
  (fn [f]
    (state/init! {:window 10 :sample-interval-ms 100})
    (reset! pipe/consumer-delay-ms 0)
    (reset! pipe/slow-probe? false)
    (try (f) (finally (pipe/shutdown!)))))

(defn- pause [ms] (m/? (m/sleep ms)))

(defn- alive?
  "Is this specific pid still around? Scoped to the pid on purpose: counting
  producers system-wide picks up any dashboard running in another terminal."
  [pid]
  (zero? (:exit (babashka.process/shell {:out :string :err :string :continue true}
                                        "kill" "-0" (str pid)))))

(deftest parse-line-reads-the-producer-format
  (is (= {:seq 42 :busy 1530 :total 9630}
         (pipe/parse-line "42 cpu  1000 10 500 8000 100 0 20 0 0 0")))
  (testing "anything that is not a sequenced cpu line is ignored"
    (is (nil? (pipe/parse-line "")))
    (is (nil? (pipe/parse-line "42 intr 1 2 3")))
    (is (nil? (pipe/parse-line "not-a-number cpu 1 2 3 4 5 6 7 8")))))

(deftest lane-a-drops-under-a-slow-consumer
  (pipe/start-lane-a!)
  (pause 700)
  (testing "a consumer that keeps up loses nothing"
    (let [{:keys [produced delivered dropped]} (:a (pipe/lane-stats))]
      (is (pos? produced))
      (is (= produced delivered))
      (is (zero? dropped))))
  (testing "relieve collapses what a slow consumer cannot take"
    (reset! pipe/consumer-delay-ms 150)
    (pause 1500)
    (let [{:keys [produced delivered dropped]} (:a (pipe/lane-stats))]
      (is (pos? dropped))
      (is (< delivered produced))
      (is (= dropped (- produced delivered))))))

(deftest lane-b-throttles-the-producer-instead-of-dropping
  (pipe/start-lane-b!)
  (reset! pipe/consumer-delay-ms 150)
  (pause 1500)
  (let [{:keys [consumed producer-seq free-running stalled-by]} (:b (pipe/lane-stats))]
    (testing "demand-driven reads do not skip: the producer's own sequence
              number tracks what the consumer has taken"
      (is (pos? consumed))
      (is (<= (Math/abs (- consumed producer-seq)) 2)))
    (testing "the producer is stalled well behind free-running rate"
      (is (< producer-seq (/ free-running 2)))
      (is (pos? stalled-by)))))

(deftest stopping-a-lane-cancels-the-flow-and-reaps-the-child
  (pipe/start-lane-b!)
  (pause 400)
  (let [proc (:proc (pipe/lane :b))
        pid  (.pid (:proc proc))
        before (:consumed (:b (pipe/lane-stats)))]
    (is (pos? before))
    (is (.isAlive (:proc proc)))
    (pipe/stop-lane! :b)
    (pause 400)
    (testing "the child is gone -- from the process table, not just the handle"
      (is (not (.isAlive (:proc proc))))
      (is (not (alive? pid))))
    (testing "and emission has stopped"
      (let [after (:consumed (:b (pipe/lane-stats)))]
        (pause 300)
        (is (= after (:consumed (:b (pipe/lane-stats)))))))
    (is (false? (pipe/running? :b)))))

(deftest lane-c-samples-and-retunes
  (pipe/start-lane-c! 100)
  (pause 600)
  (let [samples (count (:history (state/db)))]
    (is (>= samples 3) "the poll loop transacts samples into domino")
    (testing "retuning cancels and respawns rather than flipping a flag"
      (pipe/retune-lane-c! 400)
      (is (pipe/running? :c))
      (pause 700)
      (let [added (- (count (:history (state/db))) samples)]
        (is (<= 1 added 3) "a slower interval produces visibly fewer samples")))))

(deftest a-slow-probe-degrades-without-stalling-the-sampler
  (pipe/start-lane-c! 100)
  (pause 500)
  (is (= :ok (:status (:probe (state/db)))))
  (reset! pipe/slow-probe? true)
  (pause 800)
  (let [samples (count (:history (state/db)))]
    (testing "the probe times out"
      (is (= :stale (:status (:probe (state/db))))))
    (pause 500)
    (testing "while sampling continues at full rate"
      (is (> (count (:history (state/db))) samples)))))
