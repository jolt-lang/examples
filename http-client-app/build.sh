#!/bin/sh
# Build a self-contained native executable at build/http-client-app.
#
# Resolves the deps.edn dependencies with jolt, then lets jpm bake a Jolt
# context with http-client-app.core + Selmer + config preloaded into a binary (see
# main.janet). Needs: janet + jpm, the jolt binary on PATH, and
# a jolt source checkout (JOLT_REPO, default ../../jolt).
set -e
cd "$(dirname "$0")"

JOLT_REPO="${JOLT_REPO:-../../jolt}"
if [ ! -f "$JOLT_REPO/src/jolt/api.janet" ]; then
  echo "jolt checkout not found at $JOLT_REPO — set JOLT_REPO" >&2
  exit 1
fi

export JOLT_REPO
export JOLT_PATH="$(jolt path)"
# The bake snapshots whatever env-reading libraries capture at load (config!)
# INTO THE BINARY — scrub everything but what the build itself needs, so no
# tokens from the build machine can end up in a distributed executable
# (jolt-lang/jolt jolt-s3j).
export JOLT_BAKE_ENV_ALLOWLIST="PATH,HOME,TMPDIR,JOLT_REPO,JOLT_PATH,JANET_PATH,PORT"
# --modpath puts jolt's source on jpm's module path so main.janet can
# (import jolt/api) at build time.
jpm --modpath="$(cd "$JOLT_REPO/src" && pwd)" build
echo "Built build/http-client-app"
