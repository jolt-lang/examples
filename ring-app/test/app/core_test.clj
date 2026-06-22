(ns app.core-test
  (:require [app.core :as core]
            [app.db :as db]
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
  (println "hiccup page rendering")
  (check-has "renders the name upper-cased" "<h1>Hello JOLT!</h1>"
             (core/page {:name "jolt" :motd nil :features []} {}))
  (check-has "params win over config" "<h1>Hello VISITOR!</h1>"
             (core/page {:name "jolt"} {:name "visitor"}))
  (check-has "motd renders when present" "<p class=\"motd\">hi</p>"
             (core/page {:motd "hi"} {}))
  (check-has "features loop" "<li>a</li>"
             (core/page {:features ["a" "b"]} {}))

  (println "routing + ring middleware over the handler")
  (check-has "query params reach the page" "Hello WEB!"
             (:body (core/app {:request-method :get :uri "/" :query-string "name=web" :headers {}})))
  (check-has "path-param route" "Hello Alice!"
             (:body (core/app {:request-method :get :uri "/greetings/Alice" :query-string nil :headers {}})))
  (check "404 for unknown path" 404
         (:status (core/app {:request-method :get :uri "/nope" :query-string nil :headers {}})))
  (check "form body params parsed" "{:a \"1\", :b \"2\"}"
         (:body (core/app {:request-method :post :uri "/echo" :query-string nil
                           :headers {"content-type" "application/x-www-form-urlencoded"}
                           :body (java.io.StringReader. "a=1&b=2")})))

  (println "guestbook (sqlite)")
  (let [c (db/connect ":memory:")]
    (check "empty guestbook" 0 (db/greeting-count c))
    (db/add-greeting! c "Ada")
    (db/add-greeting! c "Grace")
    (check "count after two" 2 (db/greeting-count c))
    (check "most-recent first" "Grace" (:name (first (db/recent-greetings c 10)))))

  (println (str "\n" (if (zero? @failures) "all passed" (str @failures " FAILED"))))
  (when (pos? @failures) (throw (ex-info "test failures" {:n @failures}))))
