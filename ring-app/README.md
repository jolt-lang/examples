# ring-app

A guestbook web app on jolt running the real JVM-style stack, every library
pulled from its git repo and run unchanged: **ring-defaults** (params, static
resources + content-type, session, security headers), **reitit** routing,
**Selmer** HTML templates, **honeysql** queries, **tick** for the timestamps,
wired together with **Integrant**. It's served by the **ring-chez-adapter** HTTP
server (a minimal HTTP/1.1 server over BSD sockets via Chez's FFI), over a
**SQLite** guestbook through `jdbc.core` (the **jolt-lang/db** API over
`libsqlite3`). Logging is `clojure.tools.logging`.

The CSS under `resources/public` is served by ring-defaults' static-resource +
content-type middleware (with the right `text/css` MIME). ring-defaults' session
and CSRF middleware needs `javax.crypto`, which **jolt-lang/jolt-crypto** supplies
over the system OpenSSL — so the whole `site-defaults` stack loads and runs.

```
joltc serve                     # listen on config.edn's :port (3000)
PORT=8080 joltc serve           # PORT beats config.edn (Integrant applies the override)
joltc -M:test                   # rendering, routing, middleware, the guestbook
```

Then visit <http://127.0.0.1:3000>, sign the guestbook, and watch the request log.
(`joltc run` won't work — `run` is a built-in jolt command, so the project's
`:serve` task is what starts the server; `joltc -m app.core` is the long form.)

## Dev mode (REPL)

For live development, start an nREPL server and connect your editor, or use the
terminal REPL in this directory:

```
joltc nrepl-server              # nREPL on 127.0.0.1:7888 (.nrepl-port written)
joltc                           # ...or a terminal REPL
```

`-main` blocks on a sleep loop, so in the REPL start the Integrant system by
hand. `run-server` serves on a background thread, so the REPL stays responsive:

```
(require '[app.core :as core]
         '[integrant.core :as ig])
(def system (ig/init (core/load-config)))   ; starts serving on :port (3000)
(ig/halt! system)                            ; stop the server

;; edit app.core, then reload + restart to pick up handler changes:
(require '[app.core :as core] :reload)
(def system (ig/init (core/load-config)))
```

## Libraries

Every concern is a git library run unchanged; jolt itself provides only the
FFI + runtime. Git deps are fetched once into `~/.jolt/gitlibs`.

| Concern        | Source                                                        |
| ---            | ---                                                           |
| HTTP server    | git — `jolt-lang/ring-chez-adapter` (BSD sockets via FFI)     |
| Database       | git — `jolt-lang/db` / `jdbc.core` (libsqlite3 via FFI)       |
| Middleware     | git — `ring/ring-defaults` (+ ring-core/codec/ssl/headers)    |
| Crypto         | git — `jolt-lang/jolt-crypto` (OpenSSL, for sessions/CSRF)    |
| Routing        | git — `metosin/reitit` (+ `jolt-lang/router` Trie mirror)     |
| Templates      | git — `yogthos/Selmer`                                        |
| Date/time      | git — `juxt/tick` (over jolt's `java.time`)                   |
| SQL            | git — `com.github.seancorfield/honeysql`                      |
| Lifecycle      | git — `weavejester/integrant` (+ `weavejester/dependency`)   |
| Logging        | git — `org.clojure/tools.logging` (jolt-lang/logging port)    |

## Standalone binary

`joltc build` compiles the app and every library into one executable that loads
its native libs (libc sockets, libsqlite3) at startup, so it runs with no jolt or
Chez on the path:

```
joltc build -m app.core               # -> target/release/ring-app
joltc build -m app.core --dev         # -> target/debug/ring-app
PORT=8080 ./target/release/ring-app
```

Output goes under `target/`, cargo-style: `target/release` by default (and with
`--opt`), `target/debug` with `--dev`; the binary is named after the project dir.
`-o PATH` overrides (relative to the project). A standalone build needs Chez's
kernel dev files (`libkernel.a` + `scheme.h`) and a C compiler; set
`JOLT_CHEZ_CSV` to the `csv<ver>/<machine>` dir if it isn't auto-detected.
`config.edn` is read at runtime (via `io/file`), so keep it next to the binary and
edit it without rebuilding.

The Selmer template is a resource loaded through `io/resource`. There are two ways
to ship it, selected in `deps.edn`:

**Embedded — a single self-contained file.** With

```clojure
:jolt/build {:embed ["resources"]}
```

`joltc build` bakes everything under `resources/` into the binary. It runs from
any directory with no `resources/` dir present:

```
mkdir /tmp/deploy && cp target/release/ring-app config.edn /tmp/deploy/
cd /tmp/deploy && ./ring-app          # serves; the template is in the binary
```

**Alongside — ship the binary with its `resources/` dir.** Omit `:jolt/build`;
`io/resource` then resolves at runtime against the working dir (`JOLT_PWD`, else
cwd). Ship `resources/` next to the binary:

```
cp -r target/release/ring-app config.edn resources /tmp/deploy/
cd /tmp/deploy && ./ring-app          # reads templates/index.html from resources/
```

Without that dir an `io/resource` read returns nil — here the page 500s. (A
resource read at namespace *load* time, rather than per request, is evaluated
during the build and captured into the binary regardless of mode; ring-app loads
the template lazily on first render so the two modes stay distinct.)

## Requirements

- `joltc` on PATH, the system `libsqlite3` (preinstalled on macOS and most Linux
  distros), and OpenSSL (`libssl`/`libcrypto`) for jolt-crypto.

## Notes

This is the Chez port. reitit and tick read their `:clj` branches, so those
requires are scoped to `:clj` reader features (see `app.core`); everything else
runs under jolt's default feature set. ring-defaults' session-cookie store needs
`javax.crypto` at load, so `app.core` requires `jolt.crypto` first. config.edn is an Integrant graph wired with `#ig/ref`;
`-main` starts the system with `ig/init`, and the test drives an in-memory
system through the same keys, hitting the live `:app/server` over HTTP.
