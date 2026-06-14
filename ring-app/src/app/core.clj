(ns app.core
  "The combined example: ring-core middleware + reitit routing + Selmer HTML
  templates + yogthos/config + a sqlite guestbook (jolt-lang/db's jdbc.core
  with honeysql queries) — every library straight from its git repo.

  reitit needs its :clj reader branches, but the other libraries are validated
  under jolt's default feature set. So instead of forcing the whole process to
  :clj, the require of reitit (below, outside this ns form) is scoped: features
  are switched to :clj only for that load, then restored. The app runs under
  default features."
  (:require [config.core :as config]
            [clojure.tools.logging :as log]
            [selmer.parser :as selmer]
            [clojure.java.io :as io]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring-janet.adapter :as adapter]
            [app.db :as db]))

;; Load reitit under :clj features, then restore — see the ns docstring.
(let [prev (__reader-features)]
  (__reader-features-set! ["clj" "jolt" "default"])
  (require '[reitit.trie-jolt] '[reitit.core :as reitit])
  (__reader-features-set! prev))

(def template (slurp (io/resource "templates/index.html")))

(defonce conn (atom nil))

(defn render-index [cfg params]
  (selmer/render template
                 {:name      (get params :name (get cfg :name "world"))
                  :motd      (:motd cfg)
                  :features  (:features cfg)
                  :count     (if @conn (db/greeting-count @conn) 0)
                  :greetings (if @conn (db/recent-greetings @conn 10) [])}))

(defn index-handler [{:keys [params]}]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (render-index config/env params)})

(defn sign-handler [{:keys [params]}]
  (let [name (get params :name "")]
    (when (and @conn (pos? (count name))) (db/add-greeting! @conn name))
    {:status 303 :headers {"Location" (str "/?name=" name)} :body ""}))

(defn greeting-handler [{:keys [path-params]}]
  {:status 200 :headers {"Content-Type" "text/plain"}
   :body (str "Hello " (:name path-params) "!\n")})

(defn echo-handler [{:keys [params]}]
  {:status 200 :headers {"Content-Type" "text/plain"}
   :body (pr-str (into (sorted-map) params))})

(def router
  (reitit/router
    [["/"                {:name :index :get index-handler}]
     ["/sign"            {:name :sign :post sign-handler}]
     ["/greetings/:name" {:name :greeting :get greeting-handler}]
     ["/echo"            {:name :echo :post echo-handler}]]))

(defn handler [{:keys [request-method uri] :as request}]
  (if-let [match (reitit/match-by-path router uri)]
    (if-let [h (get (:data match) request-method)]
      (h (assoc request :path-params (:path-params match)))
      {:status 405 :headers {"Content-Type" "text/plain"} :body "method not allowed\n"})
    {:status 404 :headers {"Content-Type" "text/plain"} :body "not found\n"}))

(defn wrap-log [handler]
  (fn [{:keys [request-method uri] :as request}]
    (let [response (handler request)]
      (log/info request-method uri "->" (:status response))
      response)))

(def app
  (-> handler wrap-keyword-params wrap-params wrap-log))

(defn -main [& args]
  (config/reload-env)
  (let [port (or (some-> (first args) parse-long) (:port config/env 3000))
        db-path (:database-url config/env "guestbook.sqlite3")
        server (adapter/run-server app {:port port})]
    (reset! conn (db/connect db-path))
    (log/info (str "ring-app listening on http://127.0.0.1:" port) "— guestbook:" db-path)
    (log/info "PORT / DATABASE_URL override config.edn")
    (janet.ev/sleep 1000000000)
    server))
