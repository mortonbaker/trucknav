#!/usr/bin/env bash
# Isolated S6 emulator checks. Physical performance is a separate main-build gate.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH="$PATH:$ANDROID_HOME/platform-tools"
S=emulator-5556
mode=${1:-native}
[[ "$mode" == native || "$mode" == ui ]] || { echo 'usage: s6-smoke.sh native|ui'; exit 2; }
adb -s "$S" get-state >/dev/null
[[ "$(adb -s "$S" emu avd name | head -1 | tr -d '\r')" == trucknav-s6 ]] || { echo 'Wrong AVD'; exit 1; }
[[ "$(adb -s "$S" shell cat /sdcard/.agent-lock | head -1 | tr -d '\r')" == codex-s6 ]] || { echo 'Acquire codex-s6 lease first'; exit 1; }
expires=$(adb -s "$S" shell cat /sdcard/.agent-lock | sed -n '2p' | tr -d '\r')
[[ "$expires" =~ ^[0-9]+$ && "$expires" -gt "$(date +%s)" ]] || { echo 'Lease expired'; exit 1; }
E="$HOME/evidence/s6-$mode-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$E"
adb -s "$S" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$S" shell cmd package set-home-activity com.morton.trucknav/.MainActivity
adb -s "$S" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$S" shell cmd package set-home-activity com.morton.trucknav/.MainActivity
adb -s "$S" logcat -c
method=realNativePackParityRepeatedRoutesAndFailures
restore() { :; }
trap 'restore' EXIT
if [[ "$mode" == ui ]]; then
  method=offlineStartEndAndLateResultContract
  [[ "$(adb -s "$S" shell id -u | tr -d '\r')" == 0 ]] || { echo 'Owned userdebug emulator requires adb root'; exit 1; }
  app_uid=$(adb -s "$S" shell stat -c %u /sdcard/Android/data/com.morton.trucknav/files | tr -d '\r')
  [[ "$app_uid" =~ ^[0-9]+$ ]] || exit 1
  # All external app traffic fails, but loopback assets and adb stay up. Never touch shared services.
  v4="OUTPUT -m owner --uid-owner $app_uid ! -d 127.0.0.0/8 -m comment --comment trucknav-s6 -j REJECT"
  v6="OUTPUT -m owner --uid-owner $app_uid ! -d ::1/128 -m comment --comment trucknav-s6 -j REJECT"
  restore() {
    adb -s "$S" shell "iptables -D $v4" >/dev/null 2>&1 || true
    adb -s "$S" shell "ip6tables -D $v6" >/dev/null 2>&1 || true
  }
  printf '#!/system/bin/sh\nsleep 60\niptables -D %s\nip6tables -D %s\n' "$v4" "$v6" > "$E/restore.sh"
  adb -s "$S" push "$E/restore.sh" /data/local/tmp/s6-restore.sh >/dev/null
  adb -s "$S" shell 'nohup sh /data/local/tmp/s6-restore.sh >/data/local/tmp/s6-restore.log 2>&1 </dev/null &'
  adb -s "$S" shell "iptables -I $v4"
  adb -s "$S" shell "ip6tables -I $v6"
  python3 - <<'PY'
from pathlib import Path
import subprocess
p=dict(line.split('=',1) for line in Path('local.properties').read_text().splitlines() if '=' in line)
subprocess.run(['adb','-s','emulator-5556','emu','geo','fix',p.get('homeLng','-97.1331'),p.get('homeLat','33.2148')],check=True)
PY
fi
timeout 100 adb -s "$S" shell am instrument -w -r -e class "com.morton.trucknav.routing.S6RoutingTest#$method" com.morton.trucknav.test/androidx.test.runner.AndroidJUnitRunner > "$E/instrumentation.txt"
restore
adb -s "$S" logcat -d -s S6Smoke:I NavLog:I AndroidRuntime:E Mbgl:E LocalAssetServer:W > "$E/logcat.txt"
adb -s "$S" logcat -b crash -d > "$E/crash.txt"
adb -s "$S" shell dumpsys meminfo com.morton.trucknav > "$E/meminfo.txt"
if [[ "$mode" == ui ]]; then adb -s "$S" pull /sdcard/Android/data/com.morton.trucknav/files/s6-offline-routing.png "$E/" || true; fi
cat "$E/instrumentation.txt"
echo "Evidence: $E"
grep -q 'OK (1 test)' "$E/instrumentation.txt"
