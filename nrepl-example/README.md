# nrepl-example

A jolt app that starts an nREPL server and keeps running, so you can connect
from your editor and drive the live process. The motivating use case is a
binary deployed somewhere headless — say a Raspberry Pi reading sensors — that
runs its loop unattended while you reach in over nREPL to inspect state, flip
outputs, reload code, or call functions without an SSH-and-restart cycle.

```clojure
(require '[app.core :as app])
(app/status)        ; => {:ticks 19 :led true :sensor 46.24...}
(app/toggle-led!)   ; => true
(app/log! "hi")     ; => 2
(app/recent-log)    ; => ["system started" "hi"]
```

The "device" is simulated (a counter and a sine-wave sensor reading), but the
plumbing is real: a background loop mutates an atom, and the nREPL middleware
(eval, sessions, completion, lookup) comes from the
[jolt-lang/nrepl](https://github.com/jolt-lang/nrepl) library.

## Run in dev

Interpreted, with the nREPL server on `127.0.0.1:7888`:

```
joltc run -m app.core
```

It writes `.nrepl-port` and parks until `^C`. Point CIDER / Calva / Cursive at
that port and you're in a live REPL over the running process.

Run the test suite (starts the server in-process, connects a client, drives
state over the wire, checks the device loop):

```
joltc -M:test
```

For an ad-hoc REPL into this project without running the app's main:

```
joltc nrepl
```

## Compile for release

A self-contained native binary (no `joltc`, no JVM, ~MBs):

```
joltc build -m app.core -o nrepl-example
./nrepl-example          # listens on 7888
./nrepl-example 4005     # or pass a port
```

The port also comes from the first arg, else `$JOLT_NREPL_PORT`, else `7888`.
The binary writes `.nrepl-port` on start and removes it on `^C` (shutdown hooks
close the server and stop the device loop). Connect an editor exactly as in dev
— the binary is as driveable as the interpreted run.

> macOS builds are unsigned. To run one on another Mac, clear the quarantine
> bit first: `xattr -d com.apple.quarantine ./nrepl-example`.

## How it works

- `start-nrepl!` mirrors what `joltc nrepl` does: it blocks `SIGINT` on the main
  thread (so `^C` lands there, not on the server's socket `recv`), starts the
  server on a worker thread with the library middleware, and registers its stop
  fn as a shutdown hook.
- `start-device-loop!` runs the simulated device on a `future`; `park-until-interrupt`
  in `-main` is what keeps the process alive — returning from `-main` would let
  the launcher exit.
- Device state lives in a `defonce` atom (`app.core/system`), so reloading the
  namespace from a connected editor doesn't wipe what the loop has accumulated.

## Requirements

- `joltc` on PATH.
- The jolt-lang/nrepl dependency is fetched automatically from git on first run.
