# ring-app

A guestbook web app on jolt: **ring-core** middleware (params, keyword-params),
**hiccup** HTML and **yogthos/config** — all pulled from their git repos — served
by jolt's **built-in HTTP server** (a minimal HTTP/1.1 server over BSD sockets via
Chez's FFI), with a **SQLite** guestbook (jolt's built-in `libsqlite3` binding).
Routing is a small handler; SQL lives in `app.db`; logging is `clojure.tools.logging`.

```
joltc run                       # listens on config.edn's :port (3000)
PORT=8080 joltc run             # config.core/env: env beats config.edn
joltc -M:test                   # rendering, routing, middleware, the guestbook
```

Then visit <http://127.0.0.1:3000>, sign the guestbook, and watch the request log.

## What's built into jolt vs pulled from git

| Concern        | Source                                                        |
| ---            | ---                                                           |
| HTTP server    | built in — `ring-janet.adapter/run-server` (BSD sockets, FFI) |
| Database       | built in — `jolt.sqlite` (the system libsqlite3, FFI)         |
| Middleware     | git — `ring/ring-core`, `ring/ring-codec`                     |
| HTML           | git — `weavejester/hiccup`                                    |
| Config         | git — `yogthos/config`                                        |
| Logging        | git — `org.clojure/tools.logging` (jolt-lang/logging port)    |

Git deps are fetched once into `~/.jolt/gitlibs`.

## Requirements

- `joltc` on PATH, and the system `libsqlite3` (preinstalled on macOS and most
  Linux distros).

## Notes

This is the Chez port. The original JVM-style stack used reitit for routing,
honeysql/jdbc for SQL and Selmer for templating; on jolt-on-Chez those have
remaining compatibility gaps, so this version uses a small handler, direct SQL
through `jolt.sqlite`, and hiccup — a faithful, fully working guestbook on the
native stack.
