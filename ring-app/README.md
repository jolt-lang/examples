# ring-app

A guestbook web app on jolt running the real JVM-style stack, every library
pulled from its git repo and run unchanged: **ring-core** middleware (params,
keyword-params), **reitit** routing, **Selmer** HTML templates, **honeysql**
queries and **yogthos/config**. It's served by jolt's **built-in HTTP server** (a
minimal HTTP/1.1 server over BSD sockets via Chez's FFI), over a **SQLite**
guestbook through jolt's built-in `jdbc.core` (the jolt-lang/db API over
`libsqlite3`). Logging is `clojure.tools.logging`.

```
joltc run                       # listens on config.edn's :port (3000)
PORT=8080 joltc run             # config.core/env: env beats config.edn
joltc -M:test                   # rendering, routing, middleware, the guestbook
```

Then visit <http://127.0.0.1:3000>, sign the guestbook, and watch the request log.

## What's built into jolt vs pulled from git

| Concern        | Source                                                        |
| ---            | ---                                                           |
| HTTP server    | built in — `jolt.http.server/run-server` (BSD sockets, FFI) |
| Database       | built in — `jdbc.core` (the system libsqlite3, FFI)          |
| Middleware     | git — `ring/ring-core`, `ring/ring-codec`                     |
| Routing        | git — `metosin/reitit` (+ `jolt-lang/router` Trie mirror)     |
| Templates      | git — `yogthos/Selmer`                                        |
| SQL            | git — `com.github.seancorfield/honeysql`                      |
| Config         | git — `yogthos/config`                                        |
| Logging        | git — `org.clojure/tools.logging` (jolt-lang/logging port)    |

Git deps are fetched once into `~/.jolt/gitlibs`.

## Requirements

- `joltc` on PATH, and the system `libsqlite3` (preinstalled on macOS and most
  Linux distros).

## Notes

This is the Chez port. The HTTP server and JDBC execution are jolt built-ins
(BSD-socket adapter + `libsqlite3`); everything else — reitit routing, Selmer
templates, honeysql query generation, ring middleware, config — is the real
library pulled from git and run unchanged. reitit reads its `:clj` branches, so
the require is scoped to `:clj` reader features (see `app.core`); the rest run
under jolt's default features.
