#!/bin/bash
# Arrival check on an emulator (S17.10 / B11). Start 14 fixes from the end of the Whole Foods
# polyline (no GPS-gate teleport), route there, replay the last fixes, and verify:
# arrival logged once, no instruction spoken after it, card "Arrived" + "Done" shown,
# navigation IDLE within the linger window, End Navigation gone, 0 crashes.
# usage: emu-arrival.sh [emulator-serial] [tag]
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
E=${1:-emulator-5556}; T=${2:-run}; P=com.morton.trucknav; R=$HOME/route-wholefoods.txt
UI=$HOME/bin/ui.sh
tail -n 14 $R > /tmp/arrival-tail.txt; read -r LNG LAT < /tmp/arrival-tail.txt
adb -s $E shell am force-stop $P; adb -s $E emu geo fix $LNG $LAT >/dev/null; adb -s $E logcat -c
adb -s $E shell am start -n $P/.MainActivity >/dev/null; sleep 10; $UI $E tapx "Got it" >/dev/null 2>&1; $UI $E tapx Map >/dev/null
for i in 1 2 3; do adb -s $E emu geo fix $LNG $LAT >/dev/null; sleep 2; done
$UI $E tapx "Search field" >/dev/null; sleep 1; adb -s $E shell input text "Whole%sFoods"; sleep 9; adb -s $E shell input keyevent KEYCODE_BACK; sleep 2
$UI $E tapx "Result: Whole Foods Market, Justin Road, 4041, Highland Village, Texas" >/dev/null; sleep 6
$UI $E tapx "Start navigation" >/dev/null
for i in $(seq 1 40); do adb -s $E logcat -d -s NavLog:* | grep -q "state NAVIGATING" && break; sleep 0.25; done
echo "navigating: $(adb -s $E logcat -d -s NavLog:* | grep -c 'state NAVIGATING')  route: $(adb -s $E logcat -d -s NavLog:* | grep -oE 'preview gen=1 annotated.*distance=[0-9.]+mi' | grep -oE 'distance=[0-9.]+mi')"
T0=$(date +%s)
tail -n 13 /tmp/arrival-tail.txt | while read -r lng lat; do adb -s $E emu geo fix $lng $lat >/dev/null; sleep 3; done
for i in $(seq 1 20); do adb -s $E logcat -d -s NavLog:* | grep -q " arrival " && break; sleep 1; done
TA=$(date +%s); echo "arrival logged after $((TA-T0)) s: $(adb -s $E logcat -d -s NavLog:* | grep -oE 'arrival .*' | head -1)"
sleep 2; adb -s $E exec-out screencap -p > ~/arrival-$T-card.png
D=$($UI $E dump); echo "card: Arrived=$(echo "$D" | grep -c 'content-desc="Arrived"') Done=$(echo "$D" | grep -c 'content-desc="Done"') End=$(echo "$D" | grep -c 'End Navigation')"
for i in $(seq 1 20); do adb -s $E logcat -d -s NavLog:* | grep -q "state IDLE" && break; sleep 1; done
echo "idle after $(( $(date +%s)-TA )) s from arrival: $(adb -s $E logcat -d -s NavLog:* | grep -oE 'state IDLE.*' | head -1)"
sleep 2; adb -s $E exec-out screencap -p > ~/arrival-$T-after.png
D=$($UI $E dump); echo "after: Arrived=$(echo "$D" | grep -c 'content-desc="Arrived"') End=$(echo "$D" | grep -c 'End Navigation') Search=$(echo "$D" | grep -c 'content-desc="Search field"')"
L=$(adb -s $E logcat -d -s NavLog:*)
echo "spoken after arrival: $(echo "$L" | sed -n '/ arrival /,$p' | grep -c ' spoken ')  visuals after arrival: $(echo "$L" | sed -n '/ arrival /,$p' | grep -c ' visual ')  reroutes: $(echo "$L" | grep -c ' reroute ')  arrivals: $(echo "$L" | grep -c ' arrival ')"
echo "crashes: $(adb -s $E logcat -d -b crash | grep -c "Process: $P")"
echo "--- log tail"; echo "$L" | grep -E ' (arrival|state|stop|spoken|visual|reroute|deviation-handler|gps) ' | tail -n 14 | cut -c1-200
