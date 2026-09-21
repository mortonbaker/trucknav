#!/bin/bash
# S23e — full-map destination mode. Entering/editing a destination hides the rail, the power
# strip and the side pane; Start or Close brings them back.
#   docs/smoke/s23-fullmap.sh <tag> [agent]            (SERIAL=emulator-5556 default here)
SLICE=s23-fullmap PKG=com.morton.trucknav
S=${SERIAL:-emulator-5556}; export SERIAL=$S
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
UI=$HOME/bin/ui.sh
contract
require "emulator has network (photon reachable)" adb -s $S shell ping -c1 -W3 photon.komoot.io
adb -s $S emu geo fix -97.204973 33.080088 >/dev/null 2>&1
app_restart 10; $UI $S tapx "Got it" >/dev/null 2>&1

# pixel probes (landscape 1340x800): rail column x<116, side pane x>800 when a pane is open
px() { python3 - "$EVID/$1" $2 $3 <<'EOF'
import sys; from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); print("%02x%02x%02x" % im.getpixel((int(sys.argv[2]), int(sys.argv[3]))))
EOF
}
railpx()  { local f; f=$(shot "$1"); px "$f" 40 400; }
is_rail() { [ "$(px "$1" 40 400)" = "10141a" ]; }

# FM0 baseline: Books pane open, rail present
tap_until "Books" "Speed" 8 >/dev/null; sleep 1
f0=$(shot fm0-books.png); r0=$(px $f0 40 400); p0=$(px $f0 1100 400)
row FM0 "baseline: rail column and Books pane visible" "rail=$r0 pane=$p0" "$([ "$r0" = 10141a ] && echo PASS || echo FAIL)" $f0

# FM1 tap the search field → rail gone within 2 s, map spans to the right edge
$UI $S tapx "Search field" >/dev/null; T0=$(date +%s%N)
gone=none; for i in $(seq 1 20); do f=$(shot fm1-$i.png); [ "$(px $f 40 400)" != 10141a ] && { gone=$(( ($(date +%s%N)-T0)/1000000 )); break; }; sleep 0.1; done
f1=$(shot fm1-full.png); r1=$(px $f1 40 400); p1=$(px $f1 1100 780)
row FM1 "search focus → rail hidden ≤ 2000 ms" "${gone} ms" "$([ "$gone" != none ] && [ $gone -le 2000 ] && echo PASS || echo FAIL)" $f1
row FM2 "power strip + pane gone: (1100,780) is map, not chrome (#0b0e12/#10141a)" "px=$p1" "$([ "$p1" != 0b0e12 ] && [ "$p1" != 10141a ] && echo PASS || echo FAIL)" $f1

# FM3 results + preview keep full map
adb -s $S shell input text "Whole%sFoods"; sleep 9; adb -s $S shell input keyevent KEYCODE_BACK; sleep 2
f3a=$(shot fm3-results.png)
$UI $S tapx "Result: Whole Foods Market, Justin Road, 4041, Highland Village, Texas" >/dev/null; w=$(waitfor "Start navigation" 10)
f3=$(shot fm3-preview.png); r3=$(px $f3 40 400)
row FM3 "result → preview sheet, rail still hidden" "sheet after ${w}s rail=$r3" "$([ "$w" != none ] && [ "$r3" != 10141a ] && echo PASS || echo FAIL)" $f3

# FM4 Close → rail + Books pane back within 2 s
$UI $S tapx "Close" >/dev/null; T0=$(date +%s%N)
back=none; for i in $(seq 1 20); do f=$(shot fm4-$i.png); [ "$(px $f 40 400)" = 10141a ] && { back=$(( ($(date +%s%N)-T0)/1000000 )); break; }; sleep 0.1; done
sleep 1; f4=$(shot fm4-back.png); pane4=$(dump | grep -c "text=\"Speed")
row FM4 "Close → rail back ≤ 2000 ms and Books pane back" "${back} ms, Speed nodes=$pane4" "$([ "$back" != none ] && [ $back -le 2000 ] && [ "$pane4" -ge 1 ] && echo PASS || echo FAIL)" $f4

# FM5 favorites tile → full map → Start → navigating with rail back
$UI $S tapx "Map" >/dev/null; sleep 1
tap_until "Go: Home" "Start navigation" 10 >/dev/null; f5a=$(shot fm5-tile-preview.png); r5a=$(px $f5a 40 400)
$UI $S tapx "Start navigation" >/dev/null; w5=$(poll 10 sh -c "adb -s $S logcat -d -s NavLog:* | grep -q 'state NAVIGATING'"); sleep 2
f5=$(shot fm5-navigating.png); r5=$(px $f5 40 400)
row FM5 "tile → full map; Start → NAVIGATING with rail back" "tile rail=$r5a nav after ${w5}s rail=$r5" "$([ "$r5a" != 10141a ] && [ "$w5" != none ] && [ "$r5" = 10141a ] && echo PASS || echo FAIL)" $f5
$UI $S tapx "End Navigation" >/dev/null 2>&1; sleep 2

crash_gate
restore
finish
