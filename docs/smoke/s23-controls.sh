#!/usr/bin/env bash
# S23 a-d contract BEFORE app code. Emulator only, atlas01, never tablet.
# EXPECTED_CODE=<code> docs/smoke/s23-controls.sh <tag> astra-3
# pressure.sh --stop-idle --need 4G before every gradlew/emulator; build behind flock.
# a: rotations 0/1, idle + navigating: right-edge stack, 16dp inset, screenshot + bounds.
# b: idle/results/sheet/nav/add-stop: zero intersections with information rectangles;
#    leave 120dp bottom-left reserve. One row per state and orientation.
# c: all map actions >=56dp, every gap >=12dp, measured from dump.
# d: S2 browse center +/-5%, navigating lower third, panels open/closed, both rotations.
# e: zero crashes with successful collection; restore rotation and foreground on exit.
SLICE=s23-controls PKG=com.morton.trucknav SERIAL=emulator-5554
export SERIAL
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
require "atlas01 only" test "$(hostname -s)" = atlas01
require "expected version supplied" test -n "${EXPECTED_CODE:-}"
require "device available" adb -s "$S" get-state
docs/tablet-lock.sh "$S" acquire "$AGENT" 90 "S23 control geometry" || exit 2
ROTATION=$(sh_ settings get system user_rotation | tr -d '\r')
AUTO=$(sh_ settings get system accelerometer_rotation | tr -d '\r')
PORT=
FG=$(sh_ dumpsys activity activities | sed -n 's/.*topResumedActivity=.* u0 \([^ ]*\/[^ ]*\).*/\1/p' | head -1)
cleanup() {
  sh_ settings put system user_rotation "$ROTATION" >/dev/null
  sh_ settings put system accelerometer_rotation "$AUTO" >/dev/null
  [ -n "$FG" ] && sh_ am start -n "$FG" >/dev/null 2>&1
  [ -n "$PORT" ] && adb -s "$S" forward --remove "tcp:$PORT"
  docs/tablet-lock.sh "$S" release "$AGENT"
}
trap cleanup EXIT
contract
code=$(sh_ dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
require "installed code=$EXPECTED_CODE (actual=$code)" test "$code" = "$EXPECTED_CODE"
VERSION="$VERSION / code $code"
printf "%s\n" "$VERSION" > "$EVID/version.txt"
sh_ settings put system accelerometer_rotation 0
sh_ wm density > "$EVID/density.txt"
UI=$HOME/bin/ui.sh
tap() { "$UI" "$S" tapx "$1" >/dev/null; }
capture() {
  local state=$1 orient=$2 stem="$2-$1"
  dump > "$EVID/$stem.xml"
  shot "$stem.png" >/dev/null
  python3 - "$EVID" "$stem" "$state" <<'PY' > "$EVID/$stem-geometry.txt"
import sys,re,xml.etree.ElementTree as ET
from pathlib import Path
root,stem,state=Path(sys.argv[1]),sys.argv[2],sys.argv[3]
density=int(re.findall(r'density:\s*(\d+)',(root/'density.txt').read_text())[-1])/160
tree=ET.parse(root/(stem+'.xml')); nodes=list(tree.iter('node'))
parent={c:p for p in tree.iter() for c in p}
def bounds(n): return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def label(n): return n.get('content-desc','') or n.get('text','')
def touch(n):
    while n.get('clickable')!='true' and n in parent: n=parent[n]
    return n
def intersects(a,b): return a[0]<b[2] and b[0]<a[2] and a[1]<b[3] and b[1]<a[3]
maps=[n for n in nodes if label(n).startswith('Showing a Map')]
assert maps,'map bounds missing'
m=bounds(maps[0]); print('map',m,'density',density)
names={'Map style','Add stop','Center on my location','Zoom in','Zoom out','Overview','Route Overview','Mute','Unmute','Mute voice instructions','Unmute voice instructions'}
controls={label(n):bounds(touch(n)) for n in nodes if label(n) in names}
assert controls,'no map controls'
expected={'Map style','Center on my location','Zoom in','Zoom out'}
if state in ('navigating','add-stop'):
    expected.update({'Add stop','Route Overview'})
    if not ({'Mute','Unmute'} & controls.keys()): expected.add('Mute')
errors_a=[]; errors_b=[]; errors_c=[]
if not expected.issubset(controls): errors_a.append('missing '+str(expected-set(controls)))
if len(controls)!=(7 if state in ('navigating','add-stop') else 4):
    errors_a.append(('control count',len(controls)))
if controls:
    top=min(b[1] for b in controls.values()); bottom=max(b[3] for b in controls.values())
    if abs((top-m[1])/density-16)>1.1: errors_a.append(('top corner',(top-m[1])/density))
    if abs((m[3]-bottom)/density-16)>1.1: errors_a.append(('bottom corner',(m[3]-bottom)/density))
obstacles=[]
for n in nodes:
    s=label(n)
    if s in {'Turn instructions','Trip progress','S23 search area','S23 destination sheet','S23 tiles','Search field','Set Home','Set Work','Open favorites','Open recents'} or s.startswith(('Result:','Go:','Open:')):
        obstacles.append((s,bounds(touch(n)) if n.get('clickable')=='true' else bounds(n)))
# Derive actual container rectangles, not only text/icon rectangles.
def ancestor_rect(labels):
    selected=[n for n in nodes if labels(label(n))]
    if not selected:return None
    chains=[]
    for n in selected:
        chain=[n]
        while n in parent:n=parent[n];chain.append(n)
        chains.append(chain)
    common=next((n for n in chains[0] if all(n in chain for chain in chains[1:])),None)
    return bounds(common) if common is not None and common.tag=='node' else None
if state=='sheet':
    rect=ancestor_rect(lambda s:s in ('Start navigation','Close') or s.startswith('Route '))
    assert rect is not None and rect!=m,'destination-sheet rectangle unavailable'
    obstacles.append(('destination sheet',rect))
if state=='results':
    rect=ancestor_rect(lambda s:s.startswith('Result:'))
    assert rect is not None,'results-card rectangle unavailable'
    obstacles.append(('results card',rect))
if state in ('navigating','add-stop'):
    assert any(s=='Turn instructions' for s,_ in obstacles),'turn-card rectangle unavailable'
    assert any(s=='Trip progress' for s,_ in obstacles),'progress-bar rectangle unavailable'
if state=='idle':
    assert any(s in ('Go: Home','Set Home') for s,_ in obstacles),'favorites tiles unavailable'
print('information rectangles',obstacles)
proof={
'idle':lambda: any(label(n)=='Search field' for n in nodes),
'results':lambda: any(label(n).startswith('Result:') for n in nodes),
'sheet':lambda: any(label(n) in ('Start navigation','S23 destination sheet') for n in nodes),
'navigating':lambda: any(label(n)=='End Navigation' for n in nodes),
'add-stop':lambda: any(label(n)=='Search field' for n in nodes) and 'Add stop' in controls}
assert proof[state](),'state not reached: '+state
for name,b in controls.items():
    print(name,b,'dp',tuple(round(v/density,2) for v in b))
    inset=(m[2]-b[2])/density
    if abs(inset-16)>1.1 or b[1]<m[1] or b[3]>m[3]: errors_a.append((name,'right inset',inset))
    w,h=(b[2]-b[0])/density,(b[3]-b[1])/density
    if min(w,h)<55.5: errors_c.append((name,'size',w,h))
    for other,rect in obstacles:
        if intersects(b,rect): errors_b.append((name,other,b,rect))
    reserve=(m[0],m[3]-round(120*density),(m[0]+m[2])//2,m[3])
    if intersects(b,reserve): errors_b.append((name,'bottom-left reserve'))
items=list(controls.items())
for i,(name,a) in enumerate(items):
    for other,b in items[i+1:]:
        dx=max(a[0]-b[2],b[0]-a[2],0); dy=max(a[1]-b[3],b[1]-a[3],0)
        gap=(dx*dx+dy*dy)**0.5/density
        if gap<11.5: errors_c.append((name,other,'gap',gap))
for k,errors in [('a',errors_a),('b',errors_b),('c',errors_c)]:
    print(k,'FAIL' if errors else 'PASS',str(errors) if errors else f'{len(controls)} controls; {len(obstacles)} information rectangles')
PY
  local rc=$?
  for id in a b c; do
    local verdict=FAIL measured="geometry/state observation failed"
    if [ "$rc" = 0 ]; then
      verdict=$(awk -v k="$id" '$1==k {print $2}' "$EVID/$stem-geometry.txt")
      measured=$(awk -v k="$id" '$1==k {$1=$2=""; print}' "$EVID/$stem-geometry.txt")
    fi
    row "$id-$stem" "$id: placement/non-overlap/size-gap contract" "$measured" "$verdict" "$stem-geometry.txt; $stem.xml; $stem.png"
  done
}
app_restart 4
TOK=$(sed -n 's/^apiToken=//p' local.properties)
PORT=$(adb -s "$S" forward tcp:0 tcp:8782 | tr -d '\r')
gps_ready=0
for attempt in $(seq 1 45); do
  adb -s "$S" emu geo fix -97.204973 33.080088 >/dev/null
  curl -fsS -m 8 -H "Authorization: Bearer $TOK" "http://127.0.0.1:$PORT/api/state" > "$EVID/gps-state.json"
  if python3 -c 'import json,sys; s=json.load(open(sys.argv[1]));sys.exit(0 if abs(s.get("lat",0)-33.080088)<.001 and abs(s.get("lng",0)+97.204973)<.001 else 1)' "$EVID/gps-state.json"; then gps_ready=1; break; fi
  sleep 1
done
require "GPS gate accepted the fixture position" test "$gps_ready" = 1
for orient in 1 0; do
  sh_ settings put system user_rotation "$orient"
  app_restart 6
  tap "End Navigation" || true
  tap "Map" || true
  waitfor "Search field" 15 >/dev/null
  capture idle "$orient"
  tap "Search field"
  sh_ input text "Whole%sFoods"
  waitfor "Result: Whole Foods Market, Justin Road, 4041, Highland Village, Texas" 20 >/dev/null
  sh_ input keyevent KEYCODE_BACK
  capture results "$orient"
  tap "Result: Whole Foods Market, Justin Road, 4041, Highland Village, Texas"
  waitfor "Start navigation" 20 >/dev/null
  capture sheet "$orient"
  tap "Start navigation"
  waitfor "End Navigation" 20 >/dev/null
  capture navigating "$orient"
  tap "Add stop"
  waitfor "Search field" 10 >/dev/null
  sh_ input keyevent KEYCODE_BACK
  capture add-stop "$orient"
  tap "Add stop" || true
  tap "End Navigation" || true
done
if grep -q 'S=100.95.16.47:5555' docs/s2-camera.sh; then
  blocked d "S2 camera criteria unchanged" "existing harness hardcodes tablet; emulator-safe port required"
else
  PARENT_LEASE=1 SERIAL="$S" PYTHON="${PYTHON:-python3}" bash docs/s2-camera.sh "$TAG-s2" "$AGENT" > "$EVID/s2-camera.txt" 2>&1
  rc=$?
  row d "S2 camera criteria unchanged" "exit=$rc" "$([ "$rc" = 0 ] && echo PASS || echo FAIL)" s2-camera.txt
fi
sh_ settings put system user_rotation "$ROTATION" >/dev/null
sh_ settings put system accelerometer_rotation "$AUTO" >/dev/null
final_code=$(sh_ dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
row build "expected build remained installed" "$EXPECTED_CODE -> $final_code" "$([ "$EXPECTED_CODE" = "$final_code" ] && echo PASS || echo FAIL)" version.txt
actual_rotation=$(sh_ settings get system user_rotation | tr -d '\r')
actual_auto=$(sh_ settings get system accelerometer_rotation | tr -d '\r')
row restore "rotation and auto-rotate restored" "$ROTATION/$AUTO -> $actual_rotation/$actual_auto" "$([ "$ROTATION/$AUTO" = "$actual_rotation/$actual_auto" ] && echo PASS || echo FAIL)" -
crash_gate
finish
