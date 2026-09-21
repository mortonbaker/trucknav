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
require "atlas01 only" test "$(hostname -s)" = atlas01
require "expected version supplied" test -n "${EXPECTED_CODE:-}"
require "device available" adb -s "$S" get-state
docs/tablet-lock.sh "$S" acquire "$AGENT" 90 "S23 control geometry" || exit 2
ROTATION=$(sh_ settings get system user_rotation | tr -d '\r')
AUTO=$(sh_ settings get system accelerometer_rotation | tr -d '\r')
FG=$(fg)
cleanup() {
  sh_ settings put system user_rotation "$ROTATION" >/dev/null
  sh_ settings put system accelerometer_rotation "$AUTO" >/dev/null
  [ -n "$FG" ] && sh_ am start -n "$FG" >/dev/null 2>&1
  docs/tablet-lock.sh "$S" release "$AGENT"
}
trap cleanup EXIT
contract
code=$(sh_ dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
require "installed code=$EXPECTED_CODE (actual=$code)" test "$code" = "$EXPECTED_CODE"
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
maps=[n for n in nodes if label(n)=='Showing a Map']
assert maps,'map bounds missing'
m=bounds(maps[0]); print('map',m,'density',density)
names={'Map style','Add stop','Center on my location','Zoom in','Zoom out','Overview','Route overview','Mute','Unmute','Mute voice instructions','Unmute voice instructions'}
controls={label(n):bounds(touch(n)) for n in nodes if label(n) in names}
assert controls,'no map controls'
expected={'Map style','Center on my location','Zoom in','Zoom out'}
if state in ('navigating','add-stop'): expected.add('Add stop')
errors_a=[]; errors_b=[]; errors_c=[]
if not expected.issubset(controls): errors_a.append('missing '+str(expected-set(controls)))
obstacles=[]
for n in nodes:
    s=label(n)
    if s in {'S23 turn card','S23 progress bar','S23 search area','S23 destination sheet','S23 tiles','Search field'} or s.startswith(('Result:','Go:','Open:')):
        obstacles.append((s,bounds(n)))
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
adb -s "$S" emu geo fix -97.204973 33.080088 >/dev/null
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
  SERIAL="$S" bash docs/s2-camera.sh "$TAG-s2" > "$EVID/s2-camera.txt" 2>&1
  rc=$?
  row d "S2 camera criteria unchanged" "exit=$rc" "$([ "$rc" = 0 ] && echo PASS || echo FAIL)" s2-camera.txt
fi
crash_gate
finish
