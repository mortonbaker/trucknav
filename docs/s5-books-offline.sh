#!/bin/bash
# S5 — Books offline downloads. Falsifiable criteria from docs/BUILD-PLAN.md, measured on the
# real tablet. Run on atlas01:  docs/s5-books-offline.sh <unique-tag> [agent]
# Needs the tablet lease (docs/tablet-lock.sh) held by <agent>. Prints a PASS/FAIL table and
# leaves every raw artifact under ~/evidence/s5-<tag>/.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
cd "$(dirname "$0")/.." || exit 1
TAG=${1:?tag}; AGENT=${2:-claude-studio}
S=100.95.16.47:5555; P=com.morton.trucknav
BOOK=8c5b4b84-f32a-4a0e-a8b3-8c64f2809915   # Counter-Elites and the New Populism, 4 tracks, 1124 s
TITLE="Counter-Elites and the New Populism"
FILES=/sdcard/Android/data/$P/files/books/$BOOK
E=~/evidence/s5-$TAG; mkdir -p "$E"
CUT=300                                       # seconds offline
declare -a ROWS
row() { ROWS+=("| $1 | $2 | $3 | $4 | $5 |"); echo "[$4] $1: $3"; }
sh() { adb -s $S shell "$@"; }
tap() { ~/bin/ui.sh $S tapx "$1" >/dev/null || ~/bin/ui.sh $S tap "$1" >/dev/null; }
shot() { adb -s $S exec-out screencap -p > "$E/$1.png"; }
dump() { adb -s $S shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb -s $S shell cat /sdcard/ui.xml; }
# book-time from the live session: offsets[active item] + position
media() { sh dumpsys media_session 2>/dev/null | awk '/package=com.morton.trucknav/{f=1} f&&/state=PlaybackState/{print; exit}'; }
mstate() { media | grep -oE 'state=[A-Z]+\([0-9]\)' | head -1; }
mitem() { media | grep -oE 'active item id=[0-9]+' | grep -oE '[0-9]+'; }
mpos() { media | grep -oE 'position=[0-9]+' | head -1 | grep -oE '[0-9]+'; }
booktime() { local i=$(mitem) p=$(mpos); [ -z "$i" ] && { echo 0; return; }; echo "${OFF[$i]} $p" | awk '{printf "%d", $1 + $2/1000}'; }
progress() { docs/abs.sh progress $BOOK; }
svc() { sh am start-foreground-service -n $P/.books.BooksPlayerService -a "com.morton.trucknav.books.$1" "${@:2}" >/dev/null; }
waitadb() { for i in $(seq 1 60); do adb -s $S shell true >/dev/null 2>&1 && return 0; adb connect $S >/dev/null 2>&1; sleep 3; done; return 1; }

# --- contract ---------------------------------------------------------------------------
docs/tablet-lock.sh $S status | grep -q "$AGENT" || { echo "tablet lease not held by $AGENT — aborting"; docs/tablet-lock.sh $S status; exit 2; }
sh am start -n $P/.MainActivity >/dev/null 2>&1; sleep 3   # an install leaves the old launcher on screen
V=$(sh dumpsys package $P | grep -m1 versionName | tr -d '\r '); echo "build: $V  tag: $TAG  evidence: $E"
echo "$V" > "$E/version.txt"
sh dumpsys media_session > "$E/pre-media.txt"; adb -s $S logcat -d > "$E/pre-logcat.txt"; shot pre
sh dumpsys activity activities | grep -m1 topResumedActivity > "$E/pre-foreground.txt"
adb -s $S logcat -c; adb -s $S logcat -c -b crash
tap Books; sleep 2; SPEED0=$(dump | grep -oE 'text="Speed  [^"]+"' | head -1 | grep -oE '[0-9.]+'); echo "operator speed: ${SPEED0:-?}"

# Server-side truth for the sizes and offsets.
docs/abs.sh get "/api/items/$BOOK?expanded=1" > "$E/item.json"
python3 - "$E/item.json" > "$E/tracks.txt" <<'PY'
import json,sys; m=json.load(open(sys.argv[1]))["media"]
for t in m["tracks"]: print(t["index"], t["startOffset"], t["metadata"]["size"], t["metadata"]["ext"])
PY
declare -A OFF SIZE
while read -r i off size ext; do OFF[$((i-1))]=$off; SIZE[$i$ext]=$size; done < "$E/tracks.txt"

