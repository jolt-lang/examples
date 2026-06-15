# malli-app

Runs [metosin/malli](https://github.com/metosin/malli) on jolt for data schema
validation.

```
jolt-deps run -m app.core
```

(set `JOLT_FEATURES=clj,jolt,default` so malli's `.cljc` sources read their
`:clj` branches.)

## Status

`m/validate` and `m/explain` both work across the schema vocabulary: predicates
(`int?`), keyword schemas (`:int`, `:string`, `:keyword`), `:map` (incl. nested
and `{:optional true}` entries), `:vector`, `:tuple`, `:enum`, `:maybe`, `:and`,
`:or`, `:re`, and bounded `[:int {:min .. :max ..}]` / `[:string {:min ..}]`.
`m/explain` returns the detailed error paths (and `nil` for a valid value).

## Notes

- malli is heavily JVM-coupled (protocols/records, `clojure.lang` interop,
  `java.util.HashMap`, a regex registry key). The `shims/borkdude/dynaload.clj`
  stub satisfies malli's only load-time external require (`dynaload` is used to
  lazily locate optional deps like sci/test.check, which aren't present here).
- Bringing this up surfaced and fixed several general jolt bugs: `extend-type`
  and `reify` with multiple protocols, destructuring in protocol method
  params, `instance?` of a protocol (works like `satisfies?`), `@x` expanding
  to the qualified `clojure.core/deref` (so it survives `:refer-clojure
  :exclude [deref]`), Java collection interop (`.nth`/`.count`/`.valAt`/…) on
  jolt collections, and `java.util.HashMap`'s capacity/load-factor constructors
  plus `.putAll`.
