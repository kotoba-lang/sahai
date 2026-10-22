#!/bin/sh
# Amu selfhost gate for one Kotoba module: compile its zero-arity test-*
# functions with the pinned Amu root compiler into a loaderless aarch64-linux
# static image, run it, and require the expected result. No Node.js, nbb, JVM
# or babashka; POSIX sh only.
#
#   scripts/selfhost-gate.sh ROOT_ELF MODULE [EXPECT]
#
# The image's main sets bit i when the i-th test-* (in source order) is true.
# EXPECT defaults to every bit set; pass it explicitly for a negative control
# on a mutated copy of MODULE. Run on an arm64 Linux kernel (natively in CI;
# locally through `podman run --platform linux/arm64`).
set -eu

root=$1
module=$2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tests=$(sed -n 's/^(defn \(test-[A-Za-z0-9?!*-]*\) \[\].*/\1/p' "$module")
if [ -z "$tests" ]; then
  echo "selfhost-gate: no zero-arity test-* in $module" >&2
  exit 1
fi

i=0
sum=0
for t in $tests; do
  sum="(+ (if ($t) $((1 << i)) 0) $sum)"
  i=$((i + 1))
done
expect=${3:-$(((1 << i) - 1))}

# The module's ns form is replaced by one exporting only main; everything
# after it (definitions and test-*) is kept as written.
{
  echo '(ns selfhost-gate (:export [main]))'
  awk 'state == 0 && /^\(ns / { state = 1 }
       state == 1 { d += gsub(/\(/, "(") - gsub(/\)/, ")"); if (d == 0) state = 2; next }
       state == 2 { print }' "$module"
  echo "(defn main [] :i64 $sum)"
} > "$work/gate.kotoba"

cp "$root" "$work/root.elf"
chmod +x "$work/root.elf"
(cd "$work" && KEXE_CAP_RESOURCES_35="$work" ./root.elf compile "$work/gate.kotoba" \
  --target aarch64-linux --output "$work/gate.elf" --format static)
chmod +x "$work/gate.elf"

rc=0
report=$(cd "$work" && KEXE_STRUCTURED_REPORT=1 ./gate.elf 2>&1) || rc=$?
result=$(printf '%s\n' "$report" | sed -n 's/.*:status :ok :result \(-\{0,1\}[0-9][0-9]*\).*/\1/p' | head -n 1)

echo "selfhost-gate: $module tests=[$(echo $tests)] result=${result:-none} expect=$expect exit=$rc"
if [ "$result" != "$expect" ]; then
  printf '%s\n' "$report" >&2
  exit 1
fi
