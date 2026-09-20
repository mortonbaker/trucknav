#!/bin/bash
# Lease on the tablet itself, visible to every agent on every host that can adb to it.
#   tablet-lock.sh <serial> acquire <agent> <minutes> "<purpose>"  -> exit 2 if held by someone else
#   tablet-lock.sh <serial> renew   <agent> <minutes>
#   tablet-lock.sh <serial> release <agent>
#   tablet-lock.sh <serial> status
# Hold it for anything that changes the tablet: install, UI driving, rotation, Wi-Fi cuts,
# force-stop, playback. Read-only screenshots / dumpsys only need a status check.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=$1; cmd=$2; agent=$3; mins=$4; why=$5
L=/sdcard/.agent-lock
now=$(date +%s)
read_lock() { adb -s "$S" shell "cat $L 2>/dev/null" | tr -d '\r'; }
case "$cmd" in
  acquire|renew)
    { [ -z "$agent" ] || [ -z "$mins" ]; } && { echo "usage: tablet-lock.sh <serial> $cmd <agent> <minutes> [purpose]"; exit 1; }
    cur=$(read_lock)
    if [ -n "$cur" ]; then
      owner=$(echo "$cur" | sed -n 1p); exp=$(echo "$cur" | sed -n 2p)
      if [ "$owner" != "$agent" ] && [ "$exp" -gt "$now" ] 2>/dev/null; then
        echo "HELD by $owner until $(date -d @$exp '+%T') - $(echo "$cur" | sed -n 3p)"; exit 2
      fi
    fi
    [ "$cmd" = renew ] && [ -z "$why" ] && why=$(echo "$cur" | sed -n 3p)
    adb -s "$S" shell "printf '%s\n%s\n%s\n%s\n' '$agent' '$((now+mins*60))' '$why' '$(hostname)' > $L"
    echo "tablet: $agent until $(date -d @$((now+mins*60)) '+%T') - $why" ;;
  release)
    cur=$(read_lock); [ -z "$cur" ] && { echo "not held"; exit 0; }
    owner=$(echo "$cur" | sed -n 1p)
    [ "$owner" = "$agent" ] || { echo "held by $owner, not $agent"; exit 2; }
    adb -s "$S" shell "rm -f $L"; echo "released" ;;
  status|*)
    cur=$(read_lock)
    if [ -z "$cur" ]; then echo "tablet: free"; exit 0; fi
    owner=$(echo "$cur" | sed -n 1p); exp=$(echo "$cur" | sed -n 2p)
    if [ "$exp" -gt "$now" ] 2>/dev/null; then echo "tablet: $owner until $(date -d @$exp '+%T') ($(( (exp-now)/60 )) min left) - $(echo "$cur" | sed -n 3p)"; exit 3
    else echo "tablet: free (expired lease from $owner)"; fi ;;
esac
