(ns app.core
  "jolt-lang/http-client (clj-http-lite over jolt.ffi: raw sockets + OpenSSL TLS +
  libz) — TLS, redirects, gzip, query params and JSON POST against real HTTPS
  endpoints."
  (:require [jolt.http-client :as http]
            [clojure.string :as str]))

(defn- try-req [label f]
  (try
    (let [resp (f)]
      (println (format "  %-30s -> status %s  (%d bytes)"
                       label (str (:status resp)) (count (str (:body resp))))))
    (catch Exception e
      (println (format "  %-30s -> failed: %s" label (ex-message e))))))

(defn -main [& _args]
  (println "jolt.http-client (curl)\n=======================\n")
  (println "Real HTTPS endpoints (needs network):")
  (try-req "GET https://example.com"  #(http/get "https://example.com"))
  (try-req "GET https://clojure.org"  #(http/get "https://clojure.org"))
  (try-req "GET with query-params"
           #(http/get "https://httpbin.org/get" {:query-params {"q" "jolt" "lang" "clojure"}}))
  (try-req "POST json"
           #(http/post "https://httpbin.org/post"
                       {:body "{\"hello\":\"jolt\"}" :content-type :json}))
  (try-req "GET insecure (self-signed)"
           #(http/get "https://self-signed.badssl.com" {:insecure? true}))

  ;; show a full response: status, a header, and that the body round-trips
  (println "\nDetail of GET https://httpbin.org/get?demo=1 :")
  (let [r (http/get "https://httpbin.org/get" {:query-params {"demo" "1"}})]
    (println "  status      :" (:status r))
    (println "  content-type:" (get (:headers r) "content-type"))
    (println "  echoes demo :" (str/includes? (:body r) "\"demo\"")))

  (println "\ndone.")
  (flush))
