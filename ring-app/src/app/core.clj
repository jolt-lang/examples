(ns app.core
  "Demo app: ring-core middleware (straight from the ring git repo) running
  on the spork/http adapter."
  (:require [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring-janet.adapter :as adapter]))

(defn handler [{:keys [request-method uri params]}]
  (case [request-method uri]
    [:get "/"]      {:status 200
                     :headers {"Content-Type" "text/plain"}
                     :body (str "Hello " (get params :name "world") "!\n")}
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
  (let [port (or (some-> (first args) parse-long) 3000)
        server (adapter/run-server app {:port port})]
    (println (str "ring-app listening on http://127.0.0.1:" port))
    (println "try: curl 'http://127.0.0.1:3000/?name=Jolt'")
    ;; the server runs on the event loop; park this fiber forever
    (janet.ev/sleep 1000000000)
    server))
