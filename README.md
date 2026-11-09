# Jolt examples

Sample projects for [Jolt](https://github.com/jolt-lang/jolt), running on the
Chez Scheme substrate. Each project has a `deps.edn`; run them with the `jolt`
binary, e.g. `jolt run -m app.core` or `jolt -M:alias`.

Dependencies are pulled from their git repos into a cache at `~/.jolt/gitlibs`
(Maven coordinates also resolve, into `~/.m2`). The HTTP client
([jolt-lang/http-client](https://github.com/jolt-lang/http-client)), HTTP server
([jolt-lang/ring-chez-adapter](https://github.com/jolt-lang/ring-chez-adapter))
and SQLite ([jolt-lang/db](https://github.com/jolt-lang/db)) are git
dependencies that bind the system's native libraries (BSD sockets, OpenSSL,
libsqlite3) through `jolt.ffi`. PNG output (`jolt.png`) is built into jolt.

Projects with a `start` or `serve` task run it with `jolt start` / `jolt serve`.
A task can't be named `run` or `build`: jolt's own commands take those names.

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
- [http-client-app](http-client-app/) — the
  [jolt-lang/http-client](https://github.com/jolt-lang/http-client) library
  (`jolt.http-client`) against real HTTPS endpoints: TLS, redirects, gzip, query
  params, JSON POST.
- [ring-app](ring-app/) — a guestbook web app on the real JVM-style stack:
  ring-core middleware + reitit routing + Selmer templates + honeysql + yogthos/
  config (all from git, run unchanged) on ring-chez-adapter (BSD sockets via
  FFI) with a SQLite guestbook (jolt-lang/db's `jdbc.core` over libsqlite3).
  `jolt serve`, `jolt -M:test`.
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
- [basic-example](basic-example/) — the smallest project: a `-main` that prints
  a greeting, plus the build settings for a standalone binary. `jolt start`,
  `jolt build -m app.core`.
- [glimmer-app](glimmer-app/) — a reactive task board on
  [glimmer](https://github.com/jolt-lang/glimmer) and its GTK4 backend: ratoms,
  cursors, reactions, keyed lists. Needs GTK4. `jolt start`.
- [todomvc-uikit](todomvc-uikit/) — TodoMVC on glimmer's native macOS backend,
  [glimmer-appkit](https://github.com/jolt-lang/glimmer-appkit) (the name is
  historical; it runs on the Mac). `jolt start`.
- [glimmer-tui-example](glimmer-tui-example/) — a terminal app on
  [glimmer-tui](https://github.com/jolt-lang/glimmer-tui) over ncurses: a
  filterable list, detail pane, table, progress bar and modal. `jolt start`.
- [glimmer-gl-app](glimmer-gl-app/) — a lit cathedral with real-time shadows in
  a GTK4 `GtkGLArea`, driven by a glimmer control panel, on
  [glimmer-gl](https://github.com/jolt-lang/glimmer-gl). `jolt start`.
- [fps-demo](fps-demo/) — a first-person walk through the q1k3 level, a port of
  the q1k3 JavaScript FPS to glimmer-gl: player physics, collision, enemies.
  `jolt start`.
- [image-dump-example](image-dump-example/) — a glimmer todo board that saves
  and reloads the whole running program with
  [`jolt.image`](https://jolt-lang.github.io/docs/rfc/0009-program-image-dump-restore.html).
  `jolt start`.
- [glimmer-datastar](glimmer-datastar/) — a counter and greeting list in the
  browser, kept live over SSE by
  [glimmer-datastar](https://github.com/jolt-lang/glimmer-datastar) from a
  server-side glimmer ratom, on ring-chez-adapter. `jolt serve`, `jolt -M:test`.
- [reactive-dashboard](reactive-dashboard/) — a live system monitor in the
  browser: [ebb](https://github.com/jlt-commons/ebb) streams, a
  [domino](https://github.com/domino-clj/domino) state graph and
  glimmer-datastar rendering. Reads `/proc`, so it shows real figures on Linux.
  `jolt serve`, `jolt -M:test`.
