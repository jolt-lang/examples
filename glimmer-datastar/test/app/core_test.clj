(ns app.core-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [app.core :as app]
            [jolt.datastar.core :as ds]
            [clojure.core.async :as async]))

(deftest full-page-serves-datastar-init
  (let [handler (ds/wrap-datastar app/app {})
        r       (handler {:request-method :get :uri "/" :headers {}})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "/js/datastar.js"))
    (is (str/includes? (:body r) "data-init"))
    (is (str/includes? (:body r) "datastar-sse=true"))
    (is (str/includes? (:body r) "data-signals"))))

(deftest action-patches-signals
  (let [handler (ds/wrap-datastar app/app {})
        r       (handler {:request-method :get :uri "/count/inc"
                          :headers {"datastar-request" "true"}})]
    (is (= 200 (:status r)))
    (is (= "application/json" (get-in r [:headers "Content-Type"])))
    (is (str/includes? (:body r) "\"count\":1"))))

(deftest sse-stream-renders-fragment
  (let [handler (ds/wrap-datastar app/app {})
        r       (handler {:request-method :get :uri "/"
                          :query-string "datastar-sse=true" :headers {}})]
    (is (= "text/event-stream; charset=utf-8" (get-in r [:headers "Content-Type"])))
    (is (async/chan? (:body r)))
    (let [e (async/<!! (:body r))]
      (is (str/includes? e "event: datastar-patch-elements"))
      (is (str/includes? e "<span class=\"count\" data-text=\"$count\">1</span>")))
    (async/close! (:body r))))

(deftest serves-the-vendored-client-from-the-classpath
  ;; io/resource, not a path under the cwd: a built binary runs from anywhere
  ;; and has resources/ embedded, not on disk.
  (with-redefs [slurp (let [real slurp]
                        (fn [x & opts]
                          (when (and (string? x) (str/starts-with? x "resources/"))
                            (throw (ex-info "read from the cwd" {:path x})))
                          (apply real x opts)))]
    (let [r (app/app {:request-method :get :uri "/js/datastar.js" :headers {}})]
      (is (= 200 (:status r)))
      (is (= "application/javascript" (get-in r [:headers "Content-Type"])))
      (is (str/includes? (str (:body r)) "datastar")))))

(defn -main [& _]
  (let [{:keys [fail error] :as result} (run-tests 'app.core-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "example tests failed" result)))))
