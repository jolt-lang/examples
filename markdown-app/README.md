# markdown-app

Runs [markdown-clj](https://github.com/yogthos/markdown-clj) on jolt to render
Markdown to HTML.

```
jolt run -m app.core
```

(set `JOLT_FEATURES=clj,jolt,default` so the `.cljc` sources read their `:clj`
branches.)

## Notes

- The dep is pulled by git SHA. markdown-clj's only Maven dependency is
  `clj-yaml`, used solely for optional YAML front-matter (`:parse-meta?`).
  jolt resolves git/local deps only, so `shims/clj_yaml/core.clj` provides a
  tiny stub that satisfies the require; YAML metadata is not supported here.
- markdown-clj is a line-based string transformer (no AST), so a few of its own
  edge cases around directly-adjacent mixed blocks (e.g. a blockquote on the line
  after a list) render with imperfect nesting — that is markdown-clj's behaviour,
  not jolt's. Headings, emphasis, inline code, links, lists, tables and
  strikethrough render correctly.

Bringing this up surfaced and fixed several jolt bugs: `clojure.java.io/writer`
passthrough for a `Writer`, `StringWriter` close under `with-open`,
`java.io.Reader.readLine`, `Writer.write(int)` char-code handling, `drop-while`
over a string, `re-seq` returning `nil` (not `()`) on no match, and the `#()`
reader recognising `%` inside map and set literals.
