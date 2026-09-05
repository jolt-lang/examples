(ns app.ui-test
  "The render is a pure function of a published snapshot, so it can be checked
  against a fixture db with nothing running."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hiccup.core :as h]
            [app.diagram :as diagram]
            [app.state :as state]
            [app.ui :as ui]))

(defn- render []
  (state/publish!)
  (h/html (ui/fragment)))

(use-fixtures :each
  (fn [f]
    (state/init! {:window 10 :warn-threshold 0.55 :crit-threshold 0.85})
    (doseq [ts (range 1 6)]
      (state/transact! [[[:sample] {:cpu (* 0.2 ts) :mem 0.4 :io-wait 0.0
                                    :net-rx 1000.0 :net-tx 500.0 :ts (* ts 1000)
                                    :cores [0.1 0.9] :mem-used-kb 3000000
                                    :mem-total-kb 8000000}]]))
    (f)))

(deftest the-fragment-renders-every-region
  (let [html (render)]
    (testing "metrics"
      (is (str/includes? html "spark-line"))
      (is (str/includes? html "class=\"core-fill\"") "per-core bars"))
    (testing "lanes, cascade, model and log panels"
      (is (str/includes? html "m/observe → m/relieve"))
      (is (str/includes? html "m/via m/blk (demand)"))
      (is (str/includes? html "class=\"cascade\""))
      (is (str/includes? html "class=\"node event\"") "the schema diagram"))
    (testing "controls -- every group, every slider, both export states"
      (is (= 3 (count (re-seq #"control-group" html))))
      (is (= 7 (count (re-seq #"type=\"range\"" html))))
      (is (str/includes? html "export snapshot"))
      (is (str/includes? html "/lanes/a/toggle")))))

(deftest the-fragment-reflects-the-model
  (testing "alert level reaches the banner"
    (is (str/includes? (render) "OK"))
    (state/transact! [[[:controls :warn] 0.1] [[:controls :crit] 0.15]])
    (let [html (render)]
      (is (str/includes? html "CRITICAL"))
      (is (str/includes? html "critical since"))))
  (testing "an in-flight export swaps the button for a cancel"
    (state/transact! [[[:export] {:state :running :written 40 :total 100}]])
    (let [html (render)]
      (is (str/includes? html "cancel export"))
      (is (not (str/includes? html "export snapshot"))))))

(deftest the-page-boots-datastar
  (let [page (ui/page)]
    (is (str/includes? page "/js/datastar.js"))
    (is (str/includes? page "data-init"))
    (is (str/includes? page "datastar-sse=true"))
    (testing "slider positions are seeded as signals, so the server never
              fights the user's drag"
      (is (str/includes? page "window"))
      (is (str/includes? page "interval")))))

(deftest sparkline-scales-and-degrades
  (testing "a single point draws no line rather than dividing by zero"
    (is (not (str/includes? (h/html (ui/sparkline [0.5] {})) "polyline"))))
  (testing "values above the scale are clamped into the box"
    (let [svg (h/html (ui/sparkline [0.0 2.0] {:scale-max 1.0 :height 50}))]
      (is (str/includes? svg "polyline"))
      (is (not (re-find #",-\d" svg)) "no point escapes above the viewBox"))))

(deftest the-diagram-comes-from-the-schema
  (let [{:keys [nodes edges]} (diagram/layout state/schema)
        by-label (into {} (map (juxt :label identity) nodes))]
    (testing "every event and effect is a node"
      (is (contains? by-label "compute-pressure"))
      (is (contains? by-label "announce-alert")))
    (testing "and the cascade's order shows up as increasing depth"
      (is (< (:x (by-label "sample")) (:x (by-label "history"))))
      (is (< (:x (by-label "history")) (:x (by-label "pressure")))))
    (is (seq edges))
    (testing "mermaid source is generated too"
      (is (str/starts-with? (diagram/mermaid state/schema) "stateDiagram-v2")))))
