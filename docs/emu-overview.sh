#!/bin/bash
# Route-overview check on the emulator: search, pick A, start, tap Route Overview, measure the route line
# against the padded viewport. usage: emu-overview.sh <tag>
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
E=emulator-5554; P=com.morton.trucknav; T=${1:-run}
adb -s $E shell am force-stop $P; adb -s $E emu geo fix -97.204973 33.080088 >/dev/null; adb -s $E logcat -c
adb -s $E shell am start -n $P/.MainActivity >/dev/null; sleep 10
~/bin/ui.sh $E tapx "Got it" >/dev/null 2>&1; ~/bin/ui.sh $E tapx Map >/dev/null; sleep 1
~/bin/ui.sh $E tapx "Search field" >/dev/null; sleep 1; adb -s $E shell input text "Whole%sFoods"; sleep 9
adb -s $E shell input keyevent KEYCODE_BACK; sleep 2
~/bin/ui.sh $E tapx "Result: Whole Foods Market, Justin Road, 4041, Highland Village, Texas" >/dev/null; sleep 4
~/bin/ui.sh $E tapx "Start navigation" >/dev/null; sleep 8
~/bin/ui.sh $E tapx "Route Overview" >/dev/null; sleep 4
adb -s $E exec-out screencap -p > ~/emu-overview-$T.png
python3 - <<EOF
from PIL import Image
im=Image.open("/home/morton/emu-overview-$T.png").convert("RGB"); px=im.load(); xs=[];ys=[]
for y in range(37,725):
    for x in range(117,1340):
        r,g,b=px[x,y]
        if abs(r-53)<25 and abs(g-131)<25 and abs(b-221)<25: xs.append(x); ys.append(y)
if not xs: print("route: none"); raise SystemExit
x0,x1,y0,y1=min(xs),max(xs),min(ys),max(ys)
# landscape padded viewport: start = map/2+16dp -> px 117 + (1223/2 + 21) ; top 24dp=32px; end 32dp=42px; bottom 32dp=42px
L=117+int(1223/2+21); R=1340-42; Tp=37+32; B=725-42
inside = L<=x0 and x1<=R and Tp<=y0 and y1<=B
print(f"route bbox x={x0}..{x1} y={y0}..{y1}; padded viewport x={L}..{R} y={Tp}..{B}; whole route inside: {inside}; fill={(x1-x0)/(R-L):.0%} x {(y1-y0)/(B-Tp):.0%}")
EOF
adb -s $E logcat -d -s NavLog:* | grep -E "overview" | tail -1 | cut -c1-140
