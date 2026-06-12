(ns app.core
  "The combined example: ring-core middleware + Selmer HTML templates +
  yogthos/config (all straight from their git repos via deps.edn) served by
  the spork/http Ring adapter. The port comes from config.edn, overridable
  with the PORT environment variable (config.core merges env over the file)."
  (:require [config.core :as config]
            [selmer.parser :as selmer]
            [clojure.java.io :as io]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring-janet.adapter :as adapter]))

(def template (slurp (io/resource "templates/index.html")))

(defn render-index
  "Render the index page for a config map + request params (params win)."
  [cfg params]
  (selmer/render template
                 {:name     (get params :name (get cfg :name "world"))
                  :motd     (:motd cfg)
                  :features (:features cfg)}))

(defn handler [{:keys [request-method uri params]}]
  (case [request-method uri]
    [:get "/"]      {:status 200
                     :headers {"Content-Type" "text/html; charset=utf-8"}
                     :body (render-index config/env params)}
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
        server (adapter/run-server app {:port port})]
    (println (str "ring-app listening on http://127.0.0.1:" port))
    (println "PORT env or a port argument override config.edn's :port")
    (janet.ev/sleep 1000000000)
    server))
