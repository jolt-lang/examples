(ns app.test-runner
  "Runs every test namespace in the project."
  (:require [clojure.test :as t]
            [app.metrics-test]
            [app.state-test]
            [app.pipeline-test]
            [app.tasks-test]
            [app.ui-test]))

(def namespaces
  '[app.metrics-test
    app.state-test
    app.pipeline-test
    app.tasks-test
    app.ui-test])

(defn -main [& _]
  (let [{:keys [fail error] :as result} (apply t/run-tests namespaces)]
    (when (pos? (+ fail error))
      (throw (ex-info "tests failed" result)))))
