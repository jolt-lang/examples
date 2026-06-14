# ray-tracer

jank's ray tracer benchmark ([gist](https://gist.github.com/jeaye/6312e8f951c9564866a246fdd4dca835),
adapted from [Ray Tracing in One Weekend](https://raytracing.github.io/books/RayTracingInOneWeekend.html)),
ported to jolt. It renders a 100×56 scene of ~200 spheres with lambertian,
metal and dielectric materials at 2 samples/pixel and 10 ray bounces. It
stress-tests exactly what a Clojure runtime does all day: small-aggregate
allocation, keyword lookup, boxed float arithmetic and non-tail recursion.

jank used this program to drive the optimization work in
[their June 2026 post](https://jank-lang.org/blog/2026-06-01-optimization/)
(NaN boxing, virtual dispatch replacing `dynamic_call`, inlined keyword map
lookup). The port collapses the gist's `#?(:clj/:jank)` conditionals to
jolt's `clojure.math` shims and swaps the criterium/jank.perf harness for a
plain wall-clock loop.

## Two variants

There are two implementations of the same renderer, to measure what a typed
data model buys:

- **`ray_baseline.clj`** (ns `ray-baseline`) — the naive port: every vector is
  a `{:r :g :b}` map, every ray/hit/sphere a map, materials are maps carrying a
  `:scatter` closure. This is the original jank-gist style.
- **`ray_typed.clj`** (ns `ray-typed`) — the same program with every data
  structure a `defrecord` (Vec3, Ray, HitInfo, Sphere, the scatter result, the
  hit accumulator), field type hints (`^Vec3`) so a vec read back out of a
  ray/hit keeps its type across fn boundaries, and the three materials behind a
  `Scatter` protocol instead of a stored `:scatter` closure — so dispatch is a
  real protocol call (devirtualizable on a proven receiver) and the hot vec
  math inside each scatter impl reads its fields at the method's own
  statically-known type.

## Run

```bash
# all-maps baseline
jolt-deps -M:baseline 3

# typed records (direct-linked: records + the structural type inference)
JOLT_DIRECT_LINK=1 jolt-deps -M:typed 3

# typed + whole-program (closed-world inference + var const-linking)
JOLT_DIRECT_LINK=1 JOLT_WHOLE_PROGRAM=1 jolt-deps -M:typed 3
```

The `:baseline` / `:typed` deps.edn aliases run `NS/-main` with the trailing
arg as the count of timed renders (after 2 warmups). Whole-program inference
only engages on the `-m`/`-M` entry (it runs one closed-world fixpoint over all
loaded namespaces before `-main`), not on a bare `-e` require.

To verify a render, enable the `print+space` bodies and the PPM header lines,
redirect to a `.ppm`, and compare against the same render on JVM Clojure —
image statistics (mean/stdev of pixel values) come out identical within
sampling noise. Both variants produce the same image.

## Where jolt stands

Apple M1 Max, same source, clean serial runs, mean of timed runs after warmup,
all measured in one session (so the ratios are apples-to-apples):

| variant / mode | per render | vs baseline |
|---|---|---|
| baseline (all maps), default compile | 26.9 s | — |
| baseline, `JOLT_DIRECT_LINK=1` | 22.7 s | 1.0× |
| **`ray_typed`, `JOLT_DIRECT_LINK=1`** | **8.6 s** | **2.65×** |
| `ray_typed`, `+ JOLT_WHOLE_PROGRAM=1` | 8.4 s | 2.70× |

The headline: **moving the data model from maps to records cuts the render by
2.65×.** Maps pay a keyword-hash lookup and a sorted-or-hashed allocation per
vec op; records lay the fields out in declared order, read them by index, and
construct ~2× cheaper. The material `Scatter` protocol turns what was a stored
closure call into a devirtualizable protocol dispatch, and the field hints keep
nested vecs typed so the inner arithmetic proves its reads.

Whole-program optimization is roughly neutral on this program because it is a
single namespace: its main lever — direct-linking native `clojure.math/sqrt`
(a cfunction, called every bounce) and const-linking stable vars instead of a
per-call cell deref — already applies under plain `JOLT_DIRECT_LINK=1`. The
remaining whole-program-only wins (cross-namespace param-type propagation,
const-linking `^:redef`/data vars) have nothing to bite on here; they pay off
when a program spans namespaces.

For external context, an earlier session on the same machine measured jank
0.1-alpha `-O3 -Odirect-call` at 1.17 s and Clojure JVM 1.12 at 1.44 s on the
naive map version. That session ran ~1.55× faster than this one (its jolt
default was 17.3 s vs 26.9 s here), so normalizing `ray_typed`'s 8.6 s to it
lands around ~5.5 s — roughly 3.8× JVM, down from the ~8.5× the all-maps port
sat at. The remaining gap is the floor of a bytecode interpreter and a Janet
allocation per vec op, where the JIT/AOT competitors run native code and escape
most allocations.

## Why, concretely

The costs map one-to-one onto the jank post's profiling result (map creation,
keyword lookup, arithmetic/dispatch). The baseline port already used jolt's
PR #91 optimizations (inlined keyword lookup, inlined map-literal construction,
`clojure.math` over `Math/` interop, indexed reduce) and RFC 0005 structural
inference (`JOLT_DIRECT_LINK=1`) to get the map version to ~22.7 s. The typed
variant is the next step the records pivot opened: a fixed-shape record removes
the per-op map machinery the inference could only partly hide, and the protocol
materials remove the closure-in-a-map dispatch entirely.

What records do *not* fix here: a sphere `:center` is read from a `reduce`-
iterated world list, so without vector-element-type tracking through fn
boundaries it stays `:any`, and the shared vec ops widen accordingly — which is
why the win is 2.65× and not larger. Closing that needs element-type
propagation through calls (tracked separately).
