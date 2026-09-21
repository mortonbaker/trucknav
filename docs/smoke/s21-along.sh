#!/bin/bash
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
export SERIAL=${SERIAL:-emulator-5554}
[ "$SERIAL" = emulator-5554 ] && [ "$(hostname)" = build01 ] || { echo "S21 requires build01 emulator-5554"; exit 2; }
SLICE=s21-along PKG=com.morton.trucknav
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
require "Astra device lease" bash -c 'docs/tablet-lock.sh emulator-5554 status | grep -q astra-s22'
contract
echo "-- phase: ${S21_PHASE:-full}; outage phase proves e/f only"
export EVID
python3 docs/smoke/s21-along.py
RC=$?
while IFS=$'\t' read -r id criterion measured status evidence; do
  row "$id" "$criterion" "$measured" "$status" "$evidence"
done < "$EVID/rows.tsv"
[ "$RC" = 0 ] || row driver "acceptance driver completes" "exit $RC" FAIL run.log
crash_gate
finish
