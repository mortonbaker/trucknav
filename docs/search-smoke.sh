#!/bin/bash
# Search surface smoke: same container colour on every basemap, >= 4.5:1 text contrast,
# typed text actually visible, car-sized targets, pick -> keyboard down -> sheet.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=100.95.16.47:5555; P=com.morton.trucknav; T=${1:-run}
pick() { ~/bin/ui.sh $S tapx "Map style" >/dev/null; sleep 1.5; ~/bin/ui.sh $S tapx "$1" >/dev/null; sleep 4; }
bounds() { ~/bin/ui.sh $S find "$1" | head -1 | grep -oE "[0-9]+" | tr "\n" " "; }
kb() { adb -s $S shell dumpsys input_method | grep -oE "mInputShown=(true|false)" | head -1; }
adb -s $S logcat -c -b crash; ~/bin/ui.sh $S tapx "End Navigation" >/dev/null 2>&1; sleep 2
for st in Light Satellite Dark; do
  pick $st
  adb -s $S exec-out screencap -p > ~/search-$T-$st-idle.png
  ~/bin/ui.sh $S tapx "Search field" >/dev/null; sleep 1; adb -s $S shell input text "Mount%sScott%sOklahoma"; sleep 6
  adb -s $S exec-out screencap -p > ~/search-$T-$st-typed.png
  echo "-- $st: field bounds $(bounds 'Search field') result bounds $(bounds 'Result:')"
  python3 ~/trucknav/docs/contrast.py ~/search-$T-$st-typed.png $(bounds 'Search field')
  ~/bin/ui.sh $S tapx "Clear search" >/dev/null; sleep 1; echo "   after clear: keyboard $(kb), results: $(~/bin/ui.sh $S dump | grep -c 'Result:')"
done
echo "== pick flow (Light) =="; pick Light
~/bin/ui.sh $S tapx "Search field" >/dev/null; sleep 1; adb -s $S shell input text "Mount%sScott%sOklahoma"; sleep 6
~/bin/ui.sh $S tapx "Result: Mount Scott, Oklahoma" >/dev/null; sleep 5
echo "keyboard $(kb); sheet: $(~/bin/ui.sh $S dump | grep -c 'Start navigation'); field text: $(~/bin/ui.sh $S dump | grep -c 'Mount Scott Oklahoma')"
adb -s $S exec-out screencap -p > ~/search-$T-picked.png
~/bin/ui.sh $S tapx Close >/dev/null
echo "crashes: $(adb -s $S logcat -d -b crash | grep -c "Process: $P")"
