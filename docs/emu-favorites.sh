#!/bin/bash
# Favorites check on the emulator: search → pick → Save as Home; Home tile appears with an ETA;
# tapping it starts a route within 3 s; recent appears after a route; files exist. usage: emu-favorites.sh <tag>
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
E=emulator-5554; P=com.morton.trucknav; T=${1:-run}; F=/sdcard/Android/data/$P/files
adb -s $E shell "rm -f $F/favorites.json $F/recent.json"
adb -s $E shell am force-stop $P; adb -s $E emu geo fix -97.204973 33.080088 >/dev/null; adb -s $E logcat -c
adb -s $E shell am start -n $P/.MainActivity >/dev/null; sleep 10; ~/bin/ui.sh $E tapx "Got it" >/dev/null 2>&1; ~/bin/ui.sh $E tapx Map >/dev/null; sleep 1
echo "tiles before: $(~/bin/ui.sh $E dump | grep -c 'Go: ')"
~/bin/ui.sh $E tapx "Search field" >/dev/null; sleep 1; adb -s $E shell input text "Whole%sFoods"; sleep 9; adb -s $E shell input keyevent KEYCODE_BACK; sleep 2
~/bin/ui.sh $E tapx "Result: Whole Foods Market, Justin Road, 4041, Highland Village, Texas" >/dev/null; sleep 4
~/bin/ui.sh $E tapx "Save as Home" >/dev/null; sleep 1; echo "sheet after save: $(~/bin/ui.sh $E dump | grep -oE 'text="Saved"' | head -1)"
~/bin/ui.sh $E tapx Close >/dev/null; sleep 3
adb -s $E exec-out screencap -p > ~/fav-$T-tiles.png
sleep 2; for i in 1 2 3 4 5 6; do t=$(~/bin/ui.sh $E dump | grep -oE 'text="[0-9]+ min"' | head -1); [ -n "$t" ] && break; sleep 3; done
echo "tiles after: $(~/bin/ui.sh $E dump | grep -oE 'Go: [^"]+' | tr '\n' ';')  eta: $t"
T0=$(date +%s%N); ~/bin/ui.sh $E tapx "Go: Home" >/dev/null
for i in $(seq 1 20); do adb -s $E logcat -d -s NavLog:* | grep -q "state NAVIGATING" && break; sleep 0.25; done
echo "Home tap -> NAVIGATING after $(( ($(date +%s%N)-T0)/1000000 )) ms (incl ~1.5 s uiautomator)"; adb -s $E exec-out screencap -p > ~/fav-$T-nav.png
~/bin/ui.sh $E tapx "End Navigation" >/dev/null; sleep 3
echo "files:"; adb -s $E shell "cat $F/favorites.json | head -n 12; echo; cat $F/recent.json | head -n 8"
echo "tiles now: $(~/bin/ui.sh $E dump | grep -oE 'Go: [^"]+' | tr '\n' ';')"
echo "crashes: $(adb -s $E logcat -d -b crash | grep -c "Process: $P")"
