#!/bin/bash
# S20 a-d acceptance on the assigned emulator. Never clears app data or changes Wi-Fi.
# For the empty-build/fresh-profile part of a/e: docs/emu-settings-fresh.sh <tag> calls this after provisioning.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export SERIAL=${SERIAL:-emulator-5554}
case "$SERIAL" in emulator-*) ;; *) echo "Emulator only"; exit 2;; esac
lease=$(docs/tablet-lock.sh "$SERIAL" status || true)
printf "%s" "$lease" | grep -q "claude-vehicle-s20.*S20" || { echo "Acquire the S20 emulator lease first"; exit 2; }
if adb -s "$SERIAL" shell dumpsys media_session | grep -q 'state=PLAYING(3)'; then echo "Playback active; abort"; exit 2; fi
TAG=${1:?unique tag}
[[ "$TAG" =~ ^[a-zA-Z0-9-]+$ ]] || exit 2
export S20_EVIDENCE="$HOME/evidence/s20-$TAG"
test ! -e "$S20_EVIDENCE" || { echo "Evidence tag already exists"; exit 2; }
mkdir -p "$S20_EVIDENCE"
python3 docs/emu-settings.py
# The same six tests used for the measured S20 receipt: storage, image, position,
# token entropy, Units/Voice controls, QR decoding in memory, and About.
flock "$HOME/.gradle/build.lock" bash -c '~/bin/pressure.sh --stop-idle --need 4G || exit 2; ./gradlew -q :app:assembleDebugAndroidTest --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m'
adb -s "$SERIAL" install --no-incremental -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -r -e class com.morton.trucknav.settings.S20SettingsTest com.morton.trucknav.test/androidx.test.runner.AndroidJUnitRunner > "$S20_EVIDENCE/instrumentation.txt"
cat "$S20_EVIDENCE/instrumentation.txt"
grep -q 'OK (6 tests)' "$S20_EVIDENCE/instrumentation.txt"
adb -s "$SERIAL" shell am start -n com.morton.trucknav/.MainActivity >/dev/null
