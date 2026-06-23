# ring-app

A guestbook web app on jolt running the real JVM-style stack, every library
pulled from its git repo and run unchanged: **ring-core** middleware (params,
keyword-params), **reitit** routing, **Selmer** HTML templates, **honeysql**
queries and **yogthos/config**. It's served by the **ring-chez-adapter** HTTP
server (a minimal HTTP/1.1 server over BSD sockets via Chez's FFI), over a
**SQLite** guestbook through `jdbc.core` (the **jolt-lang/db** API over
`libsqlite3`). Logging is `clojure.tools.logging`.

```
joltc run                       # listens on config.edn's :port (3000)
PORT=8080 joltc run             # config.core/env: env beats config.edn
joltc -M:test                   # rendering, routing, middleware, the guestbook
```

Then visit <http://127.0.0.1:3000>, sign the guestbook, and watch the request log.

## Libraries

Every concern is a git library run unchanged; jolt itself provides only the
FFI + runtime. Git deps are fetched once into `~/.jolt/gitlibs`.

| Concern        | Source                                                        |
| ---            | ---                                                           |
| HTTP server    | git — `jolt-lang/ring-chez-adapter` (BSD sockets via FFI)     |
| Database       | git — `jolt-lang/db` / `jdbc.core` (libsqlite3 via FFI)       |
| Middleware     | git — `ring/ring-core`, `ring/ring-codec`                     |
| Routing        | git — `metosin/reitit` (+ `jolt-lang/router` Trie mirror)     |
| Templates      | git — `yogthos/Selmer`                                        |
| SQL            | git — `com.github.seancorfield/honeysql`                      |
| Config         | git — `yogthos/config`                                        |
| Logging        | git — `org.clojure/tools.logging` (jolt-lang/logging port)    |

## Standalone binary

`joltc build` compiles the app and every library into one executable:

```
joltc build -m app.core -o ring-app
./ring-app                      # serves on config.edn's :port
PORT=8080 ./ring-app
```

The binary embeds the libraries and the Selmer template (`deps.edn`
`:jolt/build {:embed ["resources"]}`), and loads its native libs (libc sockets,
libsqlite3) at startup. `config.edn` is read at runtime, so keep it next to the
binary and edit it without rebuilding. A standalone build needs Chez's kernel dev
files (`libkernel.a` + `scheme.h`) and a C compiler; set `JOLT_CHEZ_CSV` to the
`csv<ver>/<machine>` dir if it isn't auto-detected.

## Requirements

- `joltc` on PATH, and the system `libsqlite3` (preinstalled on macOS and most
  Linux distros).

## Notes

This is the Chez port. reitit reads its `:clj` branches, so the require is scoped
to `:clj` reader features (see `app.core`); everything else runs under jolt's
default feature set. clj-http-lite (used only by the test to drive the live
server) throws on 4xx/5xx by default, so the test passes `:throw-exceptions
false` to inspect the 404.
