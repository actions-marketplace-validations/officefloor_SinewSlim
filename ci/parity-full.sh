#!/usr/bin/env bash
#
# The full parity check (DESIGN.md, Testing): SinewSlim's search against the study's split.py on
# every long method of the seed-3 open-source sample. Run it before central-libraries (or any
# change to the search or the facts) is merged, and before a release to Maven Central.
#
#   ci/parity-full.sh [STUDY_DIR]
#       STUDY_DIR   the concentration-divergence-study checkout, its .venv set up (its
#                   step1_setup.sh) and its seed-3 facts measured (results/seed3/repos/);
#                   default ../concentration-divergence-study
#
# 1. The study's own export writes split.py's answers, with the facts they were read on, for
#    every long method of seed 3 (scripts/export_split_golden.py --sets seed3). It reads the
#    facts the study measured; it clones nothing.
# 2. SinewSlim's parity test replays every one of them and fails on any difference in status,
#    slim cost, pieces or cuts, under each named parameter set.
#
# The export takes about three hours (seed 3 holds large repositories), printing its count each
# minute; the replay about twenty minutes. A finished export is reused when the script is run
# again at the same study commit, so a failed replay is rerun in minutes. Both commits, the
# method count and the result are written to build/parity/parity.txt.
set -euo pipefail
cd "$(dirname "$0")/.."

STUDY="$(cd "${1:-../concentration-divergence-study}" 2>/dev/null && pwd)" \
  || { echo "ERROR: no study checkout at ${1:-../concentration-divergence-study}" >&2; exit 1; }
PY="$STUDY/.venv/bin/python"
[ -x "$PY" ] || { echo "ERROR: $PY not found: run the study's step1_setup.sh first" >&2; exit 1; }
ls "$STUDY"/results/seed3/repos/*.json.gz >/dev/null 2>&1 \
  || { echo "ERROR: no seed-3 facts in $STUDY/results/seed3/repos/: run the study's seed-3 step first" >&2; exit 1; }

OUT="build/parity"
GOLDEN="$OUT/split-golden.seed3.jsonl.gz"
mkdir -p "$OUT"

STUDY_COMMIT="$(git -C "$STUDY" rev-parse HEAD)"
DONE="$GOLDEN.study"   # the study commit a finished export was made at

echo "=== 1. split.py's answers on seed 3 (the study, ${STUDY_COMMIT:0:7}) -> $GOLDEN ==="
if [ -f "$DONE" ] && [ "$(cat "$DONE")" = "$STUDY_COMMIT" ] && gzip -t "$GOLDEN" 2>/dev/null; then
  echo "already exported at this study commit: reused (delete $DONE to export again)"
else
  rm -f "$DONE"
  (cd "$STUDY" && exec "$PY" scripts/export_split_golden.py --sets seed3 --out "$OLDPWD/$GOLDEN") &
  EXPORT=$!
  trap 'kill "$EXPORT" 2>/dev/null' EXIT
  START=$SECONDS
  # A line a minute: the export is otherwise silent for hours. The count lags a little, as the
  # gzip stream is written in blocks; seed 3 holds about 187,000 long methods.
  while kill -0 "$EXPORT" 2>/dev/null; do
    for _ in $(seq 60); do kill -0 "$EXPORT" 2>/dev/null || break; sleep 1; done
    kill -0 "$EXPORT" 2>/dev/null || break
    echo "  $(( (SECONDS - START) / 60 )) min: $(zcat "$GOLDEN" 2>/dev/null | wc -l) methods written"
  done
  wait "$EXPORT"
  trap - EXIT
  echo "$STUDY_COMMIT" > "$DONE"
  echo "  done in $(( (SECONDS - START) / 60 )) min: $(zcat "$GOLDEN" | wc -l) methods"
fi

echo
echo "=== 2. SinewSlim's parity test on every one of them ==="
./gradlew --console=plain cleanTest :sinewslim-core:test --tests '*StudyParityTest' \
    -Dsinewslim.parity.full="$PWD/$GOLDEN"

RESULT="$(grep -ho "$PWD/$GOLDEN: [^<&]*" core/build/test-results/test/TEST-*StudyParityTest.xml | head -1)"
{
  echo "sinewslim:  $(git rev-parse HEAD)$(git diff --quiet || echo ' (with uncommitted changes)')"
  echo "study:      $STUDY_COMMIT"
  echo "golden:     $GOLDEN ($(sha256sum "$GOLDEN" | cut -c1-16))"
  echo "result:     ${RESULT#"$PWD/"}"
  echo "when:       $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} | tee "$OUT/parity.txt"
echo
echo "Parity holds on seed 3: every method agrees with split.py. The record is $OUT/parity.txt."
