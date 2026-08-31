# ffi-examples

Three programs that call a C library directly over its ABI through `jolt.ffi`.
No JNI and no JVM: a `defcfn` signature is lowered to a Chez `foreign-procedure`
at compile time, and `ffi/alloc` is malloc.

```
jolt check        load every example, and report which libraries this machine has
jolt structs      libc div() returning a struct by value, benchmarked three ways
jolt libffi       call a function through a signature built at runtime
jolt portaudio    an arpeggio played from a realtime audio callback
```

Each also has an alias, so `jolt -M:structs` is `jolt structs`. Run `jolt check`
first: it says what you can run.

| example | library | what it shows |
| --- | --- | --- |
| `structs` | libc | `ffi/layout` and `[:by-value …]`: a struct returned by value, and what the destination pointer costs |
| `libffi` | libffi | escaping the compile-time signature — describing a call in memory so its shape can be decided at runtime |
| `portaudio` | PortAudio | `:collect-safe`, for a callback C invokes on a thread jolt never started |

## How an example is put together

**The library is declared in `deps.edn`, not in the source.** A `:jolt/native`
entry is dlopen'd `RTLD_LOCAL` before any namespace is required, and the
project's `defcfn`s resolve against that handle rather than the process-global
namespace — so a same-named symbol elsewhere on the machine cannot be picked up
instead. libc needs no entry: it is already in the process.

Both entries here are `:optional true`, because a required native that cannot be
found aborts the process before anything runs and PortAudio is not installed
everywhere. Each example checks its own library at startup instead and prints an
install line — that is all `app.native/need!` is, built on `ffi/dlsym-native`.

**A signature is literal, at compile time.** `defcfn`, `foreign-fn`,
`foreign-callable` and `export!` need their types written out where they are
used, since jolt lowers them to a `foreign-procedure` while compiling. So a
helper cannot take argtypes as an argument, and an `ffi/layout` descriptor is
spelled out again inside a signature rather than referred to by name. When the
shape is genuinely unknown until runtime, libffi is the way out — which is what
`app.libffi` is about.

**Memory is explicit.** `ffi/alloc` and `ffi/free` are malloc and free; exactly
one of each per pointer. The scoped forms cover the single-value cases and free
on the way out even if the body throws:

```clojure
(ffi/with-alloc [p 64]        ...)   ; 64 bytes
(ffi/with-out [pp :pointer]   ...)   ; one scalar, for an out parameter
(ffi/with-layout [s point-t]  ...)   ; one instance of a compiled layout
(ffi/with-c-string [s "utf8"] ...)   ; a NUL-terminated copy
```

A read is `(ffi/read pointer type offset)` and a write is
`(ffi/write pointer type offset value)` — offset before value. A pointer is a
plain integer address with no length attached, so `ffi/null` is `0` and reading
an array of them is arithmetic on the element size. `ffi/read-field` and
`ffi/write-field` do the same thing by field name against a compiled layout, at
the cost of resolving the path at runtime; `app.structs` measures that.

**A struct by value uses `[:by-value descriptor]`.** As an argument type it takes
a pointer to the struct bytes. As a return type it makes the binding take a
caller-owned destination pointer as its FIRST argument, write C's return there,
and hand the pointer back.

**A callback is `ffi/foreign-callable`**, which returns a C function pointer and
stays live until `ffi/free-callable`. Add `:collect-safe` whenever C may invoke
it on a thread jolt did not start, or on a jolt thread parked in a `:blocking`
foreign call. Without it the process dies in a memory fault no handler can
catch — see `app.portaudio`.

## Installing the libraries

`structs` needs nothing. libffi ships with macOS and with essentially every
Linux distribution.

```
brew install portaudio          # macOS
apt install libportaudio2       # Debian / Ubuntu
```
