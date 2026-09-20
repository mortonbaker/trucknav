#!/bin/bash
# S2 camera measurement: browsing recenter and navigating puck placement in every layout state.
# usage: s2-nav.sh <tag>   (start in landscape, not navigating)
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=100.95.16.47:5555; P=com.morton.trucknav; T=${1:-run}
area() { ~/bin/ui.sh $S find "Showing a Map" | grep -oE "[0-9]+" | tr "\n" " "; }
measure() { adb -s $S exec-out screencap -p > ~/s2-$T-$1.png; printf "%-18s " "$1"; python3 ~/trucknav/docs/puck.py ~/s2-$T-$1.png $(area); }
# rail buttons by their label text, so "Map" never matches the map's own content-desc
rail() { b=$(adb -s $S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s $S shell cat /sdcard/ui.xml | grep -oE "<node[^>]*text=\"$1\"[^>]*>" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+'); set -- $b; adb -s $S shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
rot() { adb -s $S shell settings put system user_rotation $1; sleep 5; }
recenter() { ~/bin/ui.sh $S tap "Center on my location" >/dev/null; sleep 3; }
kb() { adb -s $S shell dumpsys input_method | grep -oE "mInputShown=(true|false)" | head -1; }

echo "== browsing (recenter) =="
rail Music; sleep 2; recenter; measure browse-land-open
rail Map; sleep 2; recenter; measure browse-land-closed
rot 0; recenter; measure browse-port-closed
rail Music; sleep 2; recenter; measure browse-port-open
rot 1

echo "== navigating (Denton) =="
~/bin/ui.sh $S tap "Where to?" >/dev/null; sleep 1; adb -s $S shell input text "Denton%sTX"; sleep 6
~/bin/ui.sh $S tap "Denton, Texas" >/dev/null; sleep 3; echo "after pick: keyboard $(kb)"
adb -s $S exec-out screencap -p > ~/s2-$T-sheet.png
~/bin/ui.sh $S tap "Start navigation" >/dev/null || { echo "Start navigation not found"; exit 1; }; sleep 10
measure nav-land-open
rail Map; sleep 4; measure nav-land-closed
rot 0; sleep 3; measure nav-port-closed
rail Music; sleep 4; measure nav-port-open
rot 1
~/bin/ui.sh $S tap "End Navigation" >/dev/null; sleep 2
echo "crashes: $(adb -s $S logcat -d -b crash | grep -c "Process: $P")"
