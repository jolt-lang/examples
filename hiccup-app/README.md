# hiccup-app

Runs [weavejester/hiccup](https://github.com/weavejester/hiccup) on jolt to
render HTML from Clojure data structures.

```
jolt-deps run -m app.core
```

## Status

Renders correctly: element tags, attribute maps, nested elements, and `for`
comprehensions inside the markup. hiccup is a good compiler stress test — its
`html` macro pre-compiles the markup at macroexpansion time, emitting
`StringBuilder` interop and `#()` lambdas inside syntax-quotes.

Bringing it up surfaced and fixed three general jolt bugs (jolt-nkx):

- `#()` written inside a syntax-quote had its synthesized params qualified to the
  namespace (an invalid fn param). Params are now auto-gensyms, matching Clojure.
- `String/valueOf` static (hiccup stringifies attribute values with it).
- `(instance? CharSequence s)` now answers true for strings.
