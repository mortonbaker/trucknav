#!/bin/bash
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
export SERIAL=${SERIAL:-emulator-5554}
case "$SERIAL" in emulator-*) ;; *) echo "S21 runs on an emulator only"; exit 2;; esac   # any emulator; the merger uses emulator-5558
SLICE=s21-along PKG=com.morton.trucknav
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
AGENT=${2:-astra-s22}; require "device lease held by $AGENT" bash -c 'docs/tablet-lock.sh "$1" status | grep -q "$2"' _ "$SERIAL" "$AGENT"
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
