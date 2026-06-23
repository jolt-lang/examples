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

`joltc build` compiles the app and every library into one executable that loads
its native libs (libc sockets, libsqlite3) at startup, so it runs with no jolt or
Chez on the path:

```
joltc build -m app.core -o ring-app
PORT=8080 ./ring-app
```

A standalone build needs Chez's kernel dev files (`libkernel.a` + `scheme.h`) and
a C compiler; set `JOLT_CHEZ_CSV` to the `csv<ver>/<machine>` dir if it isn't
auto-detected. `config.edn` is read at runtime (via `io/file`), so keep it next to
the binary and edit it without rebuilding.

The Selmer template is a resource loaded through `io/resource`. There are two ways
to ship it, selected in `deps.edn`:

**Embedded — a single self-contained file.** With

```clojure
:jolt/build {:embed ["resources"]}
```

`joltc build` bakes everything under `resources/` into the binary. It runs from
any directory with no `resources/` dir present:

```
mkdir /tmp/deploy && cp ring-app config.edn /tmp/deploy/
cd /tmp/deploy && ./ring-app          # serves; the template is in the binary
```

**Alongside — ship the binary with its `resources/` dir.** Omit `:jolt/build`;
`io/resource` then resolves at runtime against the working dir (`JOLT_PWD`, else
cwd). Ship `resources/` next to the binary:

```
cp -r ring-app config.edn resources /tmp/deploy/
cd /tmp/deploy && ./ring-app          # reads templates/index.html from resources/
```

Without that dir an `io/resource` read returns nil — here the page 500s. (A
resource read at namespace *load* time, rather than per request, is evaluated
during the build and captured into the binary regardless of mode; ring-app loads
the template lazily on first render so the two modes stay distinct.)

## Requirements

- `joltc` on PATH, and the system `libsqlite3` (preinstalled on macOS and most
  Linux distros).

## Notes

This is the Chez port. reitit reads its `:clj` branches, so the require is scoped
to `:clj` reader features (see `app.core`); everything else runs under jolt's
default feature set. clj-http-lite (used only by the test to drive the live
server) throws on 4xx/5xx by default, so the test passes `:throw-exceptions
false` to inspect the 404.
