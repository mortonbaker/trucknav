#!/bin/bash
# Empty-build/fresh-profile proof; one emulator at a time, preserve original AVD.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
TAG=${1:?unique evidence tag}
[[ "$TAG" =~ ^[a-zA-Z0-9-]+$ ]] || exit 2
E="$HOME/evidence/s20-fresh-$TAG"
test ! -e "$E" || { echo "Evidence exists"; exit 2; }
mkdir -p "$E"
APK="$HOME/evidence/s20-empty-build/app-empty.apk"
test -f "$APK"
S=emulator-5554
status=$(docs/tablet-lock.sh "$S" status || true)
echo "$status" | grep -q "S20" || { echo "S20 lease required; refuse another scope"; exit 2; }
adb -s "$S" get-state | grep -q device
if adb -s "$S" shell dumpsys media_session | grep -q 'state=PLAYING(3)'; then echo "Playback active; abort"; exit 2; fi
PROFILE="s20-fresh-$TAG"
ENVFILE="$HOME/.config/emu/$PROFILE.env"
test ! -e "$ENVFILE"
cp "$HOME/.config/emu/trucknav.env" "$ENVFILE"
sed -i "s/^AVD=.*/AVD=$PROFILE/" "$ENVFILE"
wait_stopped() {
  for i in $(seq 1 30); do adb -s "$S" get-state >/dev/null 2>&1 || return 0; sleep 1; done
  echo "Emulator failed to stop"; return 1
}
kvm_ready() {
  # This host recreates /dev/kvm after the last VM exits; restore only this operator's ACL.
  [ -w /dev/kvm ] || sudo -n setfacl -m u:"$(id -un)":rw /dev/kvm
}
restore() {
  ~/bin/emu.sh stop "$PROFILE"
  wait_stopped || return 1
  kvm_ready && ~/bin/pressure.sh --stop-idle --need 4G && ~/bin/emu.sh start trucknav
}
~/bin/emu.sh stop trucknav
wait_stopped
trap restore EXIT
~/bin/emu.sh create "$PROFILE"
~/bin/pressure.sh --stop-idle --need 4G || exit 2
kvm_ready
~/bin/emu.sh start "$PROFILE"
docs/tablet-lock.sh "$S" acquire claude-vehicle-s20 40 "S20 fresh-profile acceptance" || exit 2
~/bin/emu.sh install "$PROFILE" "$APK"
adb -s "$S" shell am start -n com.morton.trucknav/.MainActivity >/dev/null
sleep 3
adb -s "$S" forward tcp:18782 tcp:8782
export S20_FRESH_EVIDENCE="$E"
python3 docs/emu-settings-fresh.py
# Apply RUNBOOK asset provisioning on this disposable profile.
~/bin/emu.sh assets "$PROFILE" "$HOME/trucknav-assets"
adb -s "$S" shell am force-stop com.morton.trucknav
adb -s "$S" shell am start -n com.morton.trucknav/.MainActivity >/dev/null
sleep 3
SERIAL="$S" docs/emu-settings.sh "fresh-$TAG"
SERIAL="$S" docs/cockpit-smoke.sh "s20-fresh-$TAG" claude-vehicle-s20 | tee "$E/cockpit.txt"
