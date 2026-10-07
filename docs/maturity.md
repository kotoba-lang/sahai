# R3 shared-store fleet maturity

`fleet` provides an ops-ready local/shared-store placement loop:

- lease, budget, tick, and placement governor
- EDN v1 checkpoint/restore
- memory, disk, optional B2, and composite stores
- tender execution through the pinned `kototama` dependency
- resume, bounded recovery daemon, audit/status, and epoch fencing
- systemd oneshot+timer packaging and a non-root staging smoke

It does **not** claim Raft/Paxos consensus, leader election, clock
synchronization, or a production multi-datacenter scheduler. Higher epoch wins
on a shared store.

The persisted schema retains `:kototama.fleet/*` keys for compatibility with
checkpoints created before ADR-2607266000 moved T6 ownership out of kototama.

## Gates

```bash
clojure -M:test
clojure -M:cli fleet-gate
bash deploy/validate-packaging.sh
bash deploy/staging-smoke.sh
```

## systemd

Install `deploy/bin/fleet-daemon`, then edit and install the service and timer
from `deploy/systemd/`. Set `FLEET_HOME`, `FLEET_ROOT`, `FLEET_WASM`, and
`FLEET_NODE_ID` for the target host. `KOTOTAMA_FLEET_ROOT` and
`KOTOTAMA_NODE_ID` remain accepted as migration aliases.

