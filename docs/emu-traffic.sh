#!/usr/bin/env bash
# S22 evidence, build01 emulator ONLY. No key values on command lines/logs.
# docs/emu-traffic.sh fixtures <tag>
# docs/emu-traffic.sh offline <tag>
# docs/emu-traffic.sh soak <tag> <polyline.txt>   # 30-minute live drive, requires BYOK
# Does NOT declare S22 DONE: live pixel/card/Settings hooks also require acceptance.
set -euo pipefail
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
cd "$(dirname "$0")/.."
mode=${1:?fixtures|offline|soak}; tag=${2:?unique tag}
[[ "$tag" =~ ^[a-zA-Z0-9_-]+$ ]] || exit 2
S=emulator-5554; P=com.morton.trucknav; agent=astra
[[ $(hostname -s) == build01 ]] || { echo 'This harness is assigned to build01 only'; exit 2; }
[[ $(adb -s "$S" emu avd name | tr -d '\r' | head -1) == trucknav-tab ]] || exit 2
lease=$(docs/tablet-lock.sh "$S" status 2>&1 || true)
[[ "$lease" == *'astra until'* ]] || { echo 'Astra lease required'; exit 2; }
E="$HOME/evidence/traffic-$tag"
mkdir "$E"  # unique; refuse to overwrite prior evidence
printf '| Criterion | Measured | Result |\n|---|---|---|\n' > "$E/results.md"
row() { printf '| %s | %s | %s |\n' "$1" "$2" "$3" | tee -a "$E/results.md"; }
adb -s "$S" logcat -d -b crash > "$E/crash-before.txt"
adb -s "$S" logcat -c -b crash
adb -s "$S" logcat -d -s NavLog > "$E/nav-before.txt"
adb -s "$S" shell dumpsys package "$P" | grep -m1 versionName > "$E/version.txt"
restore() {
  if [[ ${cut:-0} == 1 ]]; then adb -s "$S" shell svc wifi enable >/dev/null || true; fi
  if [[ ${restore_data:-0} == 1 ]]; then adb -s "$S" shell svc data enable >/dev/null || true; fi
}
trap restore EXIT INT TERM

case "$mode" in
fixtures)
  export ANDROID_HOME="$HOME/Android/Sdk"
  ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest > "$E/build.txt" 2>&1
  adb -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk > "$E/install.txt"
  adb -s "$S" shell cmd package set-home-activity "$P/.MainActivity" >> "$E/install.txt"
  uid=$(adb -s "$S" shell stat -c %u "/data/user/0/$P" | tr -d '\r')
  [[ "$uid" =~ ^[0-9]+$ ]] || exit 2
  adb -s "$S" shell chown -R "$uid:$uid" "/data/media/0/Android/data/$P/files"
  adb -s "$S" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$E/install.txt"
  adb -s "$S" shell am instrument -w -r -e class com.morton.trucknav.traffic.TrafficProviderTest "$P.test/androidx.test.runner.AndroidJUnitRunner" > "$E/instrumentation.txt"
  grep -q 'OK (12 tests)' "$E/instrumentation.txt" || { row 'c/d fixtures' 'instrumentation failed' FAIL; exit 1; }
  row 'c/d contracts (fixtures)' '12/12; providers, 3s deadline, HTTP403, freshness, geometry, persistent budget, incidents, deferred boot' PASS
  # Print paths/counts only: never print a matching secret value.
  python3 - "$E" <<'PY'
from pathlib import Path
import re, sys
bad=[]
for p in Path('app/src').rglob('*'):
    if not p.is_file(): continue
    try: s=p.read_text()
    except (UnicodeError,OSError): continue
    # The requested broad grep also matches identifiers; distinguish literal credentials.
    if re.search(r'AIza[0-9A-Za-z_-]{20,}',s) or re.search(r'(?i)(?:tomtomKey|googleMapsKey)\s*[=:]\s*["\x27][A-Za-z0-9_-]{16,}',s): bad.append(str(p))
lp=Path('local.properties')
if lp.exists() and re.search(r'(?im)^\s*(?:tomtomKey|googleMapsKey)\s*=',lp.read_text()): bad.append('local.properties')
Path(sys.argv[1],'secret-audit.txt').write_text('literal credential matches='+str(len(bad))+'\n'+'\n'.join(bad))
sys.exit(bool(bad))
PY
  row 'e source/local.properties' '0 literal credentials; keys read from Settings' PASS
  row 'a/b/c/d live UI' 'Requires real BYOK and nav/Settings integration; fixture success is not live acceptance' BLOCKED
  ;;
