#!/usr/bin/env bash
# Staging smoke for R3 fleet — no root, no systemctl.
# Mirrors the systemd oneshot path: packaging validate → fleet-gate →
# daemon wrapper → status/audit observability.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT="${FLEET_ROOT:-tmp/fleet-staging-smoke}"
WASM="${FLEET_WASM:-resources/fleet/fixtures/kotoba-compiled-fact.wasm}"
export FLEET_HOME="${FLEET_HOME:-$PWD}"
export FLEET_ROOT="$ROOT"

echo "== packaging =="
bash deploy/validate-packaging.sh

echo "== fleet-gate =="
clojure -M:cli fleet-gate

echo "== daemon wrapper (1 pass) =="
chmod +x deploy/bin/fleet-daemon
deploy/bin/fleet-daemon \
  --wasm "$WASM" \
  --root "$ROOT" \
  --interval-ms 0 \
  --max-passes 1 \
  --max-ticks 1

echo "== fleet-status =="
clojure -M:cli fleet-status
clojure -M:cli fleet-audit | head -40

echo "== staging-smoke passed =="
echo "R3 stable criterion (ops): this script is the non-root staging substitute."
echo "On a real host: enable the timer documented in docs/maturity.md"
