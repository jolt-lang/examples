(ns app.test-runner
  "Runs every test namespace in the project."
  (:require [clojure.test :as t]
            [app.metrics-test]
            [app.host-test]
            [app.state-test]
            [app.pipeline-test]
            [app.tasks-test]
            [app.ui-test]
            [app.core-test]))

(def namespaces
  '[app.metrics-test
    app.host-test
    app.state-test
    app.pipeline-test
    app.tasks-test
    app.ui-test
    app.core-test])

(defn -main [& _]
  (let [{:keys [fail error] :as result} (apply t/run-tests namespaces)]
    (when (pos? (+ fail error))
      (throw (ex-info "tests failed" result)))))
