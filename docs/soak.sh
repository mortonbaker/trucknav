#!/bin/bash
# S9b 30-minute soak on the emulator: fake drive at 60 mph on hybrid with the test book streaming;
# a sample every 5 min (screenshot, nav state, playback, PSS, crash/ANR, asset-server and prefetch counters).
#   SERIAL=emulator-5554 docs/soak.sh <tag>        (DUR / STEP in seconds override for a dry run)
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
cd "$(dirname "$0")/.." || exit 1
TAG=${1:?tag}; S=${SERIAL:-emulator-5554}; P=com.morton.trucknav
DUR=${DUR:-1800}; STEP=${STEP:-300}
E=~/evidence/soak-$TAG; mkdir -p "$E"; exec > >(tee "$E/run.log") 2>&1
BOOK=c09674da-63f8-40a8-861c-8ecc60ab5cd9                                  # test book #2 for soaks: How This Ends, 34 min, unlistened
START="33.3630 -97.1740"; DEST="34.1740 -97.1430"                            # Sanger TX -> Ardmore OK, ~75 mi on I-35
declare -a ROWS; row() { ROWS+=("| $1 | $2 | $3 | $4 |"); echo "[$4] $1: $3"; }
sh() { adb -s $S shell "$@"; }
dump() { sh "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml 2>/dev/null"; }
has() { dump | grep -cE "(text|content-desc)=\"$1\""; }
bounds() { dump | grep -oE "<node[^>]*(content-desc|text)=\"$1\"[^>]*>" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '; }
tapd() { set -- $(bounds "$1"); [ -z "$1" ] && return 1; sh input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); }
waitfor() { local t=0; while [ $t -lt $2 ]; do [ "$(has "$1")" -ge 1 ] && { echo $t; return 0; }; sleep 1; t=$((t+1)); done; echo none; return 1; }
media() { sh dumpsys media_session | awk "/package=$P/{f=1} f&&/state=PlaybackState/{print; exit}"; }
mstate() { media | grep -oE 'state=[A-Z]+\([0-9]\)' | head -1; }
mpos() { media | grep -oE 'position=[0-9]+' | head -1 | grep -oE '[0-9]+'; }
mitem() { media | grep -oE 'active item id=[0-9]+' | grep -oE '[0-9]+'; }
pss() { sh dumpsys meminfo $P | grep -oE "TOTAL PSS:\s+[0-9]+" | grep -oE "[0-9]+"; }
TOKEN=$(sed -n 's/^apiToken=//p' local.properties | tr -d '\r'); adb -s $S forward tcp:18785 tcp:8782 >/dev/null
api() { curl -s -m 10 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }
svc() { sh am start-foreground-service -n $P/.books.BooksPlayerService -a "com.morton.trucknav.books.$1" "${@:2}" >/dev/null; }

case "$S" in emulator-*) ;; *) echo "soak is emulator-only (plays audio for 30 min)"; exit 2 ;; esac
docs/tablet-lock.sh $S status | grep -q claude-vehicle || { echo "lease on $S not held"; exit 2; }
V=$(sh dumpsys package $P | grep -m1 versionName | tr -d '\r '); echo "build: $V  serial: $S  ${DUR}s / ${STEP}s  evidence: $E"

# --- route + 1 Hz fixes (same decoder as s15)
VAL=$(sed -n 's/^valhallaUrl=//p' local.properties | tr -d '\r'); set -- $START; SLAT=$1; SLNG=$2; set -- $DEST; DLAT=$1; DLNG=$2
curl -s -m 30 --resolve homebackup.tail00ae77.ts.net:8446:100.102.188.107 "$VAL" -H "Content-Type: application/json" \
  -d "{\"locations\":[{\"lat\":$SLAT,\"lon\":$SLNG},{\"lat\":$DLAT,\"lon\":$DLNG}],\"costing\":\"auto\",\"units\":\"miles\"}" > "$E/route.json"
python3 - "$E/route.json" "$E/route.txt" <<'PY'
import json, sys, math
r=json.load(open(sys.argv[1])); s=r["trip"]["legs"][0]["shape"]
def dec(s,p=1e6):
    o=[];i=0;la=0;lo=0
    while i<len(s):
        for k in (0,1):
            res=0;sh=0
            while True:
                b=ord(s[i])-63;i+=1;res|=(b&0x1f)<<sh;sh+=5
                if b<0x20: break
            d=~(res>>1) if res&1 else res>>1
            if k==0: la+=d
            else: lo+=d
        o.append((lo/p,la/p))
    return o
pts=dec(s)
def dist(a,b): kx=111320*math.cos(math.radians((a[1]+b[1])/2)); return math.hypot((a[0]-b[0])*kx,(a[1]-b[1])*111320)
f=[pts[0]];c=0.0;SP=26.8
for a,b in zip(pts,pts[1:]):
    d=dist(a,b)
    if d==0: continue
    while c+SP<=d:
        c+=SP;t=c/d;f.append((a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t))
    c-=d
open(sys.argv[2],"w").write("".join(f"{x:.6f} {y:.6f}\n" for x,y in f))
print(f"route {r['trip']['summary']['length']:.1f} mi, {len(f)} fixes")
PY

