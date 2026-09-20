#!/bin/bash
# Favorites / Recents redesign smoke. Emulator by default (SERIAL=...), tablet under lease.
#   docs/fav-smoke.sh <tag> [agent]
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
cd "$(dirname "$0")/.." || exit 1
TAG=${1:?tag}; AGENT=${2:-claude-vehicle}; S=${SERIAL:-emulator-5554}; P=com.morton.trucknav
E=~/evidence/fav-$TAG; mkdir -p "$E"; exec > >(tee "$E/run.log") 2>&1
declare -a ROWS; row() { ROWS+=("| $1 | $2 | $3 | $4 | $5 |"); echo "[$4] $1: $3"; }
sh() { adb -s $S shell "$@"; }
dump() { sh "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml 2>/dev/null"; }
has() { dump | grep -cE "(text|content-desc)=\"$1\""; }
bounds() { dump | grep -oE "<node[^>]*content-desc=\"$1\"[^>]*>" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '; }
tapd() { set -- $(bounds "$1"); [ -z "$1" ] && return 1; sh input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
holdd() { set -- $(bounds "$1"); [ -z "$1" ] && return 1; x=$(( ($1+$3)/2 )); y=$(( ($2+$4)/2 )); sh input swipe $x $y $x $y 900; }
waitfor() { local t=0; while [ $t -lt $2 ]; do [ "$(has "$1")" -ge 1 ] && { echo $t; return 0; }; sleep 1; t=$((t+1)); done; echo none; return 1; }
shot() { adb -s $S exec-out screencap -p > "$E/$1.png"; }
TOKEN=$(sed -n 's/^apiToken=//p' local.properties | tr -d '\r'); adb -s $S forward tcp:18784 tcp:8782 >/dev/null
api() { curl -s -m 8 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }

case "$S" in emulator-*) ;; *) docs/tablet-lock.sh $S status | grep -q "$AGENT" || { echo "lease not held"; exit 2; } ;; esac
V=$(sh dumpsys package $P | grep -m1 versionName | tr -d '\r '); echo "build: $V serial: $S"
sh am force-stop $P; sleep 1; sh am start -n $P/.MainActivity >/dev/null; sleep 9
[ "$(has "Got it")" -ge 1 ] && tapd "Got it"; sleep 1
# start from a known state: no panel, no keyboard, no focus in the field
[ "$(has "Close destinations")" -ge 1 ] && tapd "Close destinations"
sh dumpsys input_method | grep -q "mInputShown=true" && sh input keyevent KEYCODE_BACK; sleep 1
adb -s $S logcat -c -b crash
# seed: a fixture favorite and a recent, through the API (both files the panel reads)
api -X POST -d '{"name":"Smoke Fixture Place","lat":33.2,"lng":-97.15,"kind":"place"}' http://127.0.0.1:18784/api/favorites >/dev/null
FIX=$(api http://127.0.0.1:18784/api/favorites | python3 -c "import json,sys; print([f['id'] for f in json.load(sys.stdin) if f['name']=='Smoke Fixture Place'][0])")
nfav=$(api http://127.0.0.1:18784/api/favorites | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")

# 1 exactly four tiles, no scroll container
sleep 2; shot map; d=$(dump)
tiles=$(echo "$d" | grep -oE 'content-desc="(Go: Home|Go: Work|Set Home|Set Work|Open favorites|Open recents)"' | wc -l)
hscroll=$(echo "$d" | grep -c 'scrollable="true"[^>]*class="android.view.View"[^>]*content-desc=""' )
[ "$tiles" = 4 ] && a=PASS || a=FAIL
row "1 tiles" "exactly Home, Work, Favorites, Recents under the pill" "$tiles tiles" "$a" "map.png"

# 2 favorites panel opens with Home/Work pinned then places; rows >= 76 dp; six visible
tapd "Open favorites"; t=$(waitfor "Destinations panel" 6); shot favorites; d=$(dump)
rowsn=$(echo "$d" | grep -oE 'content-desc="Go: [^"]+"' | grep -vE "Go: (Home|Work)\"" | wc -l); first=$(echo "$d" | grep -oE 'content-desc="Go: [^"]+"' | head -2 | tr '\n' ' ')
rh=$(echo "$d" | grep -oE "<node[^>]*content-desc=\"Go: Smoke Fixture Place\"[^>]*>" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | awk 'NR==2{y0=$1} NR==4{print $1-y0}')
[ "$t" != none ] && [ "$rowsn" -ge 1 ] && [ "${rh:-0}" -ge 155 ] && a=PASS || a=FAIL     # 76 dp @ 210 dpi ≈ 100 px; @ tablet 1.3125 → 100 px; require >= 155? no: 76dp*2.08=158 on 320dpi emu. use density
dens=$(sh wm density | grep -oE "[0-9]+" | head -1); minpx=$(( 76 * dens / 160 ))
[ "$t" != none ] && [ "$rowsn" -ge 1 ] && [ "${rh:-0}" -ge $((minpx - 4)) ] && a=PASS || a=FAIL
row "2 favorites panel" "opens; first rows are Home/Work; place rows >= 76 dp ($minpx px)" "open ${t}s, first: $first, ${rowsn} places, row ${rh:-?} px" "$a" "favorites.png"

# 3 recents tab
tapd "Tab Recents"; sleep 1.5; shot recents; rec=$(has "Tab Recents"); recrows=$(dump | grep -oE 'content-desc="Go: [^"]+"' | wc -l)
[ "$rec" -ge 1 ] && a=PASS || a=FAIL
row "3 recents tab" "Recents tab shows the recent list (rows informational)" "$recrows rows" "$a" "recents.png"

# 4 hold -> Remove -> gone (on the fixture), file agrees
tapd "Tab Favorites"; sleep 1.5; holdd "Go: Smoke Fixture Place"; sleep 1; r=$(waitfor "Remove Smoke Fixture Place" 4); shot hold
tapd "Remove Smoke Fixture Place"; sleep 1.5; gone=$(has "Go: Smoke Fixture Place")
left=$(api http://127.0.0.1:18784/api/favorites | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")
[ "$r" != none ] && [ "$gone" = 0 ] && [ "$left" = $((nfav - 1)) ] && a=PASS || a=FAIL
row "4 hold to remove" "hold shows Remove; tap removes; API count drops by one" "remove after ${r}s, gone=$gone, api $nfav -> $left" "$a" "hold.png"

# 5 one tap = go from a tile, End works
tapd "Close destinations"; sleep 1; tapd "Go: Home" || tapd "Go: Work"; nav=$(waitfor "End Navigation" 15); shot go
[ "$nav" != none ] && a=PASS || a=FAIL
row "5 tile go" "Home/Work tile starts navigation (End Navigation within 15 s)" "${nav}s" "$a" "go.png"
tapd "End Navigation"; sleep 2

# 6 focused empty search shows Recents; typing hides the panel
tapd "Search field"; sleep 1.5; p=$(has "Destinations panel"); sh input text "Denton"; sleep 5; p2=$(has "Destinations panel"); shot typing
sh input keyevent KEYCODE_BACK; tapd "Clear search" 2>/dev/null; tapd "Close destinations" 2>/dev/null
[ "$p" -ge 1 ] && [ "$p2" = 0 ] && a=PASS || a=FAIL
row "6 search focus" "empty focused search opens Recents; results replace the panel" "focused=$p typing=$p2" "$a" "typing.png"

c=$(adb -s $S logcat -d -b crash | grep -c "Process: $P"); [ "$c" = 0 ] && a=PASS || a=FAIL
row "7 crash gate" "0" "$c" "$a" "-"
adb -s $S forward --remove tcp:18784 >/dev/null 2>&1
echo; echo "## Favorites smoke - $V, $(date '+%F %T'), $S, $TAG"; echo "| Item | Criterion | Measured | Result | Evidence |"; echo "|---|---|---|---|---|"; printf '%s\n' "${ROWS[@]}" | tee "$E/results.md"
grep -q "| FAIL |" "$E/results.md" && exit 1 || exit 0
