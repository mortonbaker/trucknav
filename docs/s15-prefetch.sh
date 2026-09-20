#!/bin/bash
# S15 tile prefetch: a 5-minute fake drive at 60 mph on the hybrid style, screenshot every 5 s,
# grey (no-imagery) share of the map area measured against the style's sentinel background.
# Run on atlas01 against the emulator (SERIAL=emulator-5554 default):
#   docs/s15-prefetch.sh <tag> [baseline|prefetch|baseline-off|prefetch-off]
# "-off" cuts the emulator's Wi-Fi once navigation is running (after the corridor is warm, for prefetch-off)
# and restores it at the end: the strongest form of "the dish dropped". Emulator only, never the tablet.
# Network is shaped to Starlink-like latency on the emulator console for the run and restored after.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
cd "$(dirname "$0")/.." || exit 1
TAG=${1:?tag}; MODE=${2:-prefetch}; AGENT=${AGENT:-claude-vehicle}
S=${SERIAL:-emulator-5554}; P=com.morton.trucknav
E=~/evidence/s15-$TAG; mkdir -p "$E"
exec > >(tee "$E/run.log") 2>&1
F=/sdcard/Android/data/$P/files
TOKEN=$(sed -n 's/^apiToken=//p' local.properties | tr -d '\r')
# I-35 north of Denton: Sanger -> Gainesville, ~40 km of interstate, 60 mph = 27 m/s
START="33.3630 -97.1740"; DEST="33.6260 -97.1330"
DUR=${DUR:-300}; STEP=5
sh() { adb -s $S shell "$@"; }
tap() { ~/bin/ui.sh $S tapx "$1" >/dev/null 2>&1 || ~/bin/ui.sh $S tap "$1" >/dev/null 2>&1; }
api() { curl -s -m 10 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }

docs/tablet-lock.sh $S status | grep -q "$AGENT" || { echo "lease on $S not held by $AGENT"; docs/tablet-lock.sh $S status; exit 2; }
V=$(sh dumpsys package $P | grep -m1 versionName | tr -d '\r '); echo "build: $V  serial: $S  mode: $MODE  evidence: $E"

# --- route for the fake drive: Valhalla polyline6 resampled to one fix per second at 27 m/s
VAL=$(sed -n 's/^valhallaUrl=//p' local.properties | tr -d '\r')
set -- $START; SLAT=$1; SLNG=$2; set -- $DEST; DLAT=$1; DLNG=$2
curl -s -m 30 --resolve homebackup.tail00ae77.ts.net:8446:100.102.188.107 "$VAL" -H "Content-Type: application/json" \
  -d "{\"locations\":[{\"lat\":$SLAT,\"lon\":$SLNG},{\"lat\":$DLAT,\"lon\":$DLNG}],\"costing\":\"auto\",\"units\":\"miles\"}" > "$E/route.json"
python3 - "$E/route.json" "$E/route.txt" <<'PY'
import json, sys, math
r = json.load(open(sys.argv[1]))
shape = r["trip"]["legs"][0]["shape"]
def decode(s, prec=1e6):
    out=[]; i=0; lat=0; lng=0
    while i < len(s):
        for k in (0,1):
            res=0; sh=0
            while True:
                b=ord(s[i])-63; i+=1; res|=(b&0x1f)<<sh; sh+=5
                if b<0x20: break
            d = ~(res>>1) if res&1 else res>>1
            if k==0: lat+=d
            else: lng+=d
        out.append((lng/prec, lat/prec))
    return out
pts = decode(shape)
def dist(a,b):
    kx=111320*math.cos(math.radians((a[1]+b[1])/2)); return math.hypot((a[0]-b[0])*kx,(a[1]-b[1])*111320)
fixes=[pts[0]]; carry=0.0; SPEED=26.8
for a,b in zip(pts,pts[1:]):
    d=dist(a,b)
    if d==0: continue
    while carry+SPEED<=d:
        carry+=SPEED; t=carry/d; fixes.append((a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t))
    carry-=d
with open(sys.argv[2],"w") as f:
    for lng,lat in fixes: f.write(f"{lng:.6f} {lat:.6f}\n")
print(f"route {r['trip']['summary']['length']:.1f} mi, {len(pts)} shape pts, {len(fixes)} fixes at 1 Hz")
PY

