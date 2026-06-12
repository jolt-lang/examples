# ring-app

The Jolt example app: [Ring](https://github.com/ring-clojure/ring) middleware,
[Selmer](https://github.com/yogthos/Selmer) HTML templates,
[yogthos/config](https://github.com/yogthos/config), and [reitit](https://github.com/metosin/reitit) routing, and a SQLite guestbook
through [jolt-lang/db](https://github.com/jolt-lang/db)'s `jdbc.core` with
queries written as [honeysql](https://github.com/seancorfield/honeysql) data —
every library loaded straight from its git repo via `deps.edn` (no forks, no
patches) — served by
[ring-janet-adapter](https://github.com/jolt-lang/ring-janet-adapter) over
[spork/http](https://janet-lang.org/spork/api/http.html), Janet's HTTP server,
through jolt's `janet.*` interop bridge.

```
deps.edn                       ring-core (:deps/root), ring-codec, Selmer,
                               config, and spork/http as a :jpm/module dep
config.edn                     runtime config — :port, :database-url, content
src/app/db.clj                 guestbook storage: jdbc.core + honeysql
resources/templates/index.html the Selmer HTML template
src/app/core.clj               reitit router + handlers + -main
test/                          render, middleware, config, and live-server checks
main.janet / project.janet /   native-executable build (build/ring-app)
build.sh
```

The Ring <-> spork/http adapter graduated to its own library,
[ring-janet-adapter](https://github.com/jolt-lang/ring-janet-adapter), pulled
in as an ordinary git dependency.

## Prerequisites

[Janet](https://janet-lang.org) and `jpm`, plus the `jolt` and `jolt-deps`
binaries:

```bash
git clone https://github.com/jolt-lang/jolt.git
cd jolt && git submodule update --init && jpm build
export PATH="$PWD/build:$PATH"
```

The HTTP server is spork/http, declared in `deps.edn` as a `:jpm/module`
dependency — `jolt-deps` verifies it's importable and runs `jpm install spork`
for you when it isn't.

Note: `jpm install spork` needs an up-to-date jpm (spork HEAD declares
`.janet` native sources, which older jpm — including Homebrew's current
bundle — rejects with an "unknown source file type" error; upgrade jpm from
git: `git clone https://github.com/janet-lang/jpm && cd jpm &&
PREFIX=/opt/homebrew janet bootstrap.janet`).

## Run

```bash
jolt-deps run -m app.core            # listens on config.edn's :port (3000)
PORT=8080 jolt-deps run -m app.core  # config.core/env: env beats config.edn
curl 'http://127.0.0.1:3000/?name=Jolt'
curl -d 'a=1&b=2' http://127.0.0.1:3000/echo
```

`GET /` renders `resources/templates/index.html` through Selmer —
`{{name|upper}}`, an `{% if %}` motd, and a `{% for %}` feature list — with
the name from `?name=` query params (ring's `wrap-params` +
`wrap-keyword-params`) falling back to `config.edn`. The guestbook form
POSTs `/sign`, which inserts into SQLite through `jdbc.core` with a honeysql
query and redirects home; the page lists recent signatures the same way.
`DATABASE_URL` (or `:database-url` in config.edn) picks the database file.

## Tests

```bash
jolt-deps -M:test    # template, middleware, config, and a live round trip
```

## Native executable

```bash
./build.sh           # -> build/ring-app  (jolt context baked at build time)
PORT=8080 ./build/ring-app
./build/ring-app nrepl 7888   # the same binary can host an nREPL
```

## REPL

```bash
jolt-deps repl
```

```clojure
user=> (require '[app.core :as app])
user=> (subs (app/render-index {:name "repl"} {}) 0 60)
user=> (app/-main "8090")   ; serve from the REPL
```

## Routing

Routes are a [reitit](https://github.com/metosin/reitit) data-driven router
(`src/app/core.clj`). reitit-core needs its `:clj` reader branches and its
`reitit.Trie` Java class; jolt-lang/router mirrors the trie in Clojure, and
`app.core` loads reitit under `:clj` reader features *scoped to that one
require* (the other libraries stay on jolt's default feature set):

```clojure
(let [prev (__reader-features)]
  (__reader-features-set! ["clj" "jolt" "default"])
  (require (quote [reitit.trie-jolt]) (quote [reitit.core :as reitit]))
  (__reader-features-set! prev))
```

`GET /greetings/:name` shows a path-param route; `/`, `/sign`, `/echo`
are static.

## Divergence notes

See the [adapter README](https://github.com/jolt-lang/ring-janet-adapter)
(:body is a StringReader shim, response bodies are strings/eager seqs).
