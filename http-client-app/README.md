# http-client-app

[jolt-lang/http-client](https://github.com/jolt-lang/http-client),
`jolt.http-client`, against real HTTPS endpoints.

```
jolt run -m app.core           # or: jolt start
```

The client is a git dependency, not part of jolt. It is clj-http-lite running
on JVM networking shims (`java.net.URL`, `HttpURLConnection`, `javax.net.ssl`)
that the library implements over BSD sockets and the system OpenSSL through
`jolt.ffi`; gzip uses jolt's own zlib. The API is the common Clojure HTTP shape:

```clojure
(require '[jolt.http-client :as http])

(http/get  "https://example.com")
(http/get  "https://httpbin.org/get"  {:query-params {"q" "jolt"}})
(http/post "https://httpbin.org/post" {:body "{\"a\":1}" :content-type :json})
;; also head, put, delete, and the lower-level (http/request {:method :url …})
```

A response is `{:status N :headers {…} :body "…"}`, and the request map is
clj-http-lite's plus babashka.http-client's options (`:multipart`,
`:form-params`, `:basic-auth`, `:async`, …); see the library's README.

## Requirements

- `jolt` on PATH. The library is fetched from git on first run.
- OpenSSL 3 (`libssl`/`libcrypto`), for TLS.
- A network connection for the real endpoints.
