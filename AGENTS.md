# AGENTS.md — sahai

## CI/CD runs Kotoba natively: no JVM, no nbb

Owner decision 2026-10-22: the JVM and nbb (Node.js) are not used, so they are
not part of CI/CD (Linear JUN-290, JUN-162).

- `.github/workflows/ci.yml` (job `CI`, the check main's branch protection
  requires) runs only Amu selfhost gates. `scripts/selfhost-gate.sh` compiles
  each `kotoba/**/*.kotoba` module's zero-arity `test-*` with the pinned Amu
  root compiler into a loaderless aarch64-linux static image, runs it on a
  native arm64 runner and requires every test to pass. A mutation negative
  control proves the gate can fail.
- Do not add `setup-java`, `setup-clojure`, `clojure`, nbb, Node.js, babashka
  or GraalVM steps to any workflow, and do not keep them as a fallback. A
  check that needs something the native route cannot do yet is a blocker to
  record in Linear, not permission to bring a host runtime back.
- Keep the job name `CI`. Changing branch protection needs the owner's
  separate approval.
- The pinned compiler is kotoba-lang/amu `seed/bootstrap/aarch64-linux-static-root.elf`.
  Move `AMU_COMMIT` and `AMU_ROOT_SHA256` together, and re-run the gate.
- New decisions go to `kotoba/` in the Amu selfhost seed subset (no ns
  docstring, declared result types, no fn literal capturing non-i64, bare fn
  params), with `test-*` functions so the gate covers them.

### What CI no longer covers

The `.cljk` sources and their tests (`src/`, `test/`, `jvm/`, `deps.edn`,
`nbb.edn`, `deploy/staging-smoke.sh`, `clojure -M:cli fleet-gate`) ran only on
the JVM. They are migration inventory (JUN-290): CI does not run them, and
they are replaced by Kotoba modules under `kotoba/` before they are removed.
