# ring-app

A guestbook web app on jolt running the real JVM-style stack, every library
pulled from its git repo and run unchanged: **ring-core** middleware (params,
keyword-params), **reitit** routing, **Selmer** HTML templates, **honeysql**
queries and **yogthos/config**. It's served by **jolt-lang/ring-chez-adapter** (a
minimal HTTP/1.1 server over BSD sockets via jolt.ffi), over a **SQLite**
guestbook through **jolt-lang/db**'s `jdbc.core` (binding `libsqlite3` via
jolt.ffi). Logging is `clojure.tools.logging`.

```
joltc run                       # listens on config.edn's :port (3000)
PORT=8080 joltc run             # config.core/env: env beats config.edn
joltc -M:test                   # rendering, routing, middleware, the guestbook
```

Then visit <http://127.0.0.1:3000>, sign the guestbook, and watch the request log.

## Libraries (all pulled from git)

| Concern        | Source                                                        |
| ---            | ---                                                           |
| HTTP server    | `jolt-lang/ring-chez-adapter` (BSD sockets over jolt.ffi)     |
| Database       | `jolt-lang/db` — `jdbc.core` (libsqlite3 over jolt.ffi)       |
| Middleware     | `ring/ring-core`, `ring/ring-codec`                           |
| Routing        | `metosin/reitit` (+ `jolt-lang/router` Trie mirror)           |
| Templates      | `yogthos/Selmer`                                              |
| SQL            | `com.github.seancorfield/honeysql`                            |
| Config         | `yogthos/config`                                              |
| Logging        | `org.clojure/tools.logging` (jolt-lang/logging port)          |

The two FFI libraries (ring-chez-adapter, db) declare the native libs they bind
in their deps.edn (`:jolt/native`); jolt loads them transitively. Git deps are
fetched once into `~/.jolt/gitlibs`.

## Requirements

- `joltc` on PATH, and the system `libsqlite3` (preinstalled on macOS and most
  Linux distros).

## Notes

This is the Chez port. Every piece — the BSD-socket HTTP server, the libsqlite3
JDBC driver, reitit routing, Selmer templates, honeysql query generation, ring
middleware, config — is a real library pulled from git and run unchanged; jolt
itself ships only the language + jolt.ffi. reitit reads its `:clj` branches, so
the require is scoped to `:clj` reader features (see `app.core`); the rest run
under jolt's default features.
