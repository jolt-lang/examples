(ns app.core-test
  (:require [app.core :as core]
            [app.db :as db]
            [config.core :as config]
            [jolt.http.server :as adapter]
            [jolt.http-client :as http]
            [clojure.string :as str]))

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
  (check-has "renders the name through the upper filter" "<h1>Hello JOLT!</h1>"
             (core/render-index {:name "jolt" :motd nil :features []} {}))
  (check-has "params win over config" "<h1>Hello VISITOR!</h1>"
             (core/render-index {:name "jolt"} {:name "visitor"}))
  (check-has "motd renders when present" "<p class=\"motd\">hi</p>"
             (core/render-index {:motd "hi"} {}))
  (check-has "features loop" "<li>a</li>"
             (core/render-index {:features ["a" "b"]} {}))

  (println "reitit routing + ring middleware over the handler")
  (check-has "query params reach the template" "Hello WEB!"
             (:body (core/app {:request-method :get :uri "/" :query-string "name=web" :headers {}})))
  (check-has "reitit path-param route" "Hello Alice!"
             (:body (core/app {:request-method :get :uri "/greetings/Alice" :query-string nil :headers {}})))
  (check "reitit 404 for unknown path" 404
         (:status (core/app {:request-method :get :uri "/nope" :query-string nil :headers {}})))
  (check "form body params parsed" "{:a \"1\", :b \"2\"}"
         (:body (core/app {:request-method :post :uri "/echo" :query-string nil
                           :headers {"content-type" "application/x-www-form-urlencoded"}
                           :body (java.io.StringReader. "a=1&b=2")})))

  (println "config")
  (check "config.edn supplies the port" 3000 (:port config/env))

  (println "guestbook — sqlite via jdbc.core + honeysql")
  (reset! core/conn (db/connect ":memory:"))
  (check "empty guestbook" 0 (db/greeting-count @core/conn))
  (check "add-greeting! returns id" 1 (db/add-greeting! @core/conn "ada"))
  (db/add-greeting! @core/conn "grace")
  (check "count after two" 2 (db/greeting-count @core/conn))
  (check "recent order (newest first)" ["grace" "ada"]
         (mapv :name (db/recent-greetings @core/conn 10)))
  (check "sign route inserts + redirects" 303
         (:status (core/app {:request-method :post :uri "/sign" :query-string nil
                             :headers {"content-type" "application/x-www-form-urlencoded"}
                             :body (java.io.StringReader. "name=alan")})))
  (check "signed" 3 (db/greeting-count @core/conn))
  (check-has "page shows the signatures" "<li>alan"
             (:body (core/app {:request-method :get :uri "/" :query-string nil :headers {}})))

  (println "end-to-end — live server over the wire")
  (let [server (adapter/run-server core/app {:port 8377})]
    (Thread/sleep 200)
    (let [resp    (http/get "http://127.0.0.1:8377/?name=Wire")
          resp404 (http/get "http://127.0.0.1:8377/nope")]
      (check "GET status" 200 (:status resp))
      (check-has "HTML over the wire" "Hello WIRE!" (:body resp))
      (check-has "content type is html" "text/html" (get (:headers resp) "content-type" ""))
      (check "404 route" 404 (:status resp404)))
    (adapter/stop-server server))

  (println (str "\n" (if (zero? @failures) "all passed" (str @failures " FAILED"))))
  (when (pos? @failures) (throw (ex-info "test failures" {:n @failures}))))
