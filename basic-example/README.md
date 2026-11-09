# basic-example

A jolt app that starts a minimal app.

## Run in dev

Interpreted:

```
jolt run -m app.core           # or: jolt start
```

Start an nREPL server for your editor (port 7888, writes `.nrepl-port`):

```
jolt nrepl-server
```

## Compile for release

A self-contained native binary (no `jolt`, no JVM, ~MBs):

```
jolt build -m app.core -o basic-example
./basic-example
```

## Requirements

- `jolt` on PATH.
- The jolt-lang/nrepl dependency is fetched automatically from git on first run.
