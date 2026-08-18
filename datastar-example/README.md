# datastar-example

A datastar demo on jolt: a counter and a greeting list, live-updated over
Server-Sent Events. The page state lives in a **glimmer** ratom (reagent-style
reactive atom) server-side; the **jolt-lang/datastar** middleware opens an SSE
stream that re-renders the page fragment whenever the atom changes, and patches
it in with `datastar-patch-elements` events — server-side reactivity in the
reagent style, no page reloads.

```
jolt serve                     # listen on config.edn's :port (3000)
jolt -M:test                   # handler + SSE tests
```

Then visit <http://127.0.0.1:3000>: click `+`/`−` to change the count, type a
name and hit Greet. Every action is a datastar `@get` that carries the signals
to the server; the server updates the ratom and replies with a
`datastar-patch-signals` JSON body, while the open SSE stream pushes the
re-rendered fragment. The vendored client is the official datastar
[`v1.0.2` bundle](https://github.com/starfederation/datastar) at
`resources/public/js/datastar.js`.

## How it's wired

- `app.core/state` — one glimmer ratom holding `:count`, `:name`, `:greetings`.
- `app.core/app` — the ring handler: full page for `/`, the fragment for SSE
  requests (the middleware flags them with `:jolt.datastar/sse-request`), the
  action routes (`/count/inc`, `/count/dec`, `/greet`), and the vendored JS.
- `ds/wrap-datastar` — the middleware entry point: parses signals into
  `:jolt.datastar/signals`, detects SSE requests (`datastar-sse=true`), and
  streams `datastar-patch-elements` events for every ratom change.
- `ds/init-opts` — emits the page's `data-signals` (seeded state + per-tab id)
  and `data-init` (opens the SSE stream on `#app`).

The datastar library lives at `../../datastar` and is pulled in with
`:local/root` (it has no git remote yet); jolt expands its deps — glimmer,
`clojure.data.json`, jolt-lang/time — transitively.

## Standalone binary

```
jolt build -m app.core               # -> target/release/datastar-example
```

With `:jolt/build {:embed ["resources"]}` the vendored `datastar.js` is baked
into the binary. `config.edn` is read at runtime, so keep it next to the binary.
