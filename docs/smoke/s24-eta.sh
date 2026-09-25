#!/bin/bash
# S24 — Jackery-style time estimate in the power strip ("5h 0m to full" / "10h 0m left").
# A throwaway mosquitto on atlas01 plays the Venus Pi: the emulator's venusHost is pointed at
# 10.0.2.2 for the run and put back afterwards.
#   docs/smoke/s24-eta.sh <tag> [agent]        (SERIAL=emulator-5554 default)
SLICE=s24-eta PKG=com.morton.trucknav
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
cd "$(dirname "$0")/../.." || exit 2
contract
[ "$VERSION" = "${WANT:-0.39.0}" ] || { echo "!! expected ${WANT:-0.39.0}, device has $VERSION"; exit 2; }

app_restart 8
TOK=$(grep '^apiToken=' local.properties | cut -d= -f2-)
adb -s $S forward tcp:18782 tcp:8782 >/dev/null
api() { curl -s -m 6 -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" "$@"; }
ORIG=$(api http://127.0.0.1:18782/api/settings | python3 -c "import json,sys; d=json.load(sys.stdin); print(json.dumps({k:d.get(k) for k in ('venusHost','venusPortalId')}))")
require "read original Venus settings" test -n "$ORIG"
echo "$ORIG" > "$EVID/orig-settings.json"

M=s24-mosq-$TAG
printf 'listener 1883 0.0.0.0\nallow_anonymous true\n' > "$EVID/mosquitto.conf"
require "fake broker up on 127.0.0.1:1883" docker run -d --rm --name $M -p 127.0.0.1:1883:1883 -v "$EVID/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" eclipse-mosquitto:2
cleanup() { docker rm -f $M >/dev/null 2>&1; api -X PUT http://127.0.0.1:18782/api/settings -d "$ORIG" >/dev/null; }
trap cleanup EXIT
sleep 2
pub() { echo "$(date +%T) $1 = $2" >> "$EVID/published.txt"; docker exec $M mosquitto_pub -r -t "N/fake/$1" -m "{\"value\": $2}"; }

pub battery/277/Capacity 100; pub system/0/Dc/Battery/Soc 50; pub system/0/Dc/Battery/TimeToGo null; pub system/0/Dc/Battery/Current 10
api -X PUT http://127.0.0.1:18782/api/settings -d '{"venusHost":"10.0.2.2","venusPortalId":"fake"}' >/dev/null
app_restart 8; $HOME/bin/ui.sh $S tapx "Got it" >/dev/null 2>&1; $HOME/bin/ui.sh $S tapx "Continue to map" >/dev/null 2>&1

# the value sits right after its label in the strip; print it
val() { dump | python3 -c "
import html,re,sys; t=[html.unescape(x) for x in re.findall(r'text=\"([^\"]*)\"', sys.stdin.read())]
for i,x in enumerate(t):
    if x=='$1' and i+1<len(t): print(t[i+1]); break"; }
shows() { [ "$(val "$1")" = "$2" ]; }
mins() { python3 -c "
import re,sys; s=sys.argv[1]; m=re.fullmatch(r'(?:(\d+)h )?(\d+)m', s)
print(int(m.group(1) or 0)*60+int(m.group(2)) if m else -1)" "$1"; }
check() { # id crit label value timeout
  local t0=$(date +%s) w; w=$(poll "$5" shows "$3" "$4") && w=$(( $(date +%s)-t0 )); local f; f=$(shot "$1.png")
  row "$1" "$2" "${w}s → \"$3 $(val "$3")\"" "$([ "$w" != none ] && echo PASS || echo FAIL)" "$f"; }

check P1 "charging 10 A, SoC 50, 100 Ah → 'To full 5h 0m' ≤ 20 s" "To full" "5h 0m" 20

# P2 one jump to 20 A: raw math says 2h 30m; smoothed must still read ≥ 3h 45m 8 s later
pub system/0/Dc/Battery/Current 20; sleep 8; v=$(val "To full"); m=$(mins "$v"); f=$(shot P2.png)
row P2 "jump 10→20 A: 8 s later still ≥ 3h 45m (unsmoothed = 2h 30m)" "$v ($m min)" "$([ "$m" -ge 225 ] && [ "$m" -le 300 ] && echo PASS || echo FAIL)" $f
converged() { local m; m=$(mins "$(val "To full")"); [ "$m" -ge 0 ] && [ "$m" -le 165 ]; }
t0=$(date +%s); w=$(poll 240 converged) && w=$(( $(date +%s)-t0+8 ))
f=$(shot P2b.png); row P2b "held at 20 A → converges to ≤ 2h 45m (not frozen; τ 60 s predicts ~100 s after the jump)" "${w}s → $(val "To full")" "$([ "$w" != none ] && echo PASS || echo FAIL)" $f

pub system/0/Dc/Battery/TimeToGo 36000; pub system/0/Dc/Battery/Current -5
check P3 "flip to −5 A with TimeToGo 36000 → 'Left 10h 0m' ≤ 15 s (flip resets the average)" "Left" "10h 0m" 15
pub system/0/Dc/Battery/TimeToGo null
check P4 "TimeToGo null → computed 50 Ah / 5 A = 'Left 10h 0m'" "Left" "10h 0m" 15
pub system/0/Dc/Battery/TimeToGo 500000
check P5 "TimeToGo 500000 s → 'Left >99h'" "Left" ">99h" 15
pub system/0/Dc/Battery/Current 0.1
check P6 "0.1 A → 'Time --' (idle, no 400h) ≤ 200 s (τ 60 s from −5 A)" "Time" "--" 200
pub system/0/Dc/Battery/Soc 99.8; pub system/0/Dc/Battery/Current 2
check P7 "SoC 99.8 %, +2 A → 'Battery Full'" "Battery" "Full" 60
pub system/0/Dc/Battery/Soc 50; pub battery/277/Capacity null; pub system/0/Dc/Battery/Current 10
check P8 "charging with no capacity published → 'To full --', no crash" "To full" "--" 60

# P9 layout: the estimate cell sits on the first screen of the strip, strip still 56 dp tall
b=$(dump | python3 -c "
import re,sys; x=sys.stdin.read()
n=re.search(r'text=\"To full\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
s=re.search(r'text=\"SOC\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
print(n.group(3) if n else -1, s.group(2) if s else -1)"); set -- $b; f=$(shot P9.png)
row P9 "estimate cell right edge < 1340 px (no scroll needed)" "right=$1" "$([ "$1" -gt 0 ] && [ "$1" -lt 1340 ] && echo PASS || echo FAIL)" $f

crash_gate
cleanup; trap - EXIT; sleep 1
echo "-- venus settings restored: $(api http://127.0.0.1:18782/api/settings | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('venusHost'), d.get('venusPortalId'))")"
restore
finish
