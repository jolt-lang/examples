#!/bin/sh
# Build a self-contained native executable at build/greeter.
#
# Resolves the deps.edn dependencies with jolt-deps, then lets jpm bake a Jolt
# context with greeter.core + Selmer + config preloaded into a binary (see
# main.janet). Needs: janet + jpm, the jolt + jolt-deps binaries on PATH, and
# a jolt source checkout (JOLT_REPO, default ../../jolt).
set -e
cd "$(dirname "$0")"

JOLT_REPO="${JOLT_REPO:-../../jolt}"
if [ ! -f "$JOLT_REPO/src/jolt/api.janet" ]; then
  echo "jolt checkout not found at $JOLT_REPO — set JOLT_REPO" >&2
  exit 1
fi

export JOLT_REPO
export JOLT_PATH="$(jolt-deps path)"
# --modpath puts jolt's source on jpm's module path so main.janet can
# (import jolt/api) at build time.
jpm --modpath="$(cd "$JOLT_REPO/src" && pwd)" build
echo "Built build/greeter"
