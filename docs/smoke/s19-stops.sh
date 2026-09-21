#!/bin/bash
# S19 contract authored BEFORE app code; build01 / emulator-5556 exclusively.
# a: POST navigate, stop2, stop1; bar next=stop1, ETA/min/mi + smaller final ETA;
# tap Trip stops -> 3 rows with strictly ascending ETAs; bar/list XML + screenshots.
# b: same trip -> 2 numbered pin features + 1 checkered flag; pins PNG + layer log.
# c: emu.sh drive s6 <Valhalla shape> 3 -> stop-passed; GoogleTTS arrival synthesis +1,
# next=stop2 within 2000ms (poll timestamped render log), no deviation-handler.
# d: Remove stop <stop2> -> route stop-remove steps=N; next=destination,
# pins=0+flag, still NAVIGATING, no IDLE line; XML + screenshot + NavLog.
# e: stationary next-leg meters/seconds each within 5% of independent Valhalla
# per-leg summary; save raw response and both measured numbers.
# f: crash buffer collected, zero package crashes; restore rotation, foreground,
# network, idle navigation and paused playback. Evidence unique per tag.
# Controls: Trip stops toggles list; Back closes; Remove stop (48dp, disabled busy,
# error keeps trip); End Navigation unchanged; Arrived/Done dismisses only card
# at intermediate stops and card auto-clears after 10s.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
SERIAL=emulator-5556
[ "$(hostname)" = build01 ] || exit 2
SLICE=s19-stops PKG=com.morton.trucknav AGENT=astra-2
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
require "pressure budget" "$HOME/bin/pressure.sh" --stop-idle --need 4G
require "S19 lease" docs/tablet-lock.sh "$S" acquire "$AGENT" 45 "S19 smoke $TAG"
contract
# Fail closed until the driver implementing the above measurements is connected.
for id in a b c d e; do notrun "$id" "S19 criterion $id (contract above)" "pre-code contract; driver pending"; done
crash_gate
docs/tablet-lock.sh "$S" release "$AGENT"
finish