# --- set up: mode flag, hybrid style, park at the start, navigate via the on-device API
case $MODE in baseline*) sh "touch $F/no-prefetch" ;; *) sh "rm -f $F/no-prefetch" ;; esac
case $S in emulator-*) ;; *) case $MODE in *-off) echo "-off modes are emulator only"; exit 2 ;; esac ;; esac
sh am force-stop $P; sleep 1
# Both modes start from an empty tile database (debug build: run-as works), otherwise the previous
# run's tiles sit in the ambient cache and the baseline is meaningless.
sh "run-as $P sh -c 'rm -f files/mbgl-offline.db files/mbgl-offline.db-journal files/mbgl-offline.db-wal'" 2>/dev/null; echo "tile db wiped: $(sh run-as $P ls files/ | grep -c mbgl) left"; adb -s $S emu geo fix $SLNG $SLAT >/dev/null; sh am start -n $P/.MainActivity >/dev/null; sleep 10; tap "Got it"
tap Map; tap "Map style"; sleep 1.5; tap Hybrid; sleep 4
adb -s $S forward tcp:18782 tcp:8782 >/dev/null
api -X POST -d "{\"lat\":$DLAT,\"lng\":$DLNG,\"name\":\"Gainesville\"}" http://127.0.0.1:18782/api/navigate; echo
sleep 8; ~/bin/ui.sh $S dump | grep -c "End Navigation" | xargs echo "navigating:"
case $MODE in
  prefetch-off) for i in $(seq 1 30); do adb -s $S logcat -d -s RoutePrefetch | grep -q "near complete" && break; sleep 2; done; echo "corridor warm after $((i*2)) s: $(adb -s $S logcat -d -s RoutePrefetch | grep -c 'near complete')"; sh svc wifi disable; echo "emulator wifi OFF" ;;
  baseline-off) sleep 4; sh svc wifi disable; echo "emulator wifi OFF" ;;
esac
adb -s $S logcat -c -b crash
# Starlink-ish: 120-250 ms RTT, 20 Mbit down (console: delay min:max ms, speed up:down kbps)
adb -s $S emu network delay 120:250 >/dev/null; adb -s $S emu network speed 5000:20000 >/dev/null
BOUNDS=$(~/bin/ui.sh $S find "Showing a Map" | head -1 | grep -oE "[0-9]+" | tr "\n" " "); echo "map bounds: $BOUNDS"

# --- drive + sample
( while read -r lng lat; do adb -s $S emu geo fix "$lng" "$lat" >/dev/null; sleep 1; done < "$E/route.txt" ) &
DRV=$!
sleep 6   # let the first tiles ahead load before judging; the drive is already moving
# Starlink drops (obstruction, overpass): 15 s of a dead link every 60 s, starting at t=40 s.
OUT=0
for i in $(seq 0 $STEP $((DUR-STEP))); do
  ph=$(( (i - 40 + 600) % 60 ))
  if [ $i -ge 40 ] && [ $ph -lt 15 ] && [ $OUT = 0 ]; then adb -s $S emu network speed 1:1 >/dev/null; OUT=1; echo "t=$i link down"; fi
  if { [ $i -lt 40 ] || [ $ph -ge 15 ]; } && [ $OUT = 1 ]; then adb -s $S emu network speed 5000:20000 >/dev/null; OUT=0; echo "t=$i link up"; fi
  adb -s $S exec-out screencap -p > "$E/t$(printf %03d $i).png"; sleep $((STEP-1))
done
kill $DRV 2>/dev/null; wait $DRV 2>/dev/null
adb -s $S emu network delay none >/dev/null; adb -s $S emu network speed full >/dev/null
case $MODE in *-off) sh svc wifi enable; echo "emulator wifi back ON" ;; esac
adb -s $S logcat -d -s RoutePrefetch > "$E/prefetch-logcat.txt"; adb -s $S logcat -d -b crash | grep -c "Process: $P" > "$E/crashes.txt"
api -X POST http://127.0.0.1:18782/api/stop >/dev/null; tap "End Navigation"; sh "rm -f $F/no-prefetch"

# --- measure: share of map-area pixels within +-6 of the sentinel background #0c2a1e
python3 - "$E" $BOUNDS <<'PY' | tee "$E/grey.txt"
import sys, glob, os
from PIL import Image, ImageFilter, ImageStat
E=sys.argv[1]; x0,y0,x1,y1=map(int, sys.argv[2:6])
rows=[]
for f in sorted(glob.glob(os.path.join(E,"t*.png"))):
    im=Image.open(f).convert("RGB").crop((x0,y0,x1,y1)); px=im.getdata(); n=len(px)
    grey=sum(1 for r,g,b in px if abs(r-12)<=6 and abs(g-42)<=6 and abs(b-30)<=6)
    # edge energy of the imagery: a z12 parent stretched 16x has almost none; real z16 tiles have plenty
    sharp=ImageStat.Stat(im.convert("L").filter(ImageFilter.FIND_EDGES)).var[0]
    rows.append((os.path.basename(f), 100.0*grey/n, sharp))
for f,p,sh in rows: print(f"{f} grey={p:.2f}% sharp={sh:.0f}")
mx=max(p for _,p,_ in rows); mean=sum(p for _,p,_ in rows)/len(rows); shs=[sh for _,_,sh in rows]
print(f"shots={len(rows)} max={mx:.2f}% mean={mean:.2f}% over1pct={sum(1 for _,p,_ in rows if p>=1.0)} sharp min={min(shs):.0f} mean={sum(shs)/len(shs):.0f} last60s={sum(shs[-12:])/12:.0f}")
print("RESULT", "PASS" if mx < 1.0 else "FAIL")
PY
echo "prefetch log: $(grep -c "" "$E/prefetch-logcat.txt") lines; crashes: $(cat "$E/crashes.txt")"; grep -E "complete|region|deleting|ambient" "$E/prefetch-logcat.txt" | tail -8
