# ring-app

[Ring](https://github.com/ring-clojure/ring) on Jolt: `ring-core` and
`ring-codec` come straight from their git repos via `deps.edn` (no forks, no
patches), and a small adapter (`ring-janet.adapter`) runs Ring handlers on
[spork/http](https://janet-lang.org/spork/api/http.html) — Janet's HTTP
server — through jolt's `janet.*` interop bridge.

The adapter lives in `src/ring_janet/adapter.clj` for now; it will move to
its own library once it has soaked.

## Prerequisites

The jolt toolchain (see the [greeter example](../greeter/README.md)).
Nothing else: the HTTP server is [spork/http](https://janet-lang.org/spork/api/http.html),
which jolt vendors and bakes into its binary (`vendor/spork/http.janet` in
the jolt repo) — `jpm install spork` is NOT required (and currently fails
against spork HEAD: its gfx2d native-module declaration trips newer jpm).

## Run

```bash
jolt-deps run -m app.core            # listens on :3000
curl 'http://127.0.0.1:3000/?name=Jolt'
curl -d 'a=1&b=2' http://127.0.0.1:3000/echo
```

## Tests

```bash
jolt-deps -M:test    # pure middleware checks + a live end-to-end round trip
```

## What works

- `ring.middleware.params` / `ring.middleware.keyword-params` from ring-core,
  resolved as a `:deps/root` git dependency (ring is a monorepo).
- `ring.util.codec` (ring-codec) on jolt's java.net/java.util shims:
  URLEncoder/URLDecoder, Base64, StringTokenizer, MapEntry.
- The Ring SPEC request map (`:uri`, `:query-string`, `:request-method`,
  lowercase `:headers`, `:body`) and response map (`:status`, `:headers`,
  string/seq `:body`).
