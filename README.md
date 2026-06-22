# Jolt examples

Sample projects for [Jolt](https://github.com/jolt-lang/jolt), running on the
Chez Scheme substrate. Each project has a `deps.edn`; run them with the `joltc`
binary, e.g. `joltc run -m app.core` or `joltc -M:alias`.

Dependencies are pulled straight from their git repos (no Maven) into a cache at
`~/.jolt/gitlibs`. The HTTP client, HTTP server, SQLite and PNG output are built
into jolt over the system's native libraries (curl, BSD sockets, libsqlite3) via
Chez's FFI.

- [commonmark-app](commonmark-app/) — a dependency-free Markdown → HTML renderer
  in pure Clojure (no JVM, no deps). A self-contained CommonMark subset; building
  it surfaced several jolt fixes. `joltc run -m app.core`, `joltc test`.
- [hiccup-app](hiccup-app/) — renders HTML from Clojure data with
  [weavejester/hiccup](https://github.com/weavejester/hiccup), pulled from git.
- [markdown-app](markdown-app/) — Markdown → HTML with
  [yogthos/markdown-clj](https://github.com/yogthos/markdown-clj) from git.
- [ray-tracer](ray-tracer/) — jank's ray tracer benchmark ported to jolt
  (all-maps vs typed-record variants), a compute benchmark. `joltc -M:baseline N`.
- [ray-tracer-multi](ray-tracer-multi/) — the typed ray tracer across five
  namespaces; renders a PNG with the built-in `jolt.png`. `joltc -M:run render`.
- [http-client-app](http-client-app/) — jolt's built-in HTTP client
  (`jolt.http-client`, over the system curl) against real HTTPS endpoints: TLS,
  redirects, gzip, query params, JSON POST.
- [ring-app](ring-app/) — a guestbook web app on the real JVM-style stack:
  ring-core middleware + reitit routing + Selmer templates + honeysql + yogthos/
  config (all from git, run unchanged) on jolt's built-in HTTP server (BSD sockets
  via FFI) with a SQLite guestbook (jolt's built-in `jdbc.core`/libsqlite3).
  `joltc run`, `joltc -M:test`.
- [malli-app](malli-app/) — [metosin/malli](https://github.com/metosin/malli)
  schema validation. Loads on jolt; a few deeply JVM-coupled internals are still
  being worked through.
