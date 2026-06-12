(ns app.core
  "The combined example: ring-core middleware + Selmer HTML templates +
  yogthos/config + a sqlite guestbook through jolt-lang/db's jdbc.core with
  honeysql queries — every library straight from its git repo via deps.edn.
  The port and database path come from config.edn; PORT/DATABASE_URL env
  vars override (config.core merges env over the file)."
  (:require [config.core :as config]
            [selmer.parser :as selmer]
            [clojure.java.io :as io]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring-janet.adapter :as adapter]
            [app.db :as db]))

(def template (slurp (io/resource "templates/index.html")))

(defonce conn (atom nil))

(defn render-index
  "Render the index page for a config map, request params, and db state."
  [cfg params]
  (selmer/render template
                 {:name      (get params :name (get cfg :name "world"))
                  :motd      (:motd cfg)
                  :features  (:features cfg)
                  :count     (if @conn (db/greeting-count @conn) 0)
                  :greetings (if @conn (db/recent-greetings @conn 10) [])}))

(defn handler [{:keys [request-method uri params]}]
  (case [request-method uri]
    [:get "/"]      {:status 200
                     :headers {"Content-Type" "text/html; charset=utf-8"}
                     :body (render-index config/env params)}
    [:post "/sign"] (let [name (get params :name "")]
                      (when (and @conn (pos? (count name)))
                        (db/add-greeting! @conn name))
                      {:status 303
                       :headers {"Location" (str "/?name=" name)}
                       :body ""})
    [:post "/echo"] {:status 200
                     :headers {"Content-Type" "text/plain"}
                     :body (pr-str (into (sorted-map) params))}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "not found\n"}))

(def app
  (-> handler
      wrap-keyword-params
      wrap-params))

(defn -main [& args]
  ;; the compiled binary bakes namespace state at build time — re-read env +
  ;; config.edn before serving (a no-op-ish refresh from source)
  (config/reload-env)
  (let [port (or (some-> (first args) parse-long)
                 (:port config/env 3000))
        db-path (:database-url config/env "guestbook.sqlite3")
        server (adapter/run-server app {:port port})]
    (reset! conn (db/connect db-path))
    (println (str "ring-app listening on http://127.0.0.1:" port
                  " (guestbook: " db-path ")"))
    (println "PORT / DATABASE_URL env override config.edn")
    (janet.ev/sleep 1000000000)
    server))
