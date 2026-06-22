# ray-tracer-multi

The typed ray tracer (see `../ray-tracer`, the `ray_typed` variant) decomposed
into five namespaces, to measure what crossing namespace boundaries costs and
how to get the performance back:

- **`rt.vec`** — the `Vec3` record and all vector arithmetic + colours.
- **`rt.types`** — `Ray`, `ScatterResult`, `HitInfo`, `Sphere`, `HitAcc`
  records, with `^Vec3` field hints that resolve *across* the boundary to
  `rt.vec`.
- **`rt.material`** — the `Scatter` protocol and the three material records.
- **`rt.scene`** — intersection, the recursive `ray-cast`, scene generation.
- **`rt.render`** — camera, render loop, bench harness.

## The point: cross-namespace type metadata

In the single-namespace version, per-namespace type inference sees every call
site, so it proves every record parameter's type and the hot field reads
bare-index. Split across namespaces, that breaks: `ray-cast`'s `r` parameter is
a `Ray` supplied by a caller in `rt.render`, but `rt.scene` is compiled on its
own and can't see that caller — so `r` stays `:any`, `(:direction r)`
de-specializes, and the whole hot path with it.

There are two ways to give the compiler the type it can no longer infer:

1. **Declare it** — a `^RecordType` parameter hint (`^Ray r`, `^Sphere
   hittable`). These resolve across namespaces (referred *or* aliased) and seed
   the inference directly, so the program stays fast in the normal open-world,
   per-namespace compile. This is what the source here uses.
2. **Whole-program** — `JOLT_WHOLE_PROGRAM=1` runs one closed-world inference
   over every namespace at once, recovering the cross-namespace parameter types
   from the call sites without any hints.

## Run

```bash
# per-namespace compile (open world) — relies on the ^RecordType param hints
JOLT_DIRECT_LINK=1 joltc -M:run 3

# whole-program (closed world) — recovers the same types with or without hints
JOLT_DIRECT_LINK=1 JOLT_WHOLE_PROGRAM=1 joltc -M:run 3
```

## Numbers

Apple M1 Max, one session, mean of 3 timed renders after 2 warmups (the
single-namespace `ray_typed` reference measured in the same session):

| build | per render |
|---|---|
| single-namespace `ray_typed` (reference) | 8.3 s |
| **multi-namespace, NO param hints, per-ns** | **30.4 s** |
| **multi-namespace, param hints, per-ns** | **7.9 s** |
| multi-namespace, param hints, whole-program | 8.1 s |

Splitting the program across namespaces and compiling each on its own is
**3.7× slower** — the boundary erases the parameter types the monolith proved by
seeing all callers. Declaring those types with `^RecordType` param hints brings
it right back (and slightly past the monolith); whole-program optimization gets
there too, from the other direction. Either route makes a cleanly-decomposed
program pay nothing for its module boundaries.

The hints work across namespaces in both spellings — `^Ray` where the type is
`:refer`-ed, and `^t/Ray` where the namespace is `:as`-aliased.
