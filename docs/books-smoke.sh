#!/bin/bash
# Books player smoke test, D-items from docs/BOOKS-PLAYER-PLAN.md. Run on atlas01.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=100.95.16.47:5555; P=com.morton.trucknav
ABS=https://homebackup.tail00ae77.ts.net
CURL="curl -s -m 15 --resolve homebackup.tail00ae77.ts.net:443:100.102.188.107"
# credentials come from local.properties (absUser / absPass), never from this file
ABS_USER=$(grep -oP '^absUser=\K.*' ~/trucknav/local.properties); ABS_PASS=$(grep -oP '^absPass=\K.*' ~/trucknav/local.properties)
T=$($CURL -X POST $ABS/login -H "Content-Type: application/json" -d "{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASS\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['user']['token'])")
BOOK=e02c57bd-0f93-4bcf-b510-546890104d30   # The American Deep State, 15 tracks
progress() { $CURL -H "Authorization: Bearer $T" "$ABS/api/me/progress/$BOOK" | python3 -c "import json,sys;print(round(json.load(sys.stdin).get('currentTime',0)))"; }
pos() { adb -s $S shell dumpsys media_session | awk '/package=com.morton.trucknav/{f=1} f&&/position=/{match($0,/position=[0-9]+/); print int(substr($0,RSTART+9,RLENGTH-9)/1000); exit}'; }
trackpos() { adb -s $S shell dumpsys media_session | awk '/package=com.morton.trucknav/{f=1} f&&/state=/{print; exit}' | grep -oE "state=[A-Z]+\([0-9]\)|speed=[0-9.]+" | tr "
" " "; }
speedlabel() { ~/bin/ui.sh $S dump | grep -oE 'text="Speed  [^"]+"' | head -1 | sed 's/text="Speed  //; s/"//'; }
crashes() { adb -s $S logcat -d -b crash | grep -c "Process: $P"; }
tap() { ~/bin/ui.sh $S tap "$1" >/dev/null; }

adb -s $S logcat -c; adb -s $S logcat -c -b crash
echo "server progress before: $(progress)s"
tap Books; sleep 2; tap Library; sleep 6; tap "The American Deep State"; sleep 8
P0=$(pos); echo "D1/D2 track position after open: ${P0}s [$(trackpos)] server book-time before: see above"
echo "== D11a speed ladder =="
for i in 1 2 3 4 5 6 7; do tap "Speed"; sleep 1; printf "%s " "$(speedlabel)"; done; echo
echo "== D11b: set 2.0x, measure 30 s =="
while [ "$(speedlabel)" != "2.0×" ]; do tap "Speed"; sleep 1; done
A=$(pos); T0=$(date +%s); sleep 30; B=$(pos); T1=$(date +%s); echo "2.0x: +$((B-A))s in $((T1-T0))s wall [$(trackpos)] (expect 2x wall ±2)"
echo "== D5 seek ±30 =="
tap "Play/Pause"; sleep 2; A=$(pos); tap "Forward 30s"; sleep 2; B=$(pos); tap "Back 30s"; sleep 2; C=$(pos); echo "paused seek: +30 -> $((B-A))s, then -30 -> $((C-B))s (expect +30/-30 ±1)"; tap "Play/Pause"; sleep 2
echo "== D3 sync =="
tap "Play/Pause"; sleep 20; echo "paused at track-pos $(pos)s; server book-time now $(progress)s (book-time = track offset + track-pos; Deep State track 1 offset 0, track 2 starts 2177s)"
echo "== D7 force-stop =="
adb -s $S shell am force-stop $P; sleep 3; adb -s $S shell input keyevent KEYCODE_HOME; sleep 12; tap Books; sleep 4; echo "speed after restart: $(speedlabel)"; echo "server progress: $(progress)s"
echo "== D10 crashes: $(crashes) =="
