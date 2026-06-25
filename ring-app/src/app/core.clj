(ns app.core
  "A guestbook web app on jolt running the real JVM-style stack from git:
  ring-core middleware + reitit routing + Selmer HTML templates + honeysql
  queries — wired together with Integrant (component lifecycle) and served by the
  ring-chez-adapter HTTP server (BSD sockets via FFI) over a SQLite guestbook
  (jolt's built-in jdbc.core / libsqlite3 binding).

  Integrant owns the config: config.edn is a graph keyed by component
  (#ig/ref wiring), read at startup by load-config into a system that is started
  with ig/init and stopped with ig/halt!. PORT / DATABASE_URL env vars override
  config.edn (applied in the :app/config init-key).

  reitit reads its :clj branches, so the require below is scoped: reader features
  are switched to :clj only for that load, then restored. Everything else runs
  under jolt's default feature set, and the app serves under default features."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [selmer.parser :as selmer]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring-chez.adapter :as adapter]
            [app.db :as db]))

;; Load reitit under :clj features, then restore — see the ns docstring.
(let [prev (__reader-features)]
  (__reader-features-set! ["clj" "jolt" "default"])
  (require '[reitit.trie-jolt] '[reitit.core :as reitit])
  (__reader-features-set! prev))

;; Loaded on first render, not at namespace load — so io/resource resolves at
;; runtime against the live source roots. In a standalone binary that's either
;; the embedded copy (:jolt/build :embed) or a resources/ dir next to the binary.
(def template (delay (slurp (io/resource "templates/index.html"))))

(defn render-index [config db params]
  (selmer/render @template
                 {:name      (get params :name (get config :name "world"))
                  :motd      (:motd config)
                  :features  (:features config)
                  :count     (if db (db/greeting-count db) 0)
                  :greetings (if db (db/recent-greetings db 10) [])}))

(defn wrap-log [handler]
  (fn [{:keys [request-method uri] :as request}]
    (let [response (handler request)]
      (log/info request-method uri "->" (:status response))
      response)))

;; Build the Ring app for one system. Handlers close over that system's config and
;; db, so each (ig/init config) gets an isolated app with no global state.
(defn make-app [{:keys [config db]}]
  (let [index-handler    (fn [{:keys [params]}]
                           {:status 200
                            :headers {"Content-Type" "text/html; charset=utf-8"}
                            :body (render-index config db params)})
        sign-handler     (fn [{:keys [params]}]
                           (let [name (get params :name "")]
                             (when (and db (pos? (count name)))
                               (db/add-greeting! db name))
                             {:status 303
                              :headers {"Location" (str "/?name=" name)}
                              :body ""}))
        greeting-handler (fn [{:keys [path-params]}]
                           {:status 200 :headers {"Content-Type" "text/plain"}
                            :body (str "Hello " (:name path-params) "!\n")})
        echo-handler     (fn [{:keys [params]}]
                           {:status 200 :headers {"Content-Type" "text/plain"}
                            :body (pr-str (into (sorted-map) params))})
        router           (reitit/router
                          [["/"                {:name :index :get index-handler}]
                           ["/sign"            {:name :sign :post sign-handler}]
                           ["/greetings/:name" {:name :greeting :get greeting-handler}]
                           ["/echo"            {:name :echo :post echo-handler}]])
        handler          (fn [{:keys [request-method uri] :as request}]
                           (if-let [match (reitit/match-by-path router uri)]
                             (if-let [h (get (:data match) request-method)]
                               (h (assoc request :path-params (:path-params match)))
                               {:status 405
                                :headers {"Content-Type" "text/plain"}
                                :body "method not allowed\n"})
                             {:status 404
                              :headers {"Content-Type" "text/plain"}
                              :body "not found\n"}))]
    (-> handler wrap-keyword-params wrap-params wrap-log)))

;; --- Integrant components ---------------------------------------------------
;; config.edn's graph: :app/config -> {:app/db :app/handler} -> :app/server.

(defn load-config
  "Read config.edn (next to the binary / cwd) as an Integrant config graph."
  []
  (-> (io/file "config.edn") slurp ig/read-string))

(defmethod ig/init-key :app/config [_ cfg]
  ;; PORT / DATABASE_URL beat config.edn — preserves the `PORT=8080 joltc run`
  ;; behavior now that Integrant owns the config.
  (let [env #(System/getenv %)]
    (cond-> cfg
      (env "PORT")         (assoc :port (parse-long (env "PORT")))
      (env "DATABASE_URL") (assoc :database-url (env "DATABASE_URL")))))

(defmethod ig/init-key :app/db [_ {:keys [config]}]
  (db/connect (:database-url config)))

(defmethod ig/halt-key! :app/db [_ conn]
  (when-let [close (:close conn)] (close)))

(defmethod ig/init-key :app/handler [_ deps]
  (make-app deps))

(defmethod ig/init-key :app/server [_ {:keys [handler config]}]
  (adapter/run-server handler {:port (:port config)}))

(defmethod ig/halt-key! :app/server [_ server]
  (adapter/stop-server server))

(defn -main [& _]
  (let [system (ig/init (load-config))
        port   (-> system :app/server :port)]
    (log/info "ring-app listening on http://127.0.0.1:" port)
    (log/info "config graph in config.edn; PORT / DATABASE_URL override it")
    ;; keep the process alive while the server thread serves
    (loop [] (Thread/sleep 3600000) (recur))))