offline)
  # A restore is scheduled on the device BEFORE cutting; trap is a second guard.
  adb -s "$S" shell 'command -v nohup; command -v svc' > "$E/restore-tools.txt"
  [[ $(wc -l < "$E/restore-tools.txt") -ge 2 ]] || exit 2
  cut=1
  # AVDs also have a validated LTE network. Wi-Fi-off alone falls back to LTE.
  mobile=$(adb -s "$S" shell settings get global mobile_data | tr -d '\r')
  if [[ "$mobile" == 1 ]]; then
    restore_data=1
    adb -s "$S" shell 'nohup sh -c "sleep 20; svc wifi enable; svc data enable" >/dev/null 2>&1 </dev/null &'
  else
    adb -s "$S" shell 'nohup sh -c "sleep 20; svc wifi enable" >/dev/null 2>&1 </dev/null &'
  fi
  adb -s "$S" shell svc data disable
  sleep 1
  adb -s "$S" shell svc wifi disable
  sleep 3
  adb -s "$S" logcat -d -s NavLog > "$E/offline-start.txt"
  sleep 8
  adb -s "$S" logcat -d -s NavLog > "$E/offline-end.txt"
  adb -s "$S" exec-out screencap -p > "$E/offline.png"
  python3 - "$E" <<'PY'
from pathlib import Path
import sys
e=Path(sys.argv[1]); a=e.joinpath('offline-start.txt').read_text(); b=e.joinpath('offline-end.txt').read_text()
n=b.count('traffic-request kind=')-a.count('traffic-request kind=')
state=[x for x in a.splitlines() if 'traffic provider=' in x]
ok=bool(state) and 'online=false overlay=false' in state[-1] and n==0
e.joinpath('results.md').open('a').write(f'| b offline gate | new requests={n}; offline/hidden={ok} | {"PASS" if ok else "FAIL"} |\n')
sys.exit(not ok)
PY
  restore_started=$(date +%s%3N)
  adb -s "$S" shell svc wifi enable
  cut=0
  restored=0
  for n in $(seq 1 10); do
    adb -s "$S" logcat -d -s NavLog > "$E/restored.txt"
    if grep 'traffic provider=' "$E/restored.txt" | tail -1 | grep -q 'online=true'; then
      elapsed=$(( $(date +%s%3N)-restore_started ))
      [[ "$elapsed" -le 10000 ]] || { row 'b network restore' "${elapsed}ms" FAIL; exit 1; }
      row 'b network restore' "${elapsed}ms; live pixels still require valid TomTom key" PARTIAL; restored=1; break
    fi
    sleep 1
  done
  [[ "$restored" == 1 ]] || { row 'b network restore' '>10s' FAIL; exit 1; }
  ;;
soak)
  polyline=${3:?polyline file}; [[ -s "$polyline" ]] || exit 2
  # No fabricated route: caller supplies the tested I-35W geometry, at 3s/fix.
  python3 - "$polyline" <<'PY'
import sys
rows=[list(map(float,l.split())) for l in open(sys.argv[1]) if l.strip()]
assert len(rows)>=600, 'Need at least 600 fixes (30 minutes)'
assert all(len(p)==2 and -98<p[0]<-96 and 32<p[1]<34 for p in rows), 'Expected I-35W area lng lat'
PY
  adb -s "$S" logcat -d -s NavLog > "$E/soak-start.txt"
  grep 'traffic provider=' "$E/soak-start.txt" | tail -1 | grep -q 'provider=tomtom online=true overlay=true' || { row a 'TomTom live overlay/key not active' BLOCKED; exit 2; }
  head -600 "$polyline" > "$E/drive.txt"
  start=$(date +%s)
  ~/bin/emu.sh drive trucknav "$E/drive.txt" 3 > "$E/drive.log"
  elapsed=$(( $(date +%s)-start ))
  adb -s "$S" logcat -d -s NavLog > "$E/soak-end.txt"
  adb -s "$S" exec-out screencap -p > "$E/soak-end.png"
  python3 - "$E" "$elapsed" <<'PY'
from pathlib import Path
import re,sys
e=Path(sys.argv[1]); a=e.joinpath('soak-start.txt').read_text(); b=e.joinpath('soak-end.txt').read_text()
def count(s):
    ns=re.findall(r'traffic tiles=(\d+)',s); return int(ns[-1]) if ns else 0
n=count(b)-count(a); elapsed=int(sys.argv[2]); ok=0<n<=2000 and elapsed>=1800
e.joinpath('results.md').open('a').write(f'| a 30-minute tile budget | requests={n}; seconds={elapsed} | {"PASS" if ok else "FAIL"} |\n')
sys.exit(not ok)
PY
  ;;
*) exit 2 ;;
esac
adb -s "$S" logcat -d -b crash > "$E/crash-after.txt"
crashes=$(grep -c "Process: $P" "$E/crash-after.txt" || true)
[[ "$crashes" == 0 ]] || { row 'crash gate' "$crashes" FAIL; exit 1; }
row 'crash gate' 0 PASS
echo "Evidence: $E; append reviewed measurements to docs/SMOKE-TEST.md. S22 is not DONE until all live criteria pass."
