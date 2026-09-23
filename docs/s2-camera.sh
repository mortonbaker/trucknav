#!/usr/bin/env bash
# S2 criteria unchanged: browsing +/-5% of map center; navigating lower third.
# The previous harness hardcoded the tablet and detected the retired blue dot.
# Now measures the rendered truck image, both orientations, panels open/closed.
SLICE=s2-camera PKG=com.morton.trucknav
SERIAL=${SERIAL:-emulator-5554}; export SERIAL
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
crash_gate() {
  local collected=1 c status=FAIL
  adb -s "$S" logcat -d -b crash > "$EVID/crash.txt" || collected=0
  adb -s "$S" logcat -d > "$EVID/post-logcat.txt" || collected=0
  c=$(grep -c "Process: $PKG" "$EVID/crash.txt" || true)
  [ "$collected" = 1 ] && [ "$c" = 0 ] && [ -s "$EVID/post-logcat.txt" ] && status=PASS
  row crash "zero package crashes; both log collections succeed" "count=$c collected=$collected" "$status" crash.txt
}
PYTHON=${PYTHON:-python3}
require "image measurement dependencies" "$PYTHON" -c 'import cv2,numpy,PIL'
require "device available" adb -s "$S" get-state
if [ "${PARENT_LEASE:-0}" != 1 ]; then
  docs/tablet-lock.sh "$S" acquire "$AGENT" 30 "S2 camera regression" || exit 2
