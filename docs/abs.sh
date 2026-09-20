#!/bin/bash
# Audiobookshelf helper for the harness. Credentials come from local.properties, never from argv.
#   abs.sh token                      -> bearer
#   abs.sh get <path>                 -> GET $ABS<path>
#   abs.sh progress <itemId>          -> server currentTime (s, rounded)
#   abs.sh small [maxTracks]          -> items with <= N tracks: id|tracks|duration|title
cd "$(dirname "$0")/.." || exit 1
ABS=$(sed -n 's/^absUrl=//p' local.properties | tr -d '\r' | sed 's:/*$::')
U=$(sed -n 's/^absUser=//p' local.properties | tr -d '\r')
P=$(sed -n 's/^absPass=//p' local.properties | tr -d '\r')
HOSTN=$(echo "$ABS" | sed -E 's#https?://##; s#[:/].*##')
CURL="curl -s -m 20 --resolve $HOSTN:443:100.102.188.107"
token() { $CURL -X POST "$ABS/login" -H "Content-Type: application/json" -d "{\"username\":\"$U\",\"password\":\"$P\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['user']['token'])"; }
case "$1" in
  token) token ;;
  get) $CURL -H "Authorization: Bearer $(token)" "$ABS$2" ;;
  progress) $CURL -H "Authorization: Bearer $(token)" "$ABS/api/me/progress/$2" | python3 -c "
import json,sys
try: d=json.load(sys.stdin); print(round(d.get('currentTime',0)))
except Exception: print(0)" ;;
  small)
    T=$(token); N=${2:-2}
    for lib in $($CURL -H "Authorization: Bearer $T" "$ABS/api/libraries" | python3 -c "import json,sys;print(' '.join(l['id'] for l in json.load(sys.stdin)['libraries']))"); do
      $CURL -H "Authorization: Bearer $T" "$ABS/api/libraries/$lib/items?limit=500" | python3 -c "
import json,sys
for r in json.load(sys.stdin)['results']:
    m=r['media']; n=m.get('numTracks') or m.get('numAudioFiles') or 0
    if 0<n<=$N: print(f\"{r['id']}|{n}|{int(m.get('duration',0))}|{m['metadata'].get('title')}\")"
    done ;;
esac
