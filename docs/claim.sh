#!/bin/bash
# Source-tree claim for ~/trucknav. One writer at a time.
#   claim.sh take <agent> "<scope>"   -> exit 2 if someone else holds it
#   claim.sh release <agent>
#   claim.sh status
# A claim older than 6 h is stale and may be taken over (say so in your scope).
cd "$(dirname "$0")/.." || exit 1
D=.claims; mkdir -p $D; F=$D/tree
now=$(date +%s)
case "$1" in
  take)
    [ -z "$2" ] && { echo "usage: claim.sh take <agent> \"<scope>\""; exit 1; }
    if [ -f $F ]; then
      owner=$(sed -n 1p $F); ts=$(sed -n 2p $F)
      if [ "$owner" != "$2" ] && [ $((now-ts)) -lt 21600 ]; then
        echo "HELD by $owner since $(date -d @$ts '+%F %T') - $(sed -n 3p $F)"; exit 2
      fi
    fi
    printf '%s\n%s\n%s\n%s\n' "$2" "$now" "$3" "$(hostname)" > $F
    echo "claimed by $2: $3" ;;
  release)
    [ -f $F ] || { echo "not held"; exit 0; }
    [ "$(sed -n 1p $F)" = "$2" ] || { echo "held by $(sed -n 1p $F), not $2"; exit 2; }
    rm -f $F; echo "released" ;;
  status|*)
    if [ -f $F ]; then owner=$(sed -n 1p $F); ts=$(sed -n 2p $F); echo "tree: $owner since $(date -d @$ts '+%F %T') ($(( (now-ts)/60 )) min) - $(sed -n 3p $F)"; else echo "tree: free"; fi ;;
esac
