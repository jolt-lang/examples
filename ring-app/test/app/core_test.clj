(ns app.core-test
  (:require [app.core :as core]
            [ring-janet.adapter :as adapter]
            [jolt.http :as http]))

(def failures (atom 0))

(defn check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— expected" (pr-str expected) "got" (pr-str actual)))))

(defn -main [& _]
  (println "app.core/app — pure handler through ring middleware")
  (check "query params via wrap-params + wrap-keyword-params"
         "Hello Jolt!\n"
         (:body (core/app {:request-method :get :uri "/" :query-string "name=Jolt"
                           :headers {}})))
  (check "defaults without params"
         "Hello world!\n"
         (:body (core/app {:request-method :get :uri "/" :query-string nil :headers {}})))
  (check "form body params"
         "{:a \"1\", :b \"2\"}"
         (:body (core/app {:request-method :post :uri "/echo" :query-string nil
                           :headers {"content-type" "application/x-www-form-urlencoded"}
                           :body (StringReader. "a=1&b=2")})))
  (check "url-encoded values decode"
         "Hello a b=c!\n"
         (:body (core/app {:request-method :get :uri "/" :query-string "name=a+b%3Dc"
                           :headers {}})))

  (println "end-to-end — live server on the event loop")
  (let [server (adapter/run-server core/app {:port 8377})
        _ (janet.ev/sleep 0.1)
        resp (http/get "http://127.0.0.1:8377/?name=Wire")
        resp404 (http/get "http://127.0.0.1:8377/nope")]
    (check "GET over the wire" "Hello Wire!\n" (:body resp))
    (check "GET status" 200 (:status resp))
    (check "404 route" 404 (:status resp404))
    (adapter/stop-server server))

  (if (pos? @failures)
    (do (println @failures "failing check(s)")
        (janet.os/exit 1))
    (println "all checks passed")))
