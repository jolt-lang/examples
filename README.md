# Jolt examples

Sample projects for [Jolt](https://github.com/jolt-lang/jolt).

- [greeter](greeter/) — pulls Clojure libraries (Selmer, yogthos/config) from
  GitHub via `deps.edn`, serves an nREPL you can connect an editor to, and
  compiles the app plus its dependencies into a single native executable.
- [ray-tracer](ray-tracer/) — jank's ray tracer benchmark ported to jolt, with
  jank/JVM-Clojure/jolt performance comparison and a breakdown of where the
  time goes.
- [commonmark-app](commonmark-app/) — a dependency-free Markdown → HTML renderer
  in pure Clojure (no JVM, no deps). CommonMark Java can't run on jolt, so this
  is a self-contained parser; building it surfaced several jolt bugs.
