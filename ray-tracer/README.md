# ray-tracer

jank's ray tracer benchmark ([gist](https://gist.github.com/jeaye/6312e8f951c9564866a246fdd4dca835),
adapted from [Ray Tracing in One Weekend](https://raytracing.github.io/books/RayTracingInOneWeekend.html)),
ported to jolt. It renders a 100×56 scene of ~200 spheres with lambertian,
metal and dielectric materials at 2 samples/pixel and 10 ray bounces — all
naive Clojure: every vector is a `{:r :g :b}` map, every ray a map, every
hit a map. It stress-tests exactly what a Clojure runtime does all day:
small-map allocation, keyword lookup, boxed float arithmetic and non-tail
recursion.

jank used this program to drive the optimization work in
[their June 2026 post](https://jank-lang.org/blog/2026-06-01-optimization/)
(NaN boxing, virtual dispatch replacing `dynamic_call`, inlined keyword map
lookup). The port collapses the gist's `#?(:clj/:jank)` conditionals to
jolt's `Math/` host shims and swaps the criterium/jank.perf harness for a
plain wall-clock loop.

## Run

```bash
jolt-deps -e "(require '[ray]) (ray/bench 3)"   # ~15 min, see below
```

To verify the render, enable the `print+space` bodies and the PPM header
lines, redirect to a `.ppm`, and compare against the same render on JVM
Clojure — image statistics (mean/stdev of pixel values) come out identical
within sampling noise.

## Where jolt stands

Apple M1 Max, same source, mean of timed runs after warmup:

| runtime | per render | vs JVM |
|---|---|---|
| jank 0.1-alpha, `-O3 -Odirect-call` | 1.08 s | 0.8× |
| Clojure JVM 1.12 | 1.40 s | 1.0× |
| jank 0.1-alpha, no flags | 14.5 s | 10× |
| jolt (compile mode) | 15.8 s | 11× |

jolt's first run of this benchmark took 165.6 s (118× JVM). The profile it
produced drove a round of runtime optimizations (jolt PR #91 — inlined
keyword lookup, inlined map-literal construction, `clojure.math` backed by
Janet natives, indexed reduce) that mirror jank's own optimization series,
for a 10.5× improvement. Needs a jolt built after that PR.

(The blog post reports jank 2.37 s vs Clojure 2.53 s on the author's
machine — same ranking as the optimized rows here. jank's `jank.perf`
harness fails against current homebrew bdwgc headers, so all rows use the
same `time`-loop protocol.)

## Why, concretely

Micro-benchmarks of this workload's hot operations on jolt (compiled
`loop/recur`, ns/iteration; empty loop ≈ 27 ns), before and after the
optimization round this benchmark drove:

| op | before | after | jank's analogous fix |
|---|---|---|---|
| `(:r v)` | ~930 ns | ~90 ns | inlined keyword map lookup |
| `{:r 1.5 :g 2.5 :b 3.5}` | ~890 ns | ~250 ns | NaN boxing / call-free construction |
| `sqrt` | ~5,000 ns (`Math/` interop) | ~30 ns (`clojure.math`) | native math |

The costs map one-to-one onto the jank post's profiling result (map
creation, keyword lookup, arithmetic/dispatch). `Math/sqrt`-style interop
forms remain in the compiler's frozen interpret-only punt set — the port
uses `clojure.math` (Clojure 1.11), which jolt backs directly with Janet's
math natives so calls compile and direct-link.

The remaining ~11× over the JVM is the floor of the current object model:
a Janet struct allocation per vec3 op and a guard-plus-opcode-get per
keyword access, where the JVM JIT escapes most of the allocations
entirely. Closing that gap is jank-style object-model work (jolt-4vr has
the notes).