# --- D1 download --------------------------------------------------------------------------
sh rm -rf $FILES 2>/dev/null
tap Books; sleep 2; tap Library; sleep 6; shot d1-library-before
tap "Download $TITLE" || svc DOWNLOAD --es book_id $BOOK
T0=$(date +%s)
for i in $(seq 1 60); do sh ls $FILES/manifest.json >/dev/null 2>&1 && break; sleep 3; done
T1=$(date +%s); sh ls -l $FILES | tr -d '\r' > "$E/d1-ls.txt"; cat "$E/d1-ls.txt"
ok=1; detail=""
for k in "${!SIZE[@]}"; do
  have=$(awk -v f="$k" '$NF==f{print $5}' "$E/d1-ls.txt"); [ "$have" = "${SIZE[$k]}" ] || ok=0; detail="$detail $k:$have/${SIZE[$k]}"
done
grep -q manifest.json "$E/d1-ls.txt" || ok=0
row "D1 download" "4 track files, each size == server file size; manifest.json present" "took $((T1-T0)) s;$detail" "$([ $ok = 1 ] && echo PASS || echo FAIL)" "d1-ls.txt"
# --- D2 plays from local files ----------------------------------------------------------
svc SPEED --ef speed 1.0
tap "$TITLE" || { tap Back; svc PLAY --es book_id $BOOK; }; sleep 8
src=$(adb -s $S logcat -d -s BooksPlayer | grep -oE 'opened .*source=[a-z]+ session=[a-z]+' | tail -1)
shot d2-pane; dump > "$E/d2-ui.xml"
echo "$src" | grep -q "source=local" && a=PASS || a=FAIL
row "D2 local source" "player log says source=local for the downloaded book" "$src" "$a" "d2-pane.png"
grep -q 'text="Delete download"' "$E/d2-ui.xml" && a=PASS || a=FAIL
row "D2b pane buttons" "'Downloaded' + 'Delete download' on the pane" "$(grep -oE 'text="Downloaded[^"]*"' "$E/d2-ui.xml" | head -1)" "$a" "d2-ui.xml"
# The book is now in Continue, so its badge is on screen.
tap Library; sleep 5; shot d1-continue-after; dump > "$E/d1-ui.xml"
grep -q 'content-desc="Downloaded"' "$E/d1-ui.xml" && b=PASS || b=FAIL
row "D1b badge + storage line" "check badge on the cover; 'Downloads: N used, M free' text" "$(grep -oE 'text="Downloads: [^"]+"' "$E/d1-ui.xml" | head -1)" "$b" "d1-continue-after.png"
tap Back; sleep 2

# --- D3 five-minute cut across a track boundary ------------------------------------------
svc SEEK_TO --el book_ms $(awk -v o="${OFF[1]}" 'BEGIN{printf "%d", (o-60)*1000}')   # 60 s before track 2
sleep 3; [ "$(mstate)" = "state=PLAYING(3)" ] || tap "Play/Pause"; sleep 2
B0=$(booktime); I0=$(mitem); echo "before cut: book-time ${B0}s item $I0 $(mstate)"
sh rm -f /sdcard/s5-offline.log
# The tablet samples itself while adb is gone, then restores Wi-Fi on its own.
cat > "$E/sampler.sh" <<SH
svc wifi disable
i=0; while [ \$i -lt $((CUT/5)) ]; do
  echo "\$(date +%s) \$(dumpsys media_session | grep -A12 package=$P | grep state=PlaybackState)" >> /sdcard/s5-offline.log
  sleep 5; i=\$((i+1))
done
svc wifi enable
SH
adb -s $S push "$E/sampler.sh" /sdcard/s5-sampler.sh >/dev/null
sh "nohup sh /sdcard/s5-sampler.sh >/dev/null 2>&1 &"
TC=$(date +%s); echo "cut at $(date +%T), sleeping $((CUT+20)) s"; sleep $((CUT+20))
waitadb || { row "D3 offline 5 min" "adb back after the cut" "tablet never came back" "BLOCKED" "-"; }
TR=$(date +%s); echo "adb back after $((TR-TC)) s"
# Hardware touches (InputReader Btn_touch) during the cut are a human on the tablet, not the app.
adb -s $S logcat -d -v epoch | awk -v a=$TC -v b=$TR '/InputReader.*Btn_touch.*value=1/ && $1+0>=a && $1+0<=b' > "$E/touches-during-cut.txt"
TOUCH=$(wc -l < "$E/touches-during-cut.txt"); echo "hardware touches during cut: $TOUCH"
adb -s $S pull /sdcard/s5-offline.log "$E/offline-samples.txt" >/dev/null
python3 - "$E/offline-samples.txt" "${OFF[0]}" "${OFF[1]}" "${OFF[2]}" "${OFF[3]}" > "$E/d3-analysis.txt" <<'PY'
import re,sys
off=[float(x) for x in sys.argv[2:6]]; rows=[]
for l in open(sys.argv[1]):
    m=re.search(r'^(\d+) .*?state=([A-Z]+)\(\d\), position=(\d+).*?active item id=(\d+)',l)
    if m: rows.append((int(m[1]),m[2],int(m[3]),int(m[4])))
bt=[off[i]+p/1000 for _,_,p,i in rows]
mono=all(b2>=b1 for b1,b2 in zip(bt,bt[1:]))
playing=sum(1 for r in rows if r[1]=="PLAYING")
items=sorted({r[3] for r in rows})
span=rows[-1][0]-rows[0][0] if rows else 0
adv=bt[-1]-bt[0] if rows else 0
print(f"samples={len(rows)} playing={playing} items={items} wall={span}s advanced={adv:.0f}s monotonic={mono}")
ok=len(rows)>=50 and playing==len(rows) and mono and len(items)>=2 and abs(adv-span)<=0.05*span+5
print("RESULT", "PASS" if ok else "FAIL")
PY
cat "$E/d3-analysis.txt"
r=$(grep -oE 'RESULT [A-Z]+' "$E/d3-analysis.txt" | cut -d' ' -f2)
[ "$TOUCH" -gt 0 ] && [ "$r" != PASS ] && r="BLOCKED (human touched the tablet $TOUCH x during the cut)"
row "D3 offline 5 min" ">=50 samples all PLAYING, book-time monotonic, crosses a track boundary, advance == wall ±5%" "$(head -1 "$E/d3-analysis.txt")" "${r:-FAIL}" "offline-samples.txt"

# --- D4 queued sync flushes ---------------------------------------------------------------
# Server progress must land within 20 s of the pane position; measure how long after adb-return it takes.
conv=""; for i in $(seq 1 12); do tb=$(booktime); sp=$(progress); d=$(( tb>sp ? tb-sp : sp-tb )); echo "$(date +%T) tablet=${tb}s server=${sp}s diff=${d}s" >> "$E/d4-poll.txt"; if [ "$d" -le 20 ]; then conv=$(( $(date +%s)-TR )); break; fi; sleep 5; done
[ -n "$conv" ] && a=PASS || a=FAIL
row "D4 sync after Wi-Fi" "|server - pane| <= 20 s" "$(tail -1 "$E/d4-poll.txt") (converged ${conv:-never} s after adb return)" "$a" "d4-poll.txt"
adb -s $S logcat -d -s BooksPlayer > "$E/booksplayer-logcat.txt"
grep -c "queued" "$E/booksplayer-logcat.txt" | xargs -I{} echo "queued syncs during cut: {}"; grep -m3 "flushed" "$E/booksplayer-logcat.txt"

# --- D5 delete falls back to streaming --------------------------------------------------
tap "Play/Pause"; sleep 1; tap "Delete download"; sleep 3
sh ls $FILES >/dev/null 2>&1 && a=FAIL || a=PASS
shot d5-after-delete
row "D5a delete" "book directory gone" "ls $FILES -> $(sh ls $FILES 2>&1 | tr -d '\r' | head -1)" "$a" "d5-after-delete.png"
tap Library; sleep 5; tap "$TITLE" || { tap Back; svc PLAY --es book_id $BOOK; }; sleep 8
src=$(adb -s $S logcat -d -s BooksPlayer | grep -oE 'opened .*source=[a-z]+ session=[a-z]+' | tail -1)
echo "$src" | grep -q "source=stream" && a=PASS || a=FAIL
row "D5b stream fallback" "reopening the book streams (source=stream)" "$src" "$a" "booksplayer-logcat.txt"

# --- crash gate + restore --------------------------------------------------------------
adb -s $S logcat -d -b crash > "$E/crash.txt"; c=$(grep -c "Process: $P" "$E/crash.txt")
row "Crash gate" "0 crashes for $P" "$c" "$([ "$c" = 0 ] && echo PASS || echo FAIL)" "crash.txt"
tap "Play/Pause"; sleep 1   # leave it paused, as found
[ -n "$SPEED0" ] && svc SPEED --ef speed $SPEED0
sh rm -f /sdcard/s5-sampler.sh
adb -s $S logcat -d > "$E/post-logcat.txt"; sh dumpsys media_session > "$E/post-media.txt"; shot post
sh rm -f /sdcard/s5-offline.log /sdcard/ui.xml

echo; echo "## S5 results — $V, $(date '+%F %T'), tag $TAG"; echo "| Item | Criterion | Measured | Result | Evidence |"; echo "|---|---|---|---|---|"
printf '%s\n' "${ROWS[@]}" | tee "$E/results.md"
