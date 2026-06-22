# http-client-app

Jolt's built-in HTTP client, `jolt.http-client`, against real HTTPS endpoints.

```
joltc run -m app.core
```

The client is part of jolt — no dependencies. It's backed by the system `curl`
binary (libcurl's `curl_easy_setopt` is variadic, and Chez's fixed-signature FFI
can't place a variadic arg where Apple Silicon expects it without a per-platform
C shim; shelling to `curl` uses the same native library, with TLS, redirects and
gzip, on every platform). The API mirrors the common Clojure HTTP shape:

```clojure
(require '[jolt.http-client :as http])

(http/get  "https://example.com")
(http/get  "https://httpbin.org/get"  {:query-params {"q" "jolt"}})
(http/post "https://httpbin.org/post" {:body "{\"a\":1}" :content-type :json})
;; also head, put, delete, and the lower-level (http/request {:method :url …})
```

A response is `{:status N :headers {…} :body "…"}`. Options: `:headers`,
`:body`, `:query-params`, `:content-type` (`:json`/`:xml`/`:form` or a string),
`:insecure?` (skip TLS verification), `:follow?`, `:timeout-ms`.

## Requirements

- `joltc` on PATH, and the system `curl` binary (preinstalled on macOS and most
  Linux distros).
- A network connection for the real endpoints.
