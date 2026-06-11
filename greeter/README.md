# greeter

A small [Jolt](https://github.com/jolt-lang/jolt) project that pulls two real
Clojure libraries from GitHub via `deps.edn` —
[yogthos/config](https://github.com/yogthos/config) for configuration and
[Selmer](https://github.com/yogthos/Selmer) for templating — runs them at a
REPL/nREPL, and compiles everything into a single native executable.

```
deps.edn      dependencies (git deps), aliases, tasks
config.edn    runtime configuration read by config.core
src/          greeter.core — renders a Selmer template from the config
test/         a small check suite
main.janet    entry point for the native-executable build
project.janet jpm project definition for the executable
build.sh      builds build/greeter
```

## Prerequisites

[Janet](https://janet-lang.org) and `jpm`, plus the `jolt` and `jolt-deps`
binaries on your `PATH`:

```bash
git clone https://github.com/jolt-lang/jolt.git
cd jolt && git submodule update --init && jpm build
export PATH="$PWD/build:$PATH"
```

The native-executable build also needs that jolt checkout (it defaults to
`../../jolt`; point `JOLT_REPO` elsewhere if yours lives elsewhere).

## Dependencies

`deps.edn` declares the two libraries as git deps pinned to full SHAs:

```clojure
:deps {yogthos/config {:git/url "https://github.com/yogthos/config"
                       :git/sha "e9076ad18fce0b24c72d317dd23c43766dd95c8c"}
       yogthos/selmer {:git/url "https://github.com/yogthos/Selmer"
                       :git/sha "fa4a42e77b48905f87b3bf0dc1c698f2c329c722"}}
```

`jolt-deps` fetches them into a global cache (`~/.jolt/gitlibs`) on first use,
caches the resolved roots in `.cpcache/`, and runs `jolt` with everything on
`JOLT_PATH`. There is no Maven and no JVM — the libraries load as plain
Clojure source on Jolt's Java shims.

## Run

```bash
jolt-deps run -m greeter.core            # render the greeting from config.edn
jolt-deps run -m greeter.core Clojurians # positional arg overrides the name
MOTD="env beats config.edn" jolt-deps task run
```

Configuration comes from `config.edn` merged with environment variables
(environment wins), courtesy of `config.core/env`.

Tests:

```bash
jolt-deps -M:test        # or: jolt-deps task test
```

## REPL

```bash
jolt-deps repl
```

```clojure
user=> (require '[greeter.core :as g])
user=> (g/greeting {:name "repl" :motd "live"})
"Hello REPL!\nmotd: live\n"
user=> (require '[selmer.parser :as sp])
user=> (sp/render "{{x|upper}}" {:x "selmer works"})
"SELMER WORKS"
```

## nREPL

```bash
jolt-deps -M:nrepl       # serve on 127.0.0.1:7888, write .nrepl-port
```

The server writes `.nrepl-port`, so CIDER, Calva, and friends connect with
their usual *connect to running nREPL* command. Sessions share one runtime, so
`def`s persist like a normal dev REPL. Pass an address to choose a port:
`jolt-deps -M:nrepl 0.0.0.0:12345`.

## Bundle into one file

```bash
jolt-deps task uberscript                # writes target/greeter.clj
JOLT_PATH="$(jolt-deps path)" jolt target/greeter.clj
```

`uberscript` concatenates `greeter.core` and every namespace it requires —
Selmer and config included, 10 namespaces — into one `.clj` in dependency
order. It bundles code only: Selmer loads an error-page resource at require
time, which is why the resolved roots stay on `JOLT_PATH` here. For a truly
self-contained artifact, build the executable.

## Compile to a native executable

```bash
./build.sh               # or: jolt-deps task build
./build/greeter          # starts in ~80 ms
./build/greeter Clojurians
```

`build.sh` resolves the deps, then `jpm build` runs `main.janet` and marshals
the result into a native binary: a Jolt context with `greeter.core`, Selmer,
and config already loaded is baked in at build time, so the executable needs
no sources, no deps, and no `JOLT_PATH` at runtime — copy it anywhere.

One wrinkle worth knowing: namespace state is captured at build time, so
top-level state like `config.core/env` is a snapshot of the *build*
environment. `-main` calls `config/reload-env` first, which re-reads
`config.edn` and the environment from wherever the binary actually runs.

The binary also embeds the nREPL server, so you can open a live REPL into the
compiled app:

```bash
./build/greeter nrepl    # or: ./build/greeter nrepl 0.0.0.0:12345
```

Connect your editor to `.nrepl-port` and poke at the baked image —
`(greeter.core/greeting {:name "live"})` works, and redefinitions take effect
thanks to Jolt's var indirection.
