(ns app.core
  "Demonstrates the clj-http-lite HTTP client running on Jolt via
  jolt-lang/http-client (java.net + TLS + gzip host shims over Janet FFI).

  Starts a tiny local HTTP server (spork, through the janet bridge), makes a few
  requests against it, then — if the network is available — hits some real
  HTTPS endpoints to show TLS, redirects, query params and JSON POST."
  (:require [jolt.http-client :as http]
            [clojure.string :as str]))

;; --- a local server so the demo works offline ------------------------------
(defn- local-handler [req]
  ;; spork hands a Janet request table; respond with a Janet struct
  (let [path (janet/get req :route)
        method (janet/get req :method)]
    (cond
      (= path "/hello")
      (janet/struct :status 200 :body "hello from the local server"
                    :headers (janet/struct "Content-Type" "text/plain"))
      (= path "/echo")
      (janet/struct :status 200 :body (str method " " path " body=" (or (janet.spork.http/read-body req) "")))
      :else
      (janet/struct :status 404 :body "not found"))))

(defn start-local-server! [port]
  (janet.spork.http/server local-handler "127.0.0.1" port))

(defn- try-req [label f]
  (try
    (let [resp (f)]
      (println (format "  %-26s -> status %s  (%d bytes)"
                       label (str (:status resp)) (count (str (:body resp))))))
    (catch Exception e
      (println (format "  %-26s -> failed: %s" label (ex-message e))))))

(defn -main [& _args]
  (println "clj-http-lite on Jolt\n====================\n")
  (flush)

  (let [port 8771
        server (start-local-server! port)]
    (println (str "Local server on http://127.0.0.1:" port))
    (flush)
    (let [base (str "http://127.0.0.1:" port)]
      (try-req "GET /hello"  #(http/get (str base "/hello")))
      (println "    body:" (pr-str (:body (http/get (str base "/hello")))))
      (try-req "POST /echo" #(http/post (str base "/echo") {:body "ping"}))
      (println "    body:" (pr-str (:body (http/post (str base "/echo") {:body "ping"})))))
    ;; stop the accept loop so the process can exit (it would otherwise keep the
    ;; event loop alive forever)
    (janet.net/close server))
  (flush)

  (println "\nReal HTTPS endpoints (needs network):")
  (try-req "GET https://example.com" #(http/get "https://example.com" {:throw-exceptions false}))
  (try-req "GET https://clojure.org" #(http/get "https://clojure.org" {:throw-exceptions false}))
  (try-req "GET query-params"
           #(http/get "https://httpbin.org/get"
                      {:query-params {"q" "jolt" "lang" "clojure"} :throw-exceptions false}))
  (try-req "POST json"
           #(http/post "https://httpbin.org/post"
                       {:body "{\"hello\":\"jolt\"}" :content-type :json :throw-exceptions false}))
  (try-req "insecure (self-signed)"
           #(http/get "https://self-signed.badssl.com" {:insecure? true :throw-exceptions false}))

  (println "\ndone.")
  (flush))
