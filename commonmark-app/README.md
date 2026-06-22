# commonmark-app

A compact, **dependency-free** Markdown → HTML renderer running on Jolt. Pure
Clojure (`clojure.string` only) — no JVM, no external deps.

```
joltc run -m app.core          # render a sample document
joltc run -m app.commonmark-test   # run the test suite (38 cases)
```

## Why this and not org.commonmark/commonmark

`org.commonmark/commonmark` is Atlassian's CommonMark **Java** library — compiled
Java bytecode used through interop. Jolt has no JVM and cannot load Java jars, so
that library can't run here. The pure-Clojure CommonMark parser `marchio` exists
but drags in `kern`, `fluokitten` and `cheshire` (all dead and/or Java-backed)
and is itself incomplete. So this example is a small, self-contained parser
written for the occasion rather than a port — it shows the kind of text-wrangling
Clojure code Jolt runs well, and it shook out several Jolt bugs (below).

## Supported

Block level: ATX headings (`#`–`######`), setext headings, paragraphs, thematic
breaks, fenced code (``` ``` ``` / `~~~`, with info string), indented code,
blockquotes, ordered/unordered lists with nesting and tight/loose detection.

Inline: `*`/`_` emphasis and `**`/`__` strong (with flanking + rule-of-three),
backtick code spans, `[text](url "title")` links, `![alt](url)` images, `<url>`
and `<email>` autolinks, raw inline HTML, backslash escapes, hard breaks
(two trailing spaces or a trailing `\`), and HTML escaping of `& < > "`.

## Not supported

It targets the common subset, not the full CommonMark spec suite. Notably:
reference links/definitions, HTML blocks, list-item lazy continuation across
blank lines, link-reference titles spanning lines, and the long tail of emphasis
edge cases. Tabs are expanded to 4 spaces.

## Jolt bugs surfaced and fixed

Building this turned up five Jolt divergences from Clojure. All were fixed in
Jolt (PR #158), so the source now uses the canonical Clojure forms:

- **Regex compiler hung** on concatenated nested bounded quantifiers — the
  canonical email-autolink pattern `…{0,61}…{0,61}…` never returned from compile
  (the `{n,m}` desugaring expanded to ~2ⁿ PEG nodes).
- **Backreferences** (`\1`) weren't honored — the thematic-break pattern
  `([-*_])(?:\1…)` matched nothing.
- **String wasn't a seqable of chars** consistently: `(set "ab")` threw,
  `(vec "ab")` yielded `["a" "b"]` (strings), `(into #{} "ab")` yielded
  `#{97 98}` (code points).
- **`(str/split s re -1)`** (negative limit) returned `[]`, and the 2-arg form
  didn't trim trailing empties like Clojure.
- **`System/exit`** was unsupported.
