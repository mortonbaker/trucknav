#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="$HOME/Android/Sdk"
saved=$(mktemp)
chmod 600 "$saved"
cp local.properties "$saved"
restore() { cp "$saved" local.properties; rm -f "$saved"; }
trap restore EXIT
: > local.properties
~/bin/pressure.sh --stop-idle --need 4G || exit 2
flock ~/.gradle/build.lock ./gradlew -q assembleDebug --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m --no-daemon
mkdir -p "$HOME/evidence/s20-empty-build"
cp app/build/outputs/apk/debug/app-debug.apk "$HOME/evidence/s20-empty-build/app-empty.apk"
python3 - <<'CHECK'
from pathlib import Path
source=Path("app/build/generated/source/buildConfig/debug/com/morton/trucknav/BuildConfig.java").read_text()
assert 'apiToken = "";' in source
assert 'valhallaUrl = "";' in source
assert 'home'+'Lat' not in source
print("EMPTY local.properties: build passed, token/router blank, no baked-in home")
CHECK
