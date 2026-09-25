#!/bin/bash
# S25 — relay board state is pushed (ESPHome /events), taps update at once.
# A fake board (fake-esphome-relay.py, 0.3 s per request like the real ESP32) runs on
# atlas01; the emulator's relayHost points at it for the run and is put back after.
#   docs/smoke/s25-relay.sh <tag> [agent]        (SERIAL=emulator-5554 default)
SLICE=s25-relay PKG=com.morton.trucknav
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
cd "$(dirname "$0")/../.." || exit 2
contract
[ "$VERSION" = "${WANT:-0.40.0}" ] || { echo "!! expected ${WANT:-0.40.0}, device has $VERSION"; exit 2; }
UI=$HOME/bin/ui.sh; PORT=18099; B=http://127.0.0.1:$PORT; FLOG=$EVID/board.jsonl

python3 docs/smoke/fake-esphome-relay.py --port $PORT --lag 0.3 --log $FLOG & FAKE=$!
app_restart 8
TOK=$(grep '^apiToken=' local.properties | cut -d= -f2-); adb -s $S forward tcp:18782 tcp:8782 >/dev/null
api() { curl -s -m 6 -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" "$@"; }
ORIG=$(api http://127.0.0.1:18782/api/settings | python3 -c "import json,sys; print(json.dumps({'relayHost': json.load(sys.stdin).get('relayHost') or ''}))")
require "read original relayHost" test -n "$ORIG"; echo "$ORIG" > "$EVID/orig-settings.json"
cleanup() { kill $FAKE 2>/dev/null; api -X PUT http://127.0.0.1:18782/api/settings -d "$ORIG" >/dev/null; }
trap cleanup EXIT
api -X PUT http://127.0.0.1:18782/api/settings -d "{\"relayHost\":\"10.0.2.2:$PORT\"}" >/dev/null
app_restart 8; $UI $S tapx "Got it" >/dev/null 2>&1; $UI $S tapx "Continue to map" >/dev/null 2>&1
tap_until "Vehicle" "Relay 3 OFF" 12 >/dev/null

ms() { echo $(( $(date +%s%N) / 1000000 )); }
# centre of a node by content-desc, and a probe point inside the tile away from text
box() { dump | python3 -c "
import re,sys; x=sys.stdin.read(); m=re.findall(r'content-desc=\"$1\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
print(*(m[-1] if m else ()))"; }
pix() { adb -s $S exec-out screencap -p | python3 -c "
import sys,io; from PIL import Image; im=Image.open(io.BytesIO(sys.stdin.buffer.read())).convert('RGB'); r,g,b=im.getpixel(($1,$2)); print('blue' if b-r>=60 and b>=0x70 else '%02x%02x%02x'%(r,g,b))"; }
# poll the probe pixel until it is (or is not) the ON blue; print elapsed ms or none
until_px() { local x=$1 y=$2 want=$3 lim=$4 t0=$5 p; while [ $(( $(ms)-t0 )) -lt $lim ]; do p=$(pix $x $y); if { [ "$want" = on ] && [ "$p" = blue ]; } || { [ "$want" = off ] && [ "$p" != blue ]; }; then echo $(( $(ms)-t0 )); return 0; fi; done; echo none; return 1; }
gets() { python3 -c "import json,sys; print(sum(1 for l in open('$FLOG') if json.loads(l)['m']=='GET' and json.loads(l)['path'].startswith('/switch/') and json.loads(l)['t']>=$1))"; }

# R1 connected: 8 tiles
n=$(dump | grep -oE 'content-desc="(Starlink|Rear Lights|Relay [3-8]) (ON|OFF)"' | sort -u | wc -l); f=$(shot R1.png)
row R1 "Vehicle pane lists 8 relay tiles from the board" "$n tiles" "$([ "$n" = 8 ] && echo PASS || echo FAIL)" $f

# R2 tap → tile blue; board got a name-URL POST
set -- $(box "Relay 3 OFF"); X=$(( $3-30 )); Y=$(( ($2+$4)/2 )); CX=$(( ($1+$3)/2 )); CY=$(( ($2+$4)/2 ))
T=$(ms); adb -s $S shell input tap $CX $CY; w=$(until_px $X $Y on 3000 $T); f=$(shot R2.png)
row R2 "tap Relay 3 → tile ON ≤ 1000 ms (old: POST + 0.6 s + 8 reads)" "${w} ms" "$([ "$w" != none ] && [ "$w" -le 1000 ] && echo PASS || echo FAIL)" $f
sleep 1.5; post=$(grep '"POST"' $FLOG | tail -1 | python3 -c "import json,sys; print(json.load(sys.stdin)['path'])")
row R3 "command uses the entity-name URL, no object-id POST" "$post" "$([ "$post" = "/switch/Relay 3 (Pin 14)/turn_on" ] && echo PASS || echo FAIL)" board.jsonl
set -- $(box "Relay 3 ON"); T=$(ms); adb -s $S shell input tap $CX $CY; w=$(until_px $X $Y off 3000 $T)
row R2b "tap again → tile OFF ≤ 1000 ms" "${w} ms" "$([ "$w" != none ] && [ "$w" -le 1000 ] && echo PASS || echo FAIL)" -

# R4 change made elsewhere (panel button / HA) shows up ≤ 2 s (old: up to 15 s)
set -- $(box "Relay 4 OFF"); X4=$(( $3-30 )); Y4=$(( ($2+$4)/2 ))
T=$(ms); curl -s -X POST $B/_ext/4/on >/dev/null; w=$(until_px $X4 $Y4 on 16000 $T); f=$(shot R4.png)
row R4 "Relay 4 switched on at the board → tablet tile ON ≤ 2000 ms" "${w} ms" "$([ "$w" != none ] && [ "$w" -le 2000 ] && echo PASS || echo FAIL)" $f
curl -s -X POST $B/_ext/4/off >/dev/null

# R5 no polling: 30 s idle → 0 GET /switch/*
t0=$(date +%s); sleep 30; g=$(gets $t0)
row R5 "30 s idle: 0 GET /switch/* at the board (old: 8 every 15 s)" "$g GETs" "$([ "$g" = 0 ] && echo PASS || echo FAIL)" board.jsonl

# R6 failed command reverts
set -- $(box "Relay 5 OFF"); X5=$(( $3-30 )); Y5=$(( ($2+$4)/2 )); C5X=$(( ($1+$3)/2 )); C5Y=$(( ($2+$4)/2 ))
curl -s -X POST $B/_fail/5 >/dev/null; T=$(ms); adb -s $S shell input tap $C5X $C5Y
sleep 0.2; flip=$(pix $X5 $Y5); w=$(until_px $X5 $Y5 off 4000 $T); f=$(shot R6.png); s5=$(dump | grep -oE 'content-desc="Relay 5 (ON|OFF)"')
row R6 "board answers 500 → tile back to OFF ≤ 4 s" "flip px=$flip, back ${w} ms, $s5" "$([ "$w" != none ] && echo "$s5" | grep -q OFF && echo PASS || echo FAIL)" $f

# R7 link loss greys the pane; recovery brings tiles back
T=$(ms); curl -s -X POST $B/_drop/35 >/dev/null
lost=none; while [ $(( $(ms)-T )) -lt 40000 ]; do seen "Relay 3 OFF" || { lost=$(( $(ms)-T )); break; }; sleep 1; done; f=$(shot R7-lost.png)
row R7 "board goes silent → tiles removed (not stale) ≤ 30 s" "${lost} ms" "$([ "$lost" != none ] && [ "$lost" -le 30000 ] && echo PASS || echo FAIL)" $f
back=none; while [ $(( $(ms)-T )) -lt 70000 ]; do seen "Relay 3 OFF" && { back=$(( $(ms)-T-35000 )); break; }; sleep 1; done; f=$(shot R7-back.png)
row R7b "board back → tiles back ≤ 15 s after it answers again" "${back} ms" "$([ "$back" != none ] && [ "$back" -le 15000 ] && echo PASS || echo FAIL)" $f

# R8 Starlink keeps its guard: tap turns on, tap does NOT turn off, hold does
set -- $(box "Starlink OFF"); XS=$(( $3-30 )); YS=$(( ($2+$4)/2 )); CSX=$(( ($1+$3)/2 )); CSY=$(( ($2+$4)/2 ))
adb -s $S shell input tap $CSX $CSY; a=$(until_px $XS $YS on 3000 $(ms))
adb -s $S shell input tap $CSX $CSY; sleep 2; b=$(pix $XS $YS)
adb -s $S shell input swipe $CSX $CSY $CSX $CSY 900; c=$(until_px $XS $YS off 3000 $(ms)); f=$(shot R8.png)
row R8 "Starlink: tap → ON, second tap stays ON, hold → OFF" "on ${a} ms, after tap px=$b, hold off ${c} ms" "$([ "$a" != none ] && [ "$b" = blue ] && [ "$c" != none ] && echo PASS || echo FAIL)" $f

crash_gate
cleanup; trap - EXIT
echo "-- relayHost restored: $(api http://127.0.0.1:18782/api/settings | python3 -c "import json,sys; print(repr(json.load(sys.stdin).get('relayHost')))")"
restore
finish
