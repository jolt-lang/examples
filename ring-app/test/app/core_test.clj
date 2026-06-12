(ns app.core-test
  (:require [app.core :as core]
            [ring-janet.adapter :as adapter]
            [clojure.string :as str]
            [jolt.http :as http]))

(def failures (atom 0))

(defn check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— expected" (pr-str expected) "got" (pr-str actual)))))

(defn check-has [label needle haystack]
  (if (and (string? haystack) (str/includes? haystack needle))
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— no" (pr-str needle) "in" (pr-str haystack)))))

(defn -main [& _]
  (println "selmer template rendering")
  (check-has "renders the name through the upper filter"
             "<h1>Hello JOLT!</h1>"
             (core/render-index {:name "jolt" :motd nil :features []} {}))
  (check-has "params win over config"
             "<h1>Hello VISITOR!</h1>"
             (core/render-index {:name "jolt"} {:name "visitor"}))
  (check-has "motd renders when present"
             "<p class=\"motd\">hi</p>"
             (core/render-index {:motd "hi"} {}))
  (check-has "features loop"
             "<li>a</li>"
             (core/render-index {:features ["a" "b"]} {}))

  (println "ring middleware over the handler")
  (check-has "query params reach the template"
             "Hello WEB!"
             (:body (core/app {:request-method :get :uri "/" :query-string "name=web"
                               :headers {}})))
  (check "form body params"
         "{:a \"1\", :b \"2\"}"
         (:body (core/app {:request-method :post :uri "/echo" :query-string nil
                           :headers {"content-type" "application/x-www-form-urlencoded"}
                           :body (StringReader. "a=1&b=2")})))

  (println "config")
  (check "config.edn supplies the port" 3000 (:port config.core/env))

  (println "end-to-end — live server on the event loop")
  (let [server (adapter/run-server core/app {:port 8377})
        _ (janet.ev/sleep 0.1)
        resp (http/get "http://127.0.0.1:8377/?name=Wire")
        resp404 (http/get "http://127.0.0.1:8377/nope")]
    (check "GET status" 200 (:status resp))
    (check-has "HTML over the wire" "Hello WIRE!" (:body resp))
    (check-has "content type is html" "text/html"
               (get (:headers resp) "content-type" ""))
    (check "404 route" 404 (:status resp404))
    (adapter/stop-server server))

  (if (pos? @failures)
    (do (println @failures "failing check(s)")
        (janet.os/exit 1))
    (println "all checks passed")))
