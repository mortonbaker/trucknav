#!/usr/bin/env bash
# Provision the dedicated S6 emulator. Never deletes the basemap or app data.
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH="$PATH:$ANDROID_HOME/platform-tools"
S=emulator-5556
pack=${1:?usage: s6-install-pack.sh /path/to/valhalla_tiles.tar expected-sha256}
expected=${2:?supply the source SHA-256}
[[ "$expected" =~ ^[a-f0-9]{64}$ ]] || exit 2
[[ "$(adb -s "$S" emu avd name | head -1 | tr -d '\r')" == trucknav-s6 ]] || exit 1
lock=$(adb -s "$S" shell cat /sdcard/.agent-lock | tr -d '\r')
[[ "$(head -1 <<<"$lock")" == codex-s6 ]] || exit 1
expires=$(sed -n '2p' <<<"$lock")
[[ "$expires" =~ ^[0-9]+$ && "$expires" -gt "$(date +%s)" ]] || exit 1
actual=$(sha256sum "$pack" | cut -d' ' -f1)
[[ "$actual" == "$expected" ]] || { echo 'Source checksum mismatch'; exit 1; }
F=/sdcard/Android/data/com.morton.trucknav/files/routing
adb -s "$S" shell am force-stop com.morton.trucknav
adb -s "$S" shell mkdir -p "$F"
adb -s "$S" push "$pack" "$F/valhalla_tiles.tar.part"
actual=$(adb -s "$S" shell sha256sum "$F/valhalla_tiles.tar.part" | cut -d' ' -f1)
[[ "$actual" == "$expected" ]] || { echo 'Device checksum mismatch; old pack preserved'; exit 1; }
adb -s "$S" shell mv "$F/valhalla_tiles.tar.part" "$F/valhalla_tiles.tar"
adb -s "$S" root >/dev/null
timeout 60 adb -s "$S" wait-for-device
uid=$(adb -s "$S" shell stat -c %u /sdcard/Android/data/com.morton.trucknav/files | tr -d '\r')
[[ "$uid" =~ ^[0-9]+$ ]] || exit 1
adb -s "$S" shell chown -R "$uid:ext_data_rw" /data/media/0/Android/data/com.morton.trucknav/files/routing
echo 'Routing pack verified and installed; basemap preserved.'
