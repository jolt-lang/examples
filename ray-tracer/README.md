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
| jolt (compile mode) | 165.6 s | 118× |

(The blog post reports jank 2.37 s vs Clojure 2.53 s on the author's
machine — same ranking as the optimized rows here. jank's `jank.perf`
harness fails against current homebrew bdwgc headers, so all rows use the
same `time`-loop protocol.)

## Why, concretely

Micro-benchmarks of this workload's hot operations on jolt (compiled
`loop/recur`, ns/iteration; empty loop ≈ 27 ns):

| op | cost | jank's fix for the same cost |
|---|---|---|
| `{:r 1.5 :g 2.5 :b 3.5}` | ~890 ns | NaN boxing (allocation-free doubles) |
| `(:r v)` | ~930 ns | inlined keyword map lookup |
| `(Math/sqrt x)` via wrapper defn | ~5,000 ns | n/a — jolt interop heads always interpret |

The three costs map one-to-one onto the jank post's profiling result
(map creation, keyword lookup, arithmetic/dispatch). The extra one is
jolt-specific: `.`/`Math/` interop forms are in the compiler's frozen punt
set, so every `sqrt`/`tan`/`pow` call drops into the tree-walking
interpreter — and this scene computes square roots in every bounce. A
compiled fast path for host math (or a `clojure.math` namespace backed by
Janet's `math/` natives) is the cheapest single win this benchmark
suggests, before the deeper object-model work.
