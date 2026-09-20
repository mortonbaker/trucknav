#!/bin/bash
# Add-a-stop check on an emulator (S17.11). Route home -> Whole Foods, tap "Add stop", search a
# Kroger, pick it; the route must be replaced by one through the stop (longer, 2 legs), the
# instruction must change, no crash. Then the API: add_stop while navigating = 200, when idle = 409.
# usage: emu-addstop.sh [emulator-serial] [tag]
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
E=${1:-emulator-5556}; T=${2:-run}; P=com.morton.trucknav; UI=$HOME/bin/ui.sh
TOK=$(grep '^apiToken=' $HOME/trucknav-nav/local.properties | cut -d= -f2-)
adb -s $E forward tcp:18782 tcp:8782 >/dev/null
api() { curl -s -m 8 -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" "$@"; }
adb -s $E shell am force-stop $P; adb -s $E emu geo fix -97.204973 33.080088 >/dev/null; adb -s $E logcat -c
adb -s $E shell am start -n $P/.MainActivity >/dev/null; sleep 10; $UI $E tapx "Got it" >/dev/null 2>&1; $UI $E tapx Map >/dev/null
for i in 1 2 3; do adb -s $E emu geo fix -97.204973 33.080088 >/dev/null; sleep 2; done
echo "idle add_stop -> $(api -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:18782/api/add_stop -d '{"lat":33.06,"lng":-97.1,"name":"x"}')"
api -X POST http://127.0.0.1:18782/api/navigate -d '{"lat":33.072812,"lng":-97.085042,"name":"Whole Foods Market"}' >/dev/null
for i in $(seq 1 60); do adb -s $E logcat -d -s NavLog:* | grep -q "state NAVIGATING" && break; sleep 0.5; done
sleep 3; R0=$(adb -s $E logcat -d -s NavLog:* | grep -oE 'progress .*remaining=[0-9.]+mi eta=[0-9]+min' | tail -1 | grep -oE 'remaining=.*')
echo "navigating: $(adb -s $E logcat -d -s NavLog:* | grep -c 'state NAVIGATING')  before: $R0"
D=$($UI $E dump); echo "button: Add stop=$(echo "$D" | grep -c 'content-desc="Add stop"')"
$UI $E tapx "Add stop" >/dev/null; sleep 1.5
D=$($UI $E dump); echo "search open: field=$(echo "$D" | grep -c 'content-desc="Search field"')"
adb -s $E exec-out screencap -p > ~/addstop-$T-open.png
$UI $E tapx "Search field" >/dev/null; sleep 1; adb -s $E shell input text "Kroger"; sleep 9; adb -s $E shell input keyevent KEYCODE_BACK; sleep 2
adb -s $E exec-out screencap -p > ~/addstop-$T-results.png
HIT=$($UI $E dump | grep -oE 'content-desc="Result: Kroger[^"]*"' | head -1 | cut -d\" -f2); echo "picking: $HIT"
$UI $E tapx "$HIT" >/dev/null
for i in $(seq 1 30); do adb -s $E logcat -d -s NavLog:* | grep -q "route stop-add" && break; sleep 0.5; done
sleep 12; adb -s $E exec-out screencap -p > ~/addstop-$T-after.png   # next progress line is ~10 s out
L=$(adb -s $E logcat -d -s NavLog:*)
echo "stop-add: $(echo "$L" | grep -oE 'route stop-add.*steps=[0-9]+' | grep -oE 'distance=.*' | head -1)"
echo "camera following again: $($UI $E dump | grep -ci 'content-desc="[^"]*recenter')=0 wanted"
echo "after: $(echo "$L" | grep -oE 'progress .*remaining=[0-9.]+mi eta=[0-9]+min' | tail -1 | grep -oE 'remaining=.*')  search closed: $($UI $E dump | grep -c 'content-desc="Search field"')"
echo "api add_stop while navigating -> $(api -X POST http://127.0.0.1:18782/api/add_stop -d '{"lat":33.0985,"lng":-97.1795,"name":"QuikTrip Gasoline Alley"}')"
for i in $(seq 1 30); do [ "$(adb -s $E logcat -d -s NavLog:* | grep -c 'route stop-add')" -ge 2 ] && break; sleep 0.5; done
sleep 12; echo "second stop-add: $(adb -s $E logcat -d -s NavLog:* | grep -oE 'route stop-add.*steps=[0-9]+' | grep -oE 'distance=.*' | tail -1)  after: $(adb -s $E logcat -d -s NavLog:* | grep -oE 'progress .*remaining=[0-9.]+mi eta=[0-9]+min' | tail -1 | grep -oE 'remaining=.*')"
echo "crashes: $(adb -s $E logcat -d -b crash | grep -c "Process: $P")"
api -X POST http://127.0.0.1:18782/api/stop >/dev/null
echo "--- log"; adb -s $E logcat -d -s NavLog:* | grep -E ' (stop-add|stop-passed|state|route |visual|route-error|deviation-handler) ' | cut -c34-230 | tail -n 16
