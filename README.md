# sahai

**差配 — 誰にどこを貸すかを取り仕切る。** 借家の割り当てを差配する、
のその語。旧名 `fleet`（2026-08-07 rename: bare な `fleet` が二つの org に
在り、三つの repo が `fleet.*` namespace を主張していた）。

Durable T6 placement for Kotoba tenders: multi-tenant leases, budgets,
checkpoints, recovery, audit records, and epoch fencing.

`fleet` decides **where and when** a bounded guest run is attempted. It calls
the `kototama` tender for execution, but does not define the tender, language
semantics, authority policy, or distributed consensus.

The persisted EDN schema intentionally retains `:kototama.fleet/*` keys so
checkpoints written before the split remain readable. Code ownership moved to:

- `sahai.core` — pure lease/budget/tick/checkpoint model
- `sahai.store` — memory, disk, B2, and composite persistence
- `sahai.fence` — shared-store epoch fencing (not Raft/Paxos)
- `sahai.exec` — placement-to-tender bridge and R3 acceptance gate
- `sahai.cli` — the ten fleet operational commands formerly in `kototama.cli`

## Tamaki capability boundary

Tamaki does not hand an actor specification or private issue data to Fleet.
It emits a minimal, versioned capability envelope. The execution path is:

`Tamaki envelope → Kototama admission → sealed Fleet lease → tender HostCaps`

`tamaki-run` independently validates the envelope with Kototama before any
lease, audit, or checkpoint is written. The admitted grants and limits are
sealed into the lease and cannot be widened when the lease resumes. A
checkpoint stores only the actor identifier, contract version and digest,
effects, grants, and limits required to resume; it never stores the source
actor spec, objectives, prompts, credentials, or tokens.

```bash
kbb -M:cli tamaki-run capability-envelope.edn guest.wasm
```

The envelope digest is an audit identifier, not a signature. Signed Murakumo
placement epochs remain a separate authority check.

```bash
kbb -M:test
kbb -M:cli fleet-demo
kbb -M:cli fleet-gate
bash deploy/staging-smoke.sh
```

See [docs/maturity.md](docs/maturity.md) for the exact R3 claim and operational
runbook.
