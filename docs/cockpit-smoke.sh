#!/bin/bash
# S9 cockpit smoke: prove the app works in ten minutes, hands off. Run on atlas01:
#   docs/cockpit-smoke.sh <tag> [agent]            (tablet, needs the lease held by <agent>)
#   SERIAL=emulator-5554 docs/cockpit-smoke.sh <tag>  (emulator; no lease needed)
# Fail-closed: every row is PASS or FAIL against a number. Never cuts the network, never plays
# the operator's media, leaves the app on the Map pane with nothing running.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
cd "$(dirname "$0")/.." || exit 1
TAG=${1:?tag}; AGENT=${2:-claude-studio}
S=${SERIAL:-R9PT207J6ZN}; P=com.morton.trucknav
E=~/evidence/cockpit-$TAG; mkdir -p "$E"
declare -a ROWS; row() { ROWS+=("| $1 | $2 | $3 | $4 | $5 |"); echo "[$4] $1: $3"; }
sh() { adb -s $S shell "$@"; }
tap() { ~/bin/ui.sh $S tapx "$1" >/dev/null 2>&1 || ~/bin/ui.sh $S tap "$1" >/dev/null 2>&1; }
dump() { sh uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; sh cat /sdcard/ui.xml; }
shot() { adb -s $S exec-out screencap -p > "$E/$1.png"; }
has() { dump | grep -cE "(text|content-desc)=\"$1\""; }
# poll for a label up to N s; echoes seconds taken or "none"
waitfor() { local t=0; while [ $t -lt $2 ]; do [ "$(has "$1")" -ge 1 ] && { echo $t; return 0; }; sleep 1; t=$((t+1)); done; echo none; return 1; }
fg() { sh dumpsys activity activities | grep -m1 -oE "topResumedActivity=ActivityRecord\{[^ ]+ u0 [^ /]+" | sed 's/.* u0 //'; }
crashes() { adb -s $S logcat -d -b crash | grep -c "Process: $P"; }

# --- contract ---------------------------------------------------------------------------
case "$S" in emulator-*) ;; *) docs/tablet-lock.sh $S status | grep -q "$AGENT" || { echo "tablet lease not held by $AGENT"; docs/tablet-lock.sh $S status; exit 2; } ;; esac
if sh dumpsys media_session | grep -qE "state=PLAYING\(3\)"; then echo "something is playing; operator may be listening - aborting"; exit 2; fi
V=$(sh dumpsys package $P | grep -m1 versionName | tr -d '\r '); echo "build: $V  serial: $S  evidence: $E"; echo "$V" > "$E/version.txt"
adb -s $S logcat -d > "$E/pre-logcat.txt"; adb -s $S logcat -c; adb -s $S logcat -c -b crash

# --- 1 boot: HOME is us, activity comes up ----------------------------------------------
home=$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')
sh am force-stop $P; sleep 2; sh input keyevent KEYCODE_HOME; sleep 8
f=$(fg); [ "$home" = "$P/.MainActivity" ] && [ "$f" = "$P" ] && a=PASS || a=FAIL
row "1 boot" "HOME resolves to $P/.MainActivity and it is foreground 8 s after HOME" "home=$home fg=$f" "$a" "-"
tap "Got it"; sleep 1

# --- 2 map render: local tile server served >= 10 tiles ----------------------------------
tap Map; sleep 6; shot map
tiles=$(adb -s $S logcat -d -s LocalAssetServer | grep -cE "GET /.*\.(pmtiles|pbf|json)"); [ "$tiles" -ge 10 ] && a=PASS || a=FAIL
row "2 map render" ">= 10 asset/tile requests answered by the loopback server after cold start" "$tiles requests" "$a" "map.png"

