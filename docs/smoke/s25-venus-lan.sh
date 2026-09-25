#!/bin/bash
# S25 (Venus side) — a broker that refuses us (Home Assistant's mosquitto) must not be
# remembered as the Pi. venusHost points at a dead address so the app falls to its /24
# sweep, which finds a mosquitto on 10.0.2.2: first one that demands a password, then an
# anonymous one standing in for the Pi.
#   docs/smoke/s25-venus-lan.sh <tag> [agent]        (SERIAL=emulator-5554 default)
SLICE=s25-venus-lan PKG=com.morton.trucknav
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
cd "$(dirname "$0")/../.." || exit 2
contract
[ "$VERSION" = "${WANT:-0.40.0}" ] || { echo "!! expected ${WANT:-0.40.0}, device has $VERSION"; exit 2; }
M=s25-mosq-$TAG
app_restart 8
TOK=$(grep '^apiToken=' local.properties | cut -d= -f2-); adb -s $S forward tcp:18782 tcp:8782 >/dev/null
api() { curl -s -m 6 -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" "$@"; }
ORIG=$(api http://127.0.0.1:18782/api/settings | python3 -c "import json,sys; d=json.load(sys.stdin); print(json.dumps({k:d.get(k) for k in ('venusHost','venusPortalId')}))")
require "read original Venus settings" test -n "$ORIG"; echo "$ORIG" > "$EVID/orig-settings.json"
broker() { docker rm -f $M >/dev/null 2>&1; printf "listener 1883 0.0.0.0\nallow_anonymous $1\n" > "$EVID/mosq-$1.conf"
  docker run -d --rm --name $M -p 127.0.0.1:1883:1883 -v "$EVID/mosq-$1.conf:/mosquitto/config/mosquitto.conf:ro" eclipse-mosquitto:2 >/dev/null; sleep 2; }
cleanup() { docker rm -f $M >/dev/null 2>&1; api -X PUT http://127.0.0.1:18782/api/settings -d "$ORIG" >/dev/null; }
trap cleanup EXIT
lanpref() { adb -s $S shell run-as $PKG cat shared_prefs/venus.xml 2>/dev/null | grep -oE 'name="lan">[^<]*' | sed 's/.*>//'; }
adb -s $S shell run-as $PKG rm -f shared_prefs/venus.xml

broker false
api -X PUT http://127.0.0.1:18782/api/settings -d '{"venusHost":"10.0.2.99","venusPortalId":"fake"}' >/dev/null
app_restart 8
# V1 the app finds the refusing broker, fails to connect, and does not remember it
w=$(poll 60 sh -c "adb -s $S logcat -d -s VenusClient:* | grep -q '10.0.2.2: Not authorized'"); sleep 3
p1=$(lanpref); adb -s $S logcat -d -s VenusClient:* > "$EVID/v1-logcat.txt"
row V1 "refusing broker probed (Not authorized) and NOT cached as the Pi" "refused after ${w}s, cached lan='${p1}'" "$([ "$w" != none ] && [ -z "$p1" ] && echo PASS || echo FAIL)" v1-logcat.txt
# V2 it keeps sweeping (retries the same host again, not stuck on a cache)
n=$(poll 40 sh -c "[ \$(adb -s $S logcat -d -s VenusClient:* | grep -c 'scanning 10.0.2.0/24') -ge 2 ]")
row V2 "keeps sweeping after the refusal (≥ 2 sweeps logged)" "2nd sweep by ${n}s" "$([ "$n" != none ] && echo PASS || echo FAIL)" -

# V3 an anonymous broker (the Pi) on the same address → connected and remembered
docker exec $M true 2>/dev/null; broker true
docker exec $M mosquitto_pub -r -t "N/fake/system/0/Dc/Battery/Soc" -m '{"value": 77}'
w=$(poll 60 sh -c "adb -s $S logcat -d -s VenusClient:* | grep -q 'connected to 10.0.2.2 via lan'"); sleep 3
p3=$(lanpref); s3=$(poll 20 seen "77%"); f=$(shot V3.png)
row V3 "accepting broker → connected via lan, cached, strip shows SOC 77%" "connect ${w}s, cached='${p3}', 77% after ${s3}s" "$([ "$w" != none ] && [ "$p3" = 10.0.2.2 ] && [ "$s3" != none ] && echo PASS || echo FAIL)" $f

crash_gate
cleanup; trap - EXIT
adb -s $S shell run-as $PKG rm -f shared_prefs/venus.xml
echo "-- venus settings restored: $(api http://127.0.0.1:18782/api/settings | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('venusHost'), d.get('venusPortalId'))")"
restore
finish
