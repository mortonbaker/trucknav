#!/bin/bash
# S3 style switcher smoke: five styles, timing per switch, persistence, mid-route switch.
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=100.95.16.47:5555; P=com.morton.trucknav; T=${1:-run}
shot() { adb -s $S exec-out screencap -p > ~/s3-$T-$1.png; }
pick() { ~/bin/ui.sh $S tapx "Map style" >/dev/null; sleep 1.5; ~/bin/ui.sh $S tapx "$1" >/dev/null; }
pref() { python3 ~/trucknav/docs/mapstat.py ~/s3-$T-$(ls -t ~/s3-$T-*.png 2>/dev/null | head -1 | xargs -n1 basename 2>/dev/null | sed "s/s3-$T-//") 2>/dev/null; }
adb -s $S logcat -c; adb -s $S logcat -c -b crash
echo "== sheet =="; ~/bin/ui.sh $S tapx "Map style" >/dev/null; sleep 1.5; shot sheet
echo "tiles: $(~/bin/ui.sh $S dump | grep -cE 'content-desc="(Light|Dark|Satellite|Hybrid|Terrain)"')"
adb -s $S shell input keyevent KEYCODE_BACK; sleep 1
for st in Satellite Hybrid Terrain Dark Light; do
  adb -s $S logcat -c; pick $st; sleep 3; shot $(echo $st | tr A-Z a-z)
  echo "$st: $(python3 ~/trucknav/docs/mapstat.py ~/s3-$T-$(echo $st | tr A-Z a-z).png)"
done
echo "== persistence =="; pick Hybrid; sleep 2; adb -s $S shell am force-stop $P; sleep 2; adb -s $S shell input keyevent KEYCODE_HOME; sleep 10; shot restart-hybrid; echo "after restart: $(python3 ~/trucknav/docs/mapstat.py ~/s3-$T-restart-hybrid.png)"
echo "== mid-route switch =="; pick Light; sleep 2
~/bin/ui.sh $S tap "Where to?" >/dev/null; sleep 1; adb -s $S shell input text "Denton%sTX"; sleep 6; ~/bin/ui.sh $S tap "Denton, Texas" >/dev/null; sleep 3; ~/bin/ui.sh $S tap "Start navigation" >/dev/null; sleep 8
shot route-light; pick Hybrid; sleep 5; shot route-hybrid
echo "turn card present: $(~/bin/ui.sh $S dump | grep -c 'Oak Knoll Road')  end-nav present: $(~/bin/ui.sh $S dump | grep -c 'End Navigation')"
~/bin/ui.sh $S tap "End Navigation" >/dev/null; sleep 1; pick Light
echo "crashes: $(adb -s $S logcat -d -b crash | grep -c "Process: $P")"
