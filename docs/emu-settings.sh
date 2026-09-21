#!/bin/bash
# S20 a-d acceptance on the assigned emulator. Never clears app data or changes Wi-Fi.
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$PATH:$HOME/Android/Sdk/platform-tools"
export SERIAL=${SERIAL:-emulator-5554}
case "$SERIAL" in emulator-*) ;; *) echo "Emulator only"; exit 2;; esac
docs/tablet-lock.sh "$SERIAL" status | grep -q claude-vehicle || { echo "Acquire emulator lease first"; exit 2; }
export S20_EVIDENCE="$HOME/evidence/s20-${1:?unique tag}"
test ! -e "$S20_EVIDENCE" || { echo "Evidence tag already exists"; exit 2; }
mkdir -p "$S20_EVIDENCE"
python3 docs/emu-settings.py