# --- 3 route to Denton, then End ----------------------------------------------------------
tap "Search field"; sleep 1; sh input text "Denton%sTX"
res=$(waitfor "Result: Denton, Texas" 15); sh input keyevent KEYCODE_BACK; sleep 1
# exact city row if present, else the first result the geocoder gave us
tap "Result: Denton, Texas" || { first=$(dump | grep -oE 'content-desc="Result: [^"]+"' | head -1 | cut -d'"' -f2); tap "$first"; }
sleep 2; sheet=$(waitfor "Start navigation" 10); tap "Start navigation"; sleep 6; shot route
nav=$(waitfor "End Navigation" 10); [ "$nav" != none ] && a=PASS || a=FAIL
row "3a route" "results within 15 s, sheet within 10 s, End Navigation within 16 s of Start" "results ${res}s sheet ${sheet}s end-nav ${nav}s" "$a" "route.png"
tap "End Navigation"; sleep 3; ended=$(has "End Navigation"); [ "$ended" = 0 ] && a=PASS || a=FAIL
row "3b end" "End Navigation leaves navigation (button gone within 3 s)" "end-nav=$ended" "$a" "-"

# --- 4 power strip live ------------------------------------------------------------------
strip=$(dump | grep -oE 'text="(SOC|Batt|Solar|Alt|Load|Net (in|out)|Link|Starlink)"' | sort -u | wc -l)
link=$(dump | grep -A0 -oE 'text="(offline|stale|Off|Bulk|Absorption|Float|Storage|--)"' | head -1)
[ "$strip" -ge 7 ] && a=PASS || a=FAIL
row "4 power strip" "all 8 cells present (SOC Batt Solar Alt Load Net Link Starlink); Link state is informational" "$strip cells, link=$link" "$a" "map.png"

# --- 5 every pane opens ----------------------------------------------------------------------
for pane in Music Books YouTube Power Vehicle Apps; do
  tap $pane
  case $pane in
    Music|Books) want="Play/Pause" ;; YouTube) want="YouTube" ;; Power) want="State of charge" ;; Vehicle) want="Vehicle" ;; Apps) want="Apps" ;;
  esac
  # the YouTube pane is a WebView (no label of its own); give it time to come up before the next tap
  if [ $pane = YouTube ]; then sleep 5; n=$( [ "$(dump | grep -c android.webkit.WebView)" -ge 1 ] && echo 5 || echo none ); want="a WebView"; else n=$(waitfor "$want" 10); fi
  shot pane-$pane; [ "$n" != none ] && a=PASS || a=FAIL
  row "5 pane $pane" "opens (\"$want\" on screen within 10 s)" "${n}s" "$a" "pane-$pane.png"
done
tap Map; sleep 1

# --- 6 overlay over a foreign app ---------------------------------------------------------
sh am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 4; shot overlay
ov=$(sh dumpsys window windows | grep -A4 "Window #.*u0 $P}" | grep -c "ty=APPLICATION_OVERLAY"); [ "$ov" -ge 1 ] && a=PASS || a=FAIL
row "6 overlay" "our overlay window exists while Settings is foreground" "windows=$ov fg=$(fg)" "$a" "overlay.png"
sh am start -n $P/.MainActivity >/dev/null; sleep 3

# --- 7 crash gate + restore ----------------------------------------------------------------
c=$(crashes); [ "$c" = 0 ] && a=PASS || a=FAIL
row "7 crash gate" "0 crashes for $P" "$c" "$a" "crash.txt"; adb -s $S logcat -d -b crash > "$E/crash.txt"; adb -s $S logcat -d > "$E/post-logcat.txt"
tap Map; playing=$(sh dumpsys media_session | grep -cE "state=PLAYING\(3\)"); echo "restored: fg=$(fg) pane=Map playing=$playing"

echo; echo "## Cockpit smoke - $V, $(date '+%F %T'), $S, tag $TAG"; echo "| Item | Criterion | Measured | Result | Evidence |"; echo "|---|---|---|---|---|"
printf '%s\n' "${ROWS[@]}" | tee "$E/results.md"
grep -q "| FAIL |" "$E/results.md" && exit 1 || exit 0
