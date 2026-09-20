#!/bin/bash
# S16 / B6 / B8 end-to-end on the truck, from atlas01. Needs the truck powered: the Venus Pi on the
# tailnet and the relay board on the same Wi-Fi as the Pi. Nothing here touches the tablet.
#   docs/s16-pi-relay.sh <tag>
# Rows: Pi reachable; relay.sh state cold (<5 s) / warm (<1 s); Node-RED /starlink/toggle twice is
# idempotent; board Wi-Fi/uptime sensors (needs the 2026-09-20 firmware); MQTT SOC topics (B8);
# tailscale health on the Pi (hotspot path). Evidence under ~/evidence/s16-<tag>/.
cd "$(dirname "$0")/.." || exit 1
TAG=${1:?tag}; E=~/evidence/s16-$TAG; mkdir -p "$E"
PI=100.112.123.30; PORTAL=$(sed -n 's/^venusPortalId=//p' local.properties | tr -d '\r'); PORTAL=${PORTAL:-2ccf67855c49}
pi() { timeout 40 ssh -o BatchMode=yes -o ConnectTimeout=8 root@$PI "$@" 2>&1; }
declare -a ROWS; row() { ROWS+=("| $1 | $2 | $3 | $4 |"); echo "[$4] $1: $3"; }

if ! pi true >/dev/null; then row "Pi reachable" "ssh root@$PI over the tailnet" "no (truck off, or Pi Wi-Fi down: see B9 watchdog)" "BLOCKED"; printf '%s\n' "${ROWS[@]}"; exit 2; fi
pi "date; uptime; cat /data/starlink/relay.ip 2>/dev/null; iw dev wlan0 link | grep -E 'SSID|signal'" > "$E/pi-state.txt"; row "Pi reachable" "ssh over the tailnet" "$(grep -m1 SSID "$E/pi-state.txt" | tr -d '\t')" "PASS"

# --- relay.sh cold / warm --------------------------------------------------------------
pi "rm -f /data/starlink/relay.ip"; t0=$(date +%s.%N); cold=$(pi "/data/starlink/relay.sh state"); t1=$(date +%s.%N)
warm=$(pi "/data/starlink/relay.sh state"); t2=$(date +%s.%N)
ssh_rt=$( { time -p pi true; } 2>&1 | awk '/real/{print $2}')          # subtract the ssh round trip itself
cold_s=$(echo "$t1 - $t0 - $ssh_rt" | bc); warm_s=$(echo "$t2 - $t1 - $ssh_rt" | bc)
echo "cold: $cold" > "$E/relay-state.txt"; echo "warm: $warm" >> "$E/relay-state.txt"
ok=$(echo "$cold" | grep -c '"state"'); [ "$ok" -gt 0 ] && [ "$(echo "$cold_s < 5" | bc)" = 1 ] && a=PASS || a=FAIL
row "relay.sh cold" "JSON state in < 5 s with discovery" "${cold_s}s: $(echo "$cold" | head -c 80)" "$a"
[ "$(echo "$warm" | grep -c '"state"')" -gt 0 ] && [ "$(echo "$warm_s < 1" | bc)" = 1 ] && a=PASS || a=FAIL
row "relay.sh warm" "JSON state in < 1 s from the cached IP" "${warm_s}s" "$a"

# --- Node-RED flow: toggle twice = unchanged ------------------------------------------
s0=$(pi "curl -s -m 8 http://127.0.0.1:1880/starlink/state"); pi "curl -s -m 8 http://127.0.0.1:1880/starlink/toggle" >/dev/null; sleep 3
s1=$(pi "curl -s -m 8 http://127.0.0.1:1880/starlink/state"); pi "curl -s -m 8 http://127.0.0.1:1880/starlink/toggle" >/dev/null; sleep 3
s2=$(pi "curl -s -m 8 http://127.0.0.1:1880/starlink/state")
printf 'before: %s\nafter 1: %s\nafter 2: %s\n' "$s0" "$s1" "$s2" > "$E/nodered.txt"
st() { echo "$1" | grep -oE '"state":"[A-Z]+"' | head -1; }
[ -n "$(st "$s0")" ] && [ "$(st "$s0")" != "$(st "$s1")" ] && [ "$(st "$s0")" = "$(st "$s2")" ] && a=PASS || a=FAIL
row "Node-RED /starlink/toggle x2" "state flips then returns" "$(st "$s0") -> $(st "$s1") -> $(st "$s2")" "$a"

# --- board sensors (new firmware) ------------------------------------------------------
IP=$(pi "cat /data/starlink/relay.ip 2>/dev/null" | tr -d '\r')
if [ -n "$IP" ]; then
  pi "curl -s -m 5 http://$IP/text_sensor/connected_ssid; echo; curl -s -m 5 http://$IP/sensor/wifi_signal; echo; curl -s -m 5 http://$IP/sensor/uptime" > "$E/board-sensors.txt"
  grep -q '"value"' "$E/board-sensors.txt" && a=PASS || a="NOT RUN (old firmware: flash ~/esphome-builds/4runner.yaml)"
  row "board sensors" "SSID / RSSI / uptime readable at /text_sensor,/sensor" "$(tr '\n' ' ' < "$E/board-sensors.txt" | head -c 120)" "$a"
fi

# --- B8: what SOC topics exist ---------------------------------------------------------
pi "mosquitto_pub -t R/$PORTAL/keepalive -n; mosquitto_sub -v -t 'N/$PORTAL/system/0/Dc/Battery/Soc' -t 'N/$PORTAL/battery/+/Soc' -t 'N/$PORTAL/system/0/AutoSelectedBatteryService' -W 8 2>&1" > "$E/soc-topics.txt"
cat "$E/soc-topics.txt"
if grep -q 'Soc {"value":[0-9]' "$E/soc-topics.txt"; then a=PASS; m="$(grep -m1 Soc "$E/soc-topics.txt")"
elif grep -q 'AutoSelectedBatteryService' "$E/soc-topics.txt"; then a="NOT A BUG"; m="no battery service on dbus: $(grep -m1 AutoSelected "$E/soc-topics.txt" | cut -d' ' -f2-) - wire the SmartShunt VE.Direct cable"
else a=FAIL; m="no answer from the broker"; fi
row "B8 SOC topic" "a Soc topic carries a number" "$m" "$a"

# --- hotspot path: tailscale health on the Pi ----------------------------------------
pi "/data/tailscale/tailscale status --self --peers=false 2>&1 | head -n 3; /data/tailscale/tailscale netcheck 2>&1 | grep -E 'UDP|Nearest DERP|IPv4'" > "$E/tailscale.txt"
grep -qE "Nearest DERP: [a-z]" "$E/tailscale.txt" && a=PASS || a=FAIL
row "Pi tailscale" "DERP reachable (the only path on an isolating hotspot)" "$(grep -E 'Nearest DERP|UDP' "$E/tailscale.txt" | tr '\n' ' ')" "$a"

echo; echo "## S16 truck results - $(date '+%F %T'), tag $TAG"; echo "| Item | Criterion | Measured | Result |"; echo "|---|---|---|---|"
printf '%s\n' "${ROWS[@]}" | tee "$E/results.md"
