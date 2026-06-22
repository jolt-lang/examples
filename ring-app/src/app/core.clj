(ns app.core
  "A guestbook web app on jolt: ring-core middleware (params, keyword-params) +
  hiccup HTML + yogthos/config — all from git — served by jolt's built-in HTTP
  server (BSD sockets via FFI) with a SQLite guestbook (jolt's built-in libsqlite3
  binding). Routing is a small handler; SQL is in app.db."
  (:require [config.core :as config]
            [clojure.tools.logging :as log]
            [hiccup.core :as h]
            [clojure.string :as str]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring-janet.adapter :as adapter]
            [app.db :as db]))

(defonce conn (atom nil))

(defn page [cfg params]
  (str
    "<!doctype html>"
    (h/html
      [:html {:lang "en"}
       [:head [:meta {:charset "utf-8"}]
        [:title (str (:name cfg "world") " — ring on jolt")]]
       [:body
        [:h1 (str "Hello " (str/upper-case (get params :name (:name cfg "world"))) "!")]
        (when-let [motd (:motd cfg)] [:p {:class "motd"} motd])
        [:ul (for [f (:features cfg)] [:li f])]
        [:form {:action "/sign" :method "post"}
         [:input {:name "name" :placeholder "your name"}]
         [:button {:type "submit"} "sign the guestbook"]]
        [:h2 (str (if @conn (db/greeting-count @conn) 0) " signatures")]
        [:ol {:class "guestbook"}
         (for [g (if @conn (db/recent-greetings @conn 10) [])]
           [:li (:name g) " " [:small (:created-at g)]])]]])))

(defn index-handler [{:keys [params]}]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (page config/env params)})

(defn sign-handler [{:keys [params]}]
  (let [name (get params :name "")]
    (when (and @conn (pos? (count name))) (db/add-greeting! @conn name))
    {:status 303 :headers {"Location" (str "/?name=" name)} :body ""}))

(defn greeting-handler [name]
  {:status 200 :headers {"Content-Type" "text/plain"} :body (str "Hello " name "!\n")})

(defn echo-handler [{:keys [params]}]
  {:status 200 :headers {"Content-Type" "text/plain"} :body (pr-str (into (sorted-map) params))})

;; A small router: exact routes plus the /greetings/:name pattern.
(defn handler [{:keys [request-method uri] :as request}]
  (cond
    (and (= uri "/") (= request-method :get))      (index-handler request)
    (and (= uri "/sign") (= request-method :post)) (sign-handler request)
    (and (= uri "/echo") (= request-method :post)) (echo-handler request)
    (and (str/starts-with? uri "/greetings/") (= request-method :get))
      (greeting-handler (subs uri (count "/greetings/")))
    :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found\n"}))

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
        db-path (:database-url config/env "guestbook.sqlite3")]
    (reset! conn (db/connect db-path))
    (adapter/run-server app {:port port})
    (log/info (str "ring-app listening on http://127.0.0.1:" port) "— guestbook:" db-path)
    (log/info "PORT / DATABASE_URL override config.edn")
    ;; keep the process alive while the server thread serves
    (loop [] (Thread/sleep 3600000) (recur))))