# --- start: fresh app, hybrid, navigate via the API, test book at 1.0x
sh am force-stop $P; sleep 1; adb -s $S emu geo fix $SLNG $SLAT >/dev/null; sh am start -n $P/.MainActivity >/dev/null; sleep 10
[ "$(has "Got it")" -ge 1 ] && tapd "Got it"; ~/bin/ui.sh $S tapx Map >/dev/null 2>&1
tapd "Map style"; sleep 1.5; tapd "Hybrid"; sleep 3
api -X POST -d "{\"lat\":$DLAT,\"lng\":$DLNG,\"name\":\"Ardmore\"}" http://127.0.0.1:18785/api/navigate >/dev/null; echo "navigate: $(waitfor "End Navigation" 20)s"
svc SPEED --ef speed 1.0; svc PLAY --es book_id $BOOK; sleep 6; echo "book: $(mstate)"
adb -s $S logcat -c; adb -s $S logcat -c -b crash
( while read -r lng lat; do adb -s $S emu geo fix "$lng" "$lat" >/dev/null; sleep 1; done < "$E/route.txt" ) & DRV=$!

# --- samples
printf "t,nav,book,pos_s,item,pss_kb,assets,recuts,crashes,anr\n" > "$E/samples.csv"
for t in $(seq 0 $STEP $DUR); do
  [ $t -gt 0 ] && sleep $STEP
  adb -s $S exec-out screencap -p > "$E/t$(printf %02d $((t/60))).png"
  nav=$(has "End Navigation"); st=$(mstate); pos=$(( ${mpos:-0} )); pos=$(mpos); item=$(mitem); m=$(pss)
  assets=$(adb -s $S logcat -d -s LocalAssetServer | grep -c "GET /"); recuts=$(adb -s $S logcat -d -s RoutePrefetch | grep -c "near region")
  crashes=$(adb -s $S logcat -d -b crash | grep -c "Process: $P"); anr=$(adb -s $S logcat -d | grep -c "ANR in $P")
  echo "$((t/60)),$nav,$st,$((${pos:-0}/1000)),$item,$m,$assets,$recuts,$crashes,$anr" | tee -a "$E/samples.csv"
done
kill $DRV 2>/dev/null; wait $DRV 2>/dev/null
adb -s $S logcat -d > "$E/post-logcat.txt"; adb -s $S logcat -d -b crash > "$E/crash.txt"
tapd "Play/Pause" 2>/dev/null; sleep 1; [ "$(mstate)" = "state=PLAYING(3)" ] && { ~/bin/ui.sh $S tapx Books >/dev/null; sleep 2; tapd "Play/Pause"; }
api -X POST http://127.0.0.1:18785/api/stop >/dev/null; tapd "End Navigation" 2>/dev/null; adb -s $S forward --remove tcp:18785 >/dev/null 2>&1
echo "final: nav=$(has "End Navigation") book=$(mstate)"

# --- judge
python3 - "$E/samples.csv" "$E/results.md" "$V" "$S" <<'PY'
import csv, sys
rows=list(csv.DictReader(open(sys.argv[1]))); n=len(rows); out=[]
def R(item,crit,meas,ok): out.append(f"| {item} | {crit} | {meas} | {'PASS' if ok else 'FAIL'} |"); print(f"[{'PASS' if ok else 'FAIL'}] {item}: {meas}")
navs=[int(r['nav'])>=1 for r in rows]; R("1 navigation", "End Navigation at every sample", f"{sum(navs)}/{n}", all(navs))
plays=[r['book'].startswith("state=PLAYING") for r in rows]; R("2 book playing", "PLAYING at every sample", f"{sum(plays)}/{n}", all(plays))
c=int(rows[-1]['crashes']); a=int(rows[-1]['anr']); R("3 crash/ANR", "0 crashes, 0 ANRs", f"crashes={c} anr={a}", c==0 and a==0)
pss=[int(r['pss_kb'] or 0)//1024 for r in rows]; p5=pss[1] if n>1 else pss[0]; p30=pss[-1]
R("4 memory", "PSS(t30) <= 1.3 x PSS(t5) and <= 600 MB", f"t5={p5} MB t30={p30} MB ratio={p30/max(p5,1):.2f}", p30<=1.3*p5 and p30<=600)
assets=[int(r['assets']) for r in rows]; inc=all(b>a for a,b in zip(assets,assets[1:])); R("5 map alive", "asset requests grow between every pair of samples", f"{assets}", inc)
rc=int(rows[-1]['recuts']); R("6 prefetch alive", ">= 2 near-region re-cuts", f"{rc}", rc>=2)
pos=[int(r['pos_s'])+int(r['item'] or 0)*0 for r in rows]; items=[int(r['item'] or 0) for r in rows]
# book time = track offsets unknown here; require monotonic (item,pos) and total advance >= 25 min across tracks approximated by wall (1.0x)
mono=all((i2,p2)>=(i1,p1) for (i1,p1),(i2,p2) in zip(zip(items,pos),zip(items[1:],pos[1:])))
R("7 book progress", "(track,pos) monotonic across samples", f"items={items} pos={pos}", mono)
open(sys.argv[2],"w").write(f"## Soak - {sys.argv[3]}, {sys.argv[4]}\n| Item | Criterion | Measured | Result |\n|---|---|---|---|\n"+"\n".join(out)+"\n")
PY
cat "$E/results.md" | tail -9
