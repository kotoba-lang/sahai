# AGENTS.md — sahai

The workspace rules in com-junkawasaki/root `AGENTS.md` apply (CDCI is
stopped; when it restarts it runs Amu selfhost and Kotoba native gates only).
This file records what that means here.

## CI/CD: no JVM, no nbb

Owner decision 2026-10-22: the JVM and nbb (Node.js) are not used, so they are
not part of CI/CD (Linear JUN-290).

- `.github/workflows/ci.yml` (job `CI`, the check main's branch protection
  requires) holds only the Amu selfhost gate: the pinned Amu root compiler
  compiles `kotoba/sahai/fence.kotoba`'s `test-*` into a loaderless
  aarch64-linux static image, runs it on a native arm64 runner and requires
  `:result 15`, with a mutation negative control (`11`). The workflow is
  disabled while CDCI is stopped; enabling it is the owner's decision.
- Do not add `setup-java`, `setup-clojure`, `clojure`, nbb, Node.js, babashka
  or GraalVM steps to any workflow, and do not keep them as a fallback. A
  check that needs something the native route cannot do yet is a blocker to
  record in Linear, not permission to bring a host runtime back.
- Keep the job name `CI`. Changing branch protection needs the owner's
  separate approval.
- The pinned compiler is kotoba-lang/amu
  `seed/bootstrap/aarch64-linux-static-root.elf`. Move `AMU_COMMIT` and
  `AMU_ROOT_SHA256` together and re-run the gate.
- New decisions go to `kotoba/` in the Amu selfhost seed subset (no ns
  docstring, declared result types, no fn literal capturing non-i64, bare fn
  params), with `test-*` functions, and the gate in `ci.yml` and the README
  is extended to cover them.

### What CI no longer covers

The `.cljk` sources and their tests (`src/`, `test/`, `jvm/`, `deps.edn`,
`nbb.edn`, `deploy/staging-smoke.sh`, `clojure -M:cli fleet-gate`, the lint
canary) ran only on the JVM. They are migration inventory (JUN-290): CI does
not run them, and they are replaced by Kotoba modules under `kotoba/` before
they are removed.
