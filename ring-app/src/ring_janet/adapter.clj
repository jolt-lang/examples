(ns ring-janet.adapter
  "A Ring adapter over spork/http, reached through jolt's janet.* interop
  bridge (janet.spork.http/*). Synchronous Ring 1.x handlers only.

  spork/http hands each connection's parsed request to a handler as a janet
  table ({:method :path :headers :connection :buffer ...}, header names
  lowercased) and writes back a response table ({:status :headers :body}).
  This namespace converts spork request -> Ring request map, calls the
  handler, and converts Ring response map -> a raw janet struct (spork
  iterates the response with host-level lookups, so it must NOT receive a
  jolt map)."
  (:require [clojure.string :as str]))

(defn- split-query
  "spork's :path is the request-target: URI plus optional ?query-string."
  [path]
  (let [i (.indexOf path "?")]
    (if (neg? i)
      [path nil]
      [(.substring path 0 i) (.substring path (inc i))])))

(defn- header-map
  "janet table of headers -> Ring headers map (string keys, lowercase from
  spork already)."
  [headers]
  (reduce (fn [m kv] (assoc m (str (nth kv 0)) (str (nth kv 1))))
          {}
          (janet/pairs (or headers (janet/struct)))))

(defn- ring-request
  "Build the Ring request map (https://github.com/ring-clojure/ring/blob/master/SPEC.md)."
  [req host port]
  (let [[uri qs] (split-query (str (or (get req :path) "/")))
        body     (janet.spork.http/read-body req)]
    {:server-port    port
     :server-name    host
     :remote-addr    "127.0.0.1"
     :uri            uri
     :query-string   qs
     :scheme         :http
     :request-method (keyword (str/lower-case (str (or (get req :method) "GET"))))
     :protocol       "HTTP/1.1"
     :headers        (header-map (get req :headers))
     ;; Ring's :body is an InputStream on the JVM; here it's a StringReader
     ;; shim — (slurp (:body req)) drains it, which is what ring middleware
     ;; (and most handlers) do with it.
     :body           (when body (StringReader. (str body)))}))

(defn- spork-response
  "Ring response map -> a raw janet struct for spork's send-response. Body
  coercion: nil -> \"\", string passes, seqs concatenate (the useful subset
  of ring.core.protocols/StreamableResponseBody on a host with no streams)."
  [{:keys [status headers body]}]
  (janet/struct :status (or status 200)
                :headers (apply janet/struct
                                (mapcat (fn [kv] [(str (key kv)) (str (val kv))])
                                        (seq (or headers {}))))
                :body (cond
                        (nil? body)    ""
                        (string? body) body
                        (seq? body)    (apply str body)
                        :else          (str body))))

(defn run-server
  "Start the server and return a handle {:server <stream> :host :port}.
  Non-blocking: connections are served on the janet event loop, like
  jolt.nrepl. opts: :host (default \"127.0.0.1\"), :port (default 3000)."
  [handler opts]
  (let [host (get opts :host "127.0.0.1")
        port (get opts :port 3000)
        spork-handler (fn [req]
                        ;; a throwing handler answers 500, like every ring
                        ;; adapter — and the error goes to stderr
                        (try
                          (spork-response (handler (ring-request req host port)))
                          (catch Throwable e
                            (janet/eprint "ring-janet.adapter: " (str e))
                            (janet/struct :status 500
                                          :headers (janet/struct "Content-Type" "text/plain")
                                          :body "Internal Server Error"))))
        server (janet.spork.http/server spork-handler host port)]
    {:server server :host host :port port}))

(defn stop-server
  "Close the server socket; in-flight connections finish on the event loop."
  [server-handle]
  (janet.net/close (:server server-handle))
  nil)
