# Jolt examples

Sample projects for [Jolt](https://github.com/jolt-lang/jolt), running on the
Chez Scheme substrate. Each project has a `deps.edn`; run them with the `jolt`
binary, e.g. `jolt run -m app.core` or `jolt -M:alias`.

Dependencies are pulled straight from their git repos (no Maven) into a cache at
`~/.jolt/gitlibs`. The HTTP client, HTTP server, SQLite and PNG output are built
into jolt over the system's native libraries (curl, BSD sockets, libsqlite3) via
Chez's FFI.

- [commonmark-app](commonmark-app/) — a dependency-free Markdown → HTML renderer
  in pure Clojure (no JVM, no deps). A self-contained CommonMark subset; building
  it surfaced several jolt fixes. `jolt run -m app.core`, `jolt test`.
- [hiccup-app](hiccup-app/) — renders HTML from Clojure data with
  [weavejester/hiccup](https://github.com/weavejester/hiccup), pulled from git.
- [markdown-app](markdown-app/) — Markdown → HTML with
  [yogthos/markdown-clj](https://github.com/yogthos/markdown-clj) from git.
- [ray-tracer](ray-tracer/) — jank's ray tracer benchmark ported to jolt
  (all-maps vs typed-record variants), a compute benchmark. `jolt -M:baseline N`.
- [ray-tracer-multi](ray-tracer-multi/) — the typed ray tracer across five
  namespaces; renders a PNG with the built-in `jolt.png`. `jolt -M:run render`.
- [http-client-app](http-client-app/) — jolt's built-in HTTP client
  (`jolt.http-client`, over the system curl) against real HTTPS endpoints: TLS,
  redirects, gzip, query params, JSON POST.
- [ring-app](ring-app/) — a guestbook web app on the real JVM-style stack:
  ring-core middleware + reitit routing + Selmer templates + honeysql + yogthos/
  config (all from git, run unchanged) on jolt's built-in HTTP server (BSD sockets
  via FFI) with a SQLite guestbook (jolt's built-in `jdbc.core`/libsqlite3).
  `jolt run`, `jolt -M:test`.
- [malli-app](malli-app/) — [metosin/malli](https://github.com/metosin/malli)
  schema validation, pulled from git. `m/validate`/`m/explain` across the schema
  vocabulary (predicates, `:map`, `:vector`, `:enum`, bounded `:int`, …).
- [ffi-examples](ffi-examples/) — three programs calling C libraries directly
  over their ABI with `jolt.ffi`: libc's `div()` returning a struct by value,
  libffi driven through jolt.ffi so a call's signature can be built at runtime,
  and an arpeggio from a PortAudio realtime audio callback. `jolt check`,
  `jolt structs`, `jolt libffi`, `jolt portaudio`.
- [nrepl-example](nrepl-example/) — a jolt app that starts an nREPL server and
  parks, so you can connect an editor and drive the live process (inspect state,
  mutate it, reload code). The shape of a binary deployed to, say, a Raspberry Pi
  that you reach into over nREPL. Runs interpreted or compiled to a standalone
  binary. `jolt run -m app.core`, `jolt build -m app.core -o nrepl-example`.