fi
require "lease belongs to $AGENT" sh -c "adb -s '$S' shell cat /sdcard/.agent-lock | head -1 | tr -d '\r' | grep -Fx '$AGENT'"
ROTATION=$(sh_ settings get system user_rotation | tr -d '\r')
AUTO=$(sh_ settings get system accelerometer_rotation | tr -d '\r')
VERSION=$(appver)
FG=$(sh_ dumpsys activity activities | sed -n 's/.*topResumedActivity=.* u0 \([^ ]*\/[^ ]*\).*/\1/p' | head -1)
UI=$HOME/bin/ui.sh
TOK=$(sed -n 's/^apiToken=//p' local.properties)
PORT=$(adb -s "$S" forward tcp:0 tcp:8782 | tr -d '\r')
api() { local endpoint=$1; shift; curl -fsS -m 12 -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' "http://127.0.0.1:$PORT/api/$endpoint" "$@"; }
cleanup() {
  api stop -X POST >/dev/null
  "$UI" "$S" tapx Map >/dev/null 2>&1
  sh_ settings put system user_rotation "$ROTATION" >/dev/null
  sh_ settings put system accelerometer_rotation "$AUTO" >/dev/null
  [ -n "$FG" ] && sh_ am start -n "$FG" >/dev/null 2>&1
  adb -s "$S" forward --remove "tcp:$PORT"
  [ "${PARENT_LEASE:-0}" = 1 ] || docs/tablet-lock.sh "$S" release "$AGENT"
}
trap cleanup EXIT
sh_ settings put system accelerometer_rotation 0
sh_ wm density > "$EVID/density.txt"
api stop -X POST >/dev/null
adb -s "$S" emu geo fix -97.204973 33.080088 >/dev/null
sh_ am start -n "$ACT" >/dev/null
sleep 4
gps_ready=0
for attempt in $(seq 1 45); do
  adb -s "$S" emu geo fix -97.204973 33.080088 >/dev/null
  api state > "$EVID/gps-state.json"
  if python3 -c 'import json,sys; s=json.load(open(sys.argv[1]));sys.exit(0 if abs(s.get("lat",0)-33.080088)<.001 and abs(s.get("lng",0)+97.204973)<.001 else 1)' "$EVID/gps-state.json"; then gps_ready=1; break; fi
  sleep 1
done
require "GPS gate accepted the fixture position" test "$gps_ready" = 1
measure() {
  local state=$1
  dump > "$EVID/$state.xml"; shot "$state.png" >/dev/null
  "$PYTHON" - "$EVID" "$state" app/src/main/res/drawable-nodpi/vehicle_top.png <<'PY' > "$EVID/$state-measure.txt"
import cv2,numpy as np,sys,re,json,xml.etree.ElementTree as ET
from pathlib import Path
root,state,asset=Path(sys.argv[1]),sys.argv[2],sys.argv[3]
nodes=list(ET.parse(root/(state+'.xml')).iter('node'))
maps=[n for n in nodes if n.get('content-desc','').startswith('Showing a Map')]
assert maps,'no visible map'
navigating=any(n.get('content-desc')=='End Navigation' for n in nodes)
assert navigating==state.startswith('nav'),'navigation state does not match camera case'
x0,y0,x1,y1=map(int,re.findall(r'\d+',maps[0].get('bounds')))
(root/(state+'-bounds.json')).write_text(json.dumps([x0,y0,x1,y1]))
if state.endswith('-Music'):
    prior=json.loads((root/(state.replace('-Music','-Map')+'-bounds.json')).read_text())
    assert (x1-x0)*(y1-y0)<.9*(prior[2]-prior[0])*(prior[3]-prior[1]),'Music pane did not open'
density=int(re.findall(r'density:\s*(\d+)',(root/'density.txt').read_text())[-1])/160
frame=cv2.imread(str(root/(state+'.png'))); roi=frame[y0:y1,x0:x1]
source=cv2.imread(asset,cv2.IMREAD_UNCHANGED)
best=(float('inf'),None)
# Search the entire map, never the expected position. Pitch compresses the map-aligned image.
for width in range(round(84*density*.75),round(84*density*1.15)+1,4):
    for squash in np.arange(.45,1.06,.05):
        height=round(width*squash)
        template=cv2.resize(source,(width,height),interpolation=cv2.INTER_AREA)
        mask=(template[:,:,3]>220).astype(np.uint8)*255
        if np.count_nonzero(mask)<100 or height>=roi.shape[0] or width>=roi.shape[1]:continue
        scores=cv2.matchTemplate(roi,template[:,:,:3],cv2.TM_SQDIFF_NORMED,mask=mask)
        scores=np.nan_to_num(scores,nan=100,posinf=100,neginf=100)
        value,_,xy,_=cv2.minMaxLoc(scores)
        if value<best[0]:best=(value,(xy[0]+width/2+x0,xy[1]+height/2+y0,width,height))
score,puck=best
assert puck is not None and score<.24,f'puck not confidently matched: score={score},candidate={puck}'
cx,cy,w,h=puck; fx,fy=(cx-x0)/(x1-x0),(cy-y0)/(y1-y0)
ok=abs(fx-.5)<=.05 and abs(fy-.5)<=.05 if state.startswith('browse') else 0<=fx<=1 and 2/3<=fy<=1
cv2.rectangle(frame,(round(cx-w/2),round(cy-h/2)),(round(cx+w/2),round(cy+h/2)),(0,255,0),2)
cv2.imwrite(str(root/(state+'-detected.png')),frame)
print(f'puck=({cx:.1f},{cy:.1f}) map=({x0},{y0},{x1},{y1}) fraction=({fx:.4f},{fy:.4f}) image_match_error={score:.4f} '+('PASS' if ok else 'FAIL'))
sys.exit(0 if ok else 1)
PY
  local rc=$? measured
  measured=$(cat "$EVID/$state-measure.txt")
  row "$state" "browse center +/-5%; nav lower third" "$measured (exit=$rc)" "$([ "$rc" = 0 ] && echo PASS || echo FAIL)" "$state.png; $state.xml; $state-detected.png"
}
for mode in browse nav; do
  if [ "$mode" = nav ]; then
    api navigate -X POST -d '{"lat":33.072812,"lng":-97.085042,"name":"S2 Whole Foods"}' > "$EVID/navigate.json"
    waitfor "End Navigation" 30 >/dev/null
  fi
  for rot in 1 0; do
    sh_ settings put system user_rotation "$rot"; sleep 4
    for pane in Map Music; do
      "$UI" "$S" tapx "$pane" >/dev/null
      "$UI" "$S" tapx "Center on my location" >/dev/null
      sleep 3
      measure "$mode-$rot-$pane"
    done
  done
done
crash_gate
finish
