#!/bin/sh
# Venus Pi Wi-Fi watchdog, v2 (2026-09-25).
# Why: 2026-09-25 10:27 ConnMan roamed Everything -> EveryLink, declared Everything
# "stuck in failed state" and restarted itself; the restarted ConnMan did not see
# wlan0 for 4.5 min and v1 (60 s checks, bounce at 5 min) sat it out. Only an
# interface bounce makes ConnMan re-add wlan0.
# Every 15 s: if wlan0 has no IPv4, or ConnMan has no wifi services at all (lost
# the interface), rescan + connect favourites; bounce wlan0 after 30 s down, then
# every 2 min while still down. Also writes a status line on every change (and a
# heartbeat every 10 min) to /data/log/netstat.log so the next drop can be read back.
# Runs from /data/rc.local. Event log: /data/starlink/wifi-watchdog.log
LOG=/data/starlink/wifi-watchdog.log; NET=/data/log/netstat.log; mkdir -p /data/starlink /data/log
TS="/data/tailscale/bin/tailscale --socket=/run/tailscale/tailscaled.sock"
down=0; last=""; beat=0
PID=/run/wifi-watchdog.pid
[ -f $PID ] && kill $(cat $PID) 2>/dev/null; echo $$ > $PID
trim() { [ $(wc -c < $1) -gt $2 ] && tail -c $(($2/2)) $1 > $1.tmp && mv $1.tmp $1; }
log() { echo "$(date '+%F %T') $*" >> $LOG; trim $LOG 200000; }
bounce() { log "bouncing wlan0 ($1)"; ip link set wlan0 down; sleep 3; ip link set wlan0 up; connmanctl enable wifi >/dev/null 2>&1; iw dev wlan0 set power_save off 2>/dev/null; }
log "watchdog v2 started (pid $$)"
while true; do
  ip4=$(ip -4 addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+' | cut -d' ' -f2)
  nsvc=$(connmanctl services 2>/dev/null | grep -c 'wifi_')
  if [ -n "$ip4" ] && [ "$nsvc" -gt 0 ]; then
    [ $down -gt 0 ] && log "wlan0 back after $((down*15))s: $ip4 on $(connmanctl services 2>/dev/null | grep '^\*' | head -n1 | cut -c5-28)"
    down=0
  else
    down=$((down+1))
    [ "$nsvc" -eq 0 ] && why="ConnMan sees no wifi services (lost wlan0)" || why="no IPv4"
    log "down x$down: $why"
    if [ $down -eq 2 ] || { [ $down -gt 2 ] && [ $(( (down-2) % 8 )) -eq 0 ]; }; then bounce "$why"
    else
      connmanctl scan wifi >/dev/null 2>&1
      # saved networks only ('*' = favourite). v1 asked ConnMan to join every visible
      # network (neighbours included), and each attempt knocked the good one off (15:43:22).
      for svc in $(connmanctl services 2>/dev/null | grep '^\*' | awk '{print $NF}' | grep '^wifi_'); do connmanctl connect $svc >/dev/null 2>&1 && log "connect $svc issued"; done
    fi
  fi
  # status line: ssid ip gateway-ping tailnet
  ssid=$(iw dev wlan0 link 2>/dev/null | sed -n 's/^\s*SSID: //p'); sig=$(iw dev wlan0 link 2>/dev/null | sed -n 's/^\s*signal: \(-[0-9]*\).*/\1/p')
  gw=$(ip -4 route show default 2>/dev/null | awk '{print $3; exit}')
  gwok=no; [ -n "$gw" ] && ping -c1 -W2 $gw >/dev/null 2>&1 && gwok=yes
  tsok=no; $TS status >/dev/null 2>&1 && tsok=yes
  now="ssid=${ssid:-none} ip=${ip4:-none} gw=${gw:-none} gw_ping=$gwok tailnet=$tsok wifi_svcs=$nsvc"
  beat=$((beat+1))
  if [ "$now" != "$last" ] || [ $beat -ge 40 ]; then echo "$(date '+%F %T') $now sig=${sig:-?}" >> $NET; trim $NET 500000; last="$now"; beat=0; fi
  sleep 15
done
