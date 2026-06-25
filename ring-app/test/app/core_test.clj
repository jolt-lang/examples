(ns app.core-test
  (:require [app.core :as core]
            [app.db :as db]
            [integrant.core :as ig]
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

;; A self-contained Integrant config for tests: in-memory SQLite + a server on a
;; fixed port, so the end-to-end check can hit it over the wire.
(def test-config
  {:app/config  {:port 8377 :database-url ":memory:" :name "jolt"
                 :motd "hi" :features ["a" "b"]}
   :app/db      {:config (ig/ref :app/config)}
   :app/handler {:config (ig/ref :app/config) :db (ig/ref :app/db)}
   :app/server  {:handler (ig/ref :app/handler) :config (ig/ref :app/config)}})

(defn -main [& _]
  (println "selmer template rendering")
  (check-has "renders the name through the upper filter" "<h1>Hello JOLT!</h1>"
             (core/render-index {:name "jolt" :motd nil :features []} nil {}))
  (check-has "params win over config" "<h1>Hello VISITOR!</h1>"
             (core/render-index {:name "jolt"} nil {:name "visitor"}))
  (check-has "motd renders when present" "<p class=\"motd\">hi</p>"
             (core/render-index {:motd "hi"} nil {}))
  (check-has "features loop" "<li>a</li>"
             (core/render-index {:features ["a" "b"]} nil {}))

  (println "config.edn — read as an Integrant graph")
  (check "config.edn supplies the port" 3000
         (-> (core/load-config) :app/config :port))

  (println "reitit routing + ring middleware + guestbook via an Integrant system")
  (let [system (ig/init test-config)
        app    (:app/handler system)
        conn   (:app/db system)]
    (check-has "query params reach the template" "Hello WEB!"
               (:body (app {:request-method :get :uri "/" :query-string "name=web" :headers {}})))
    (check-has "reitit path-param route" "Hello Alice!"
               (:body (app {:request-method :get :uri "/greetings/Alice" :query-string nil :headers {}})))
    (check "reitit 404 for unknown path" 404
           (:status (app {:request-method :get :uri "/nope" :query-string nil :headers {}})))
    (check "form body params parsed" "{:a \"1\", :b \"2\"}"
           (:body (app {:request-method :post :uri "/echo" :query-string nil
                        :headers {"content-type" "application/x-www-form-urlencoded"}
                        :body (java.io.StringReader. "a=1&b=2")})))

    (check "empty guestbook" 0 (db/greeting-count conn))
    (check "add-greeting! returns id" 1 (db/add-greeting! conn "ada"))
    (db/add-greeting! conn "grace")
    (check "count after two" 2 (db/greeting-count conn))
    (check "recent order (newest first)" ["grace" "ada"]
           (mapv :name (db/recent-greetings conn 10)))
    (check "sign route inserts + redirects" 303
           (:status (app {:request-method :post :uri "/sign" :query-string nil
                          :headers {"content-type" "application/x-www-form-urlencoded"}
                          :body (java.io.StringReader. "name=alan")})))
    (check "signed" 3 (db/greeting-count conn))
    (check-has "page shows the signatures" "<li>alan"
               (:body (app {:request-method :get :uri "/" :query-string nil :headers {}})))

    (println "end-to-end — live server over the wire (started by :app/server)")
    (Thread/sleep 200)
    (let [resp (http/get "http://127.0.0.1:8377/?name=Ring")]
      (check "live server responds 200" true (= 200 (:status resp)))
      (check-has "live server serves the page" "Hello RING!" (:body resp)))

    (try (ig/halt! system)
         (catch Throwable e
           (swap! failures inc)
           (println "  FAIL ig/halt! stopped cleanly —" (ex-message e)))))

  (println)
  (if (zero? @failures)
    (println "all passed")
    (println @failures "FAILED"))
  (when (pos? @failures) (throw (ex-info "test failures" {:n @failures}))))
