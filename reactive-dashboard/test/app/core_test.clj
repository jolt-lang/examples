(ns app.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [app.core :as core]))

(deftest serves-the-vendored-client-from-the-classpath
  ;; io/resource, not a path under the cwd: a built binary runs from anywhere
  ;; and has resources/ embedded, not on disk.
  (with-redefs [slurp (let [real slurp]
                        (fn [x & opts]
                          (when (and (string? x) (str/starts-with? x "resources/"))
                            (throw (ex-info "read from the cwd" {:path x})))
                          (apply real x opts)))]
    (let [r (core/routes {:uri "/js/datastar.js"})]
      (is (= 200 (:status r)))
      (is (str/includes? (str (:body r)) "datastar")))))
