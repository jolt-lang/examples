# http-client-app

A small [Jolt](https://github.com/jolt-lang/jolt) example that exercises the
[clj-http-lite](https://github.com/clj-commons/clj-http-lite) HTTP client through
[jolt-lang/http-client](https://github.com/jolt-lang/http-client), then compiles
to a native executable.

`src/app/core.clj` starts a tiny local HTTP server (spork, via Jolt's `janet.*`
bridge), makes a couple of requests against it, then — if the network is up —
hits some real HTTPS endpoints to show TLS, query params, JSON POST and an
`:insecure?` self-signed request.

## Layout

```
deps.edn      jolt-lang/http-client + clj-http-lite git deps, spork as a :jpm/module
main.janet    native-executable entry point (bakes a Jolt ctx with app.core)
project.janet jpm declare-executable
build.sh      resolves deps and builds build/http-client-app
src/app/core.clj  the demo
```

## Run it

Needs [Janet](https://janet-lang.org) + `jpm`, a `jolt` build on `PATH`, and
OpenSSL for the https calls.

```sh
git clone https://github.com/jolt-lang/jolt.git
cd jolt && git submodule update --init && jpm build
export PATH="$PWD/build:$PATH" JOLT_REPO="$PWD"
cd ../examples/http-client-app

jolt task run        # run from source (resolves git deps on first run)
./build.sh           # compile a native executable at build/http-client-app
./build/http-client-app
```

## Notes

- `jolt-lang/http-client` supplies the `java.net` / TLS / gzip host shims
  clj-http-lite needs; they are not part of jolt core (require installs them).
- The real-endpoint requests are wrapped so the demo still runs (and the local
  server part still passes) with no network.
