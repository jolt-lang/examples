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
joltc -M:baseline 3

# typed records (direct-linked: records + the structural type inference)
JOLT_DIRECT_LINK=1 joltc -M:typed 3

# typed + whole-program (closed-world inference + var const-linking)
JOLT_DIRECT_LINK=1 JOLT_WHOLE_PROGRAM=1 joltc -M:typed 3
```

The `:baseline` / `:typed` deps.edn aliases run `NS/-main` with the trailing
arg as the count of timed renders (after 2 warmups). Whole-program inference
only engages on the `-m`/`-M` entry (it runs one closed-world fixpoint over all
loaded namespaces before `-main`), not on a bare `-e` require.

To verify a render, enable the `print+space` bodies and the PPM header lines,
redirect to a `.ppm`, and compare against the same render on JVM Clojure —
image statistics (mean/stdev of pixel values) come out identical within
sampling noise. Both variants produce the same image.

## What the data model buys

Absolute timings depend on the machine and the substrate, so the figures here
are illustrative; what is stable is the *ratio* between the two variants run
back-to-back on the same machine.

The headline: **moving the data model from maps to records cuts the render
roughly 2.6×.** Maps pay a keyword-hash lookup and a sorted-or-hashed
allocation per vec op; records lay the fields out in declared order, read them
by index, and construct ~2× cheaper. The material `Scatter` protocol turns what
was a stored closure call into a devirtualizable protocol dispatch, and the
field hints keep nested vecs typed so the inner arithmetic proves its reads.

Whole-program optimization is roughly neutral on this program because it is a
single namespace: its main lever — direct-linking native `clojure.math/sqrt`
(a cfunction, called every bounce) and const-linking stable vars instead of a
per-call cell deref — already applies under plain `JOLT_DIRECT_LINK=1`. The
remaining whole-program-only wins (cross-namespace param-type propagation,
const-linking `^:redef`/data vars) have nothing to bite on here; they pay off
when a program spans namespaces.

For external context, published numbers for the same program on other runtimes
exist (jank 0.1-alpha `-O3 -Odirect-call` and Clojure JVM 1.12 on the naive map
version, both well under 2 s on an M1 Max). Those were measured separately, not
in a current head-to-head, so treat them as background rather than a direct
comparison. The general phenomenon they point at holds regardless of substrate:
a per-vec allocation in the inner loop is the dominant cost, and the data-model
pivot from maps to records is what removes most of it.

## Why, concretely

The costs map one-to-one onto the jank post's profiling result (map creation,
keyword lookup, arithmetic/dispatch). The baseline port already used jolt's
PR #91 optimizations (inlined keyword lookup, inlined map-literal construction,
`clojure.math` over `Math/` interop, indexed reduce) and RFC 0005 structural
inference (`JOLT_DIRECT_LINK=1`) to get the map version to ~22.7 s. The typed
variant is the next step the records pivot opened: a fixed-shape record removes
the per-op map machinery the inference could only partly hide, and the protocol
materials remove the closure-in-a-map dispatch entirely.

The hot per-op costs the records variant builds on (jolt micro-benchmarks,
before/after the PR #91 optimization round this benchmark drove):

| op | before | after | jank's analogous fix |
|---|---|---|---|
| `(:r v)` | ~930 ns | ~90 ns | inlined keyword map lookup |
| `{:r 1.5 :g 2.5 :b 3.5}` | ~890 ns | ~250 ns | NaN boxing / call-free construction |
| `sqrt` | ~5,000 ns (`Math/` interop) | ~30 ns (`clojure.math`) | native math |

Records are the next step past those: a fixed shape stores the keys once and
the values in declared order, so a field read is an index access and
construction is flat — exactly the shape-based-aggregate route the all-maps
version could only point at.

What records do *not* fix here: a sphere `:center` is read from a `reduce`-
iterated world list, so without vector-element-type tracking through fn
boundaries it stays `:any`, and the shared vec ops widen accordingly — which is
why the win is 2.65× and not larger. Closing that needs element-type
propagation through calls (tracked separately). See `../ray-tracer-multi` for
the namespace-boundary version and how `^RecordType` param hints recover it.
