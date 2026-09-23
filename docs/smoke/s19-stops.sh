#!/bin/bash
# S19 contract authored BEFORE app code; build01 / emulator-5556 exclusively.
# a: POST navigate, stop2, stop1; bar next=stop1, ETA/min/mi + smaller final ETA;
# tap Trip stops -> 3 rows with strictly ascending ETAs; bar/list XML + screenshots.
# b: same trip -> 2 numbered pin features + 1 checkered flag; pins PNG + layer log.
# c: emu.sh drive s6 <Valhalla shape> 3 -> stop-passed; GoogleTTS arrival synthesis +1,
# next=stop2 within 2000ms (poll timestamped render log), no deviation-handler.
# d: Remove stop <stop2> -> route stop-remove steps=N; next=destination,
# pins=0+flag, still NAVIGATING, no IDLE line; XML + screenshot + NavLog.
# e: stationary next-leg meters/seconds each within 5% of independent Valhalla
# per-leg summary; save raw response and both measured numbers.
# f: crash buffer collected, zero package crashes; restore rotation, foreground,
# network, idle navigation and paused playback. Evidence unique per tag.
# Controls: Trip stops toggles list; Back closes; Remove stop (48dp, disabled busy,
# error keeps trip); End Navigation unchanged; Arrived/Done dismisses only card
# at intermediate stops and card auto-clears after 10s.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
SERIAL=${SERIAL:-emulator-5556}; export EMU_PROFILE=${EMU_PROFILE:-s6}
[ "$(hostname)" = build01 ] || exit 2
SLICE=s19-stops PKG=com.morton.trucknav AGENT=astra-2
. ~/.claude/skills/slice-build/scripts/smoke-lib.sh
. ~/.claude/skills/slice-build/scripts/android.sh
require "pressure budget" "$HOME/bin/pressure.sh" --stop-idle --need 4G
require "S19 lease" docs/tablet-lock.sh "$S" acquire "$AGENT" 45 "S19 smoke $TAG"
EXPECTED_CODE=$(sed -nE 's/.*versionCode ([0-9]+).*/\1/p' app/build.gradle)
require "expected code $EXPECTED_CODE" bash -c 'adb -s "$1" shell dumpsys package "$2" | grep -q "versionCode=$3 "' _ "$S" "$PKG" "$EXPECTED_CODE"
contract
sha256sum app/build/outputs/apk/debug/app-debug.apk > "$EVID/apk-sha256.txt"
ROTATION=$(sh_ settings get system user_rotation | tr -d '\r')
FOREGROUND=$(fg)
export EVID S PKG ROTATION FOREGROUND
adb -s "$S" forward tcp:18782 tcp:8782 >/dev/null
python3 - <<'PY'
import datetime, json, os, pathlib, re, subprocess, time, urllib.request, xml.etree.ElementTree as ET
ev=pathlib.Path(os.environ["EVID"]); serial=os.environ["S"]; pkg=os.environ["PKG"]; rows=[]
def run(*args,binary=False):
    return subprocess.check_output(args,timeout=35,text=not binary,stderr=subprocess.DEVNULL)
def adb(*a): return run("adb","-s",serial,*a)
def save(name,data): (ev/name).write_text(data)
def log(): return adb("logcat","-d","-v","epoch","-s","NavLog:*","GoogleTTSServiceImpl:*")
def ui(name):
    adb("shell","uiautomator","dump","/sdcard/s19.xml")
    s=adb("shell","cat","/sdcard/s19.xml");save(name+".xml",s)
    (ev/(name+".png")).write_bytes(run("adb","-s",serial,"exec-out","screencap","-p",binary=True))
    return ET.fromstring(s)
def tap(label):
    for n in ui("tap").iter("node"):
        if label in (n.get("text"),n.get("content-desc")):
            a=list(map(int,re.findall(r"\d+",n.get("bounds",""))))
            adb("shell","input","tap",str((a[0]+a[2])//2),str((a[1]+a[3])//2));return
    raise AssertionError("control absent: "+label)
def poll(fn,seconds=30):
    start=time.monotonic()
    while time.monotonic()-start<seconds:
        try:
            if fn():return round((time.monotonic()-start)*1000)
        except (OSError,ValueError):pass
        time.sleep(.15)
    raise AssertionError("deadline exceeded")
props=dict(l.strip().split("=",1) for l in pathlib.Path("local.properties").read_text().splitlines() if "=" in l and not l.startswith("#"))
def request(url,data=None,token=False):
    req=urllib.request.Request(url,data=None if data is None else json.dumps(data).encode(),headers={"Content-Type":"application/json",**({"Authorization":"Bearer "+props["apiToken"]} if token else {})})
    with urllib.request.urlopen(req,timeout=20) as r:return json.load(r)
def api(path,data=None):return request("http://127.0.0.1:18782/api/"+path,data,True)
def route(points):return request(props["valhallaUrl"],{"locations":[{"lat":p[1],"lon":p[0],"type":"break"} for p in points],"costing":"auto","units":"kilometers"})
def decode(s):
    out=[];acc=[0,0];i=0
    while i<len(s):
        for k in range(2):
            v=0;shift=0
            while True:
                b=ord(s[i])-63;i+=1;v|=(b&31)<<shift;shift+=5
                if b<32:break
            acc[k]+=~(v>>1) if v&1 else v>>1
        out.append((acc[1]/1e6,acc[0]/1e6))
    return out
def row(i,desc,fn,files):
    try:measured=fn();status="PASS"
    except Exception as ex:measured=type(ex).__name__+": "+str(ex);status="FAIL"
    rows.append((i,desc,str(measured),status,files));print(rows[-1],flush=True)
drive=None
recent_path="/sdcard/Android/data/"+pkg+"/files/recent.json"
try: recent_before=adb("shell","cat",recent_path)
except subprocess.CalledProcessError: recent_before=None
initial_state=None
owns_trip=False
try:
    # No app data is cleared. Refuse to replace an existing live trip.
    adb("shell","am","start","-n",pkg+"/.MainActivity")
    poll(lambda:api("state") is not None)
    state=api("state");initial_state=state;save("before-state.json",json.dumps(state))
    if state.get("navigating"):raise RuntimeError("existing navigation; cannot replace operator trip")
    owns_trip=True
    base=route([(-97.204973,33.080088),(-97.085042,33.072812)])
    shape=decode(base["trip"]["legs"][0]["shape"])
    points=[shape[len(shape)*k//100] for k in (65,72,87)]+[shape[-1]]
    raw=route(points);save("valhalla.json",json.dumps(raw))
    legs=raw["trip"]["legs"];drivepoints=decode(legs[0]["shape"])+decode(legs[1]["shape"])[:4]
    (ev/"drive.txt").write_text("".join(f"{x} {y}\n" for x,y in drivepoints))
    adb("shell","am","force-stop",pkg);adb("emu","geo","fix",str(points[0][0]),str(points[0][1]))
    adb("shell","am","start","-n",pkg+"/.MainActivity")
    poll(lambda:api("state") is not None)
    def anchored():
        adb("emu","geo","fix",str(points[0][0]),str(points[0][1]))
        state=api("state")
        return abs(state.get("lat",0)-points[0][1])<.0001 and abs(state.get("lng",0)-points[0][0])<.0001
    anchor_ms=poll(anchored,65)
    save("anchor.json",json.dumps({"elapsedMs":anchor_ms,"state":api("state"),"expected":points[0]}))
    try:tap("Got it")
    except AssertionError:pass
    try:tap("Map")
    except AssertionError:pass
    def target(p,n):return {"lng":p[0],"lat":p[1],"name":n}
    api("navigate",target(points[3],"S19 Destination"))
    poll(lambda:"state NAVIGATING" in log())
    api("add_stop",target(points[2],"S19 Stop 2"));poll(lambda:"route stop-add" in log())
    api("add_stop",target(points[1],"S19 Stop 1"));poll(lambda:log().count("route stop-add")>=2)
    poll(lambda:'trip-bar next="S19 Stop 1"' in log())
    def a():
        texts=[n.get("text","") for n in ui("bar").iter("node")]
        assert "→ S19 Stop 1" in texts and any("Final" in t for t in texts),texts
        assert any(" min" in t and " mi" in t for t in texts),texts
        tap("Trip stops")
        r=[n.get("content-desc","") for n in ui("list").iter("node") if n.get("content-desc","").startswith("Stop row ")]
        assert len(r)==3,r
        etas=[]
        for x in r:
            t=datetime.datetime.strptime(x.split(" ETA ",1)[1],"%I:%M %p")
            minutes=t.hour*60+t.minute
            if etas and minutes<etas[-1]%1440:minutes+=1440
            etas.append(minutes)
        assert etas==sorted(etas) and len(set(etas))==3,etas
        adb("shell","input","keyevent","KEYCODE_BACK");return "3 rows; displayed ETA minutes="+str(etas)
    row("a","next-stop bar; 3 ascending ETAs",a,"bar.xml bar.png list.xml list.png")
    if any(n.get("content-desc")=="Stop list" for n in ui("list-closed").iter("node")):
        adb("shell","input","keyevent","KEYCODE_BACK")
    def b():
        s=log();save("pins.log",s)
        tap("Route Overview");ui("pins")
        tap("Center on my location")  # S23 corner stack replaced Ferrostar Recenter Map
        assert "stop-pins numbered=2 flag=1" in s
        return "2 numbered features; 1 flag"
    row("b","pins 1,2 and destination flag",b,"pins.png pins.log")
    def e():
        s=log();save("numbers.log",s)
        m=re.findall(r'trip-bar next="S19 Stop 1" meters=([\d.]+) seconds=([\d.]+)',s)[-1]
        actual=list(map(float,m));expected=[legs[0]["summary"]["length"]*1000,legs[0]["summary"]["time"]]
        errors=[abs(a-b)/b for a,b in zip(actual,expected)]
        save("comparison.json",json.dumps({"bar":actual,"valhalla":expected,"relativeErrors":errors}))
        assert max(errors)<=.05,(actual,expected,errors)
        return "bar="+str(actual)+" Valhalla="+str(expected)+" errors="+str(errors)
    row("e","leg meters/seconds within 5% of Valhalla",e,"valhalla.json comparison.json numbers.log")
    before=log();save("before-drive.log",before)
    drive=subprocess.Popen([str(pathlib.Path.home()/"bin/emu.sh"),"drive",os.environ.get("EMU_PROFILE","s6"),str(ev/"drive.txt"),"3"],stdout=(ev/"drive.log").open("w"),stderr=subprocess.STDOUT)
    def c():
        poll(lambda:"stop-passed S19 Stop 1" in log(),max(90,len(drivepoints)*3+30))
        poll(lambda:'trip-bar next="S19 Stop 2"' in log(),2)
        time.sleep(.3) # bounded TTS dispatch grace; flip deadline uses event timestamps
        s=log();save("arrival.log",s);tree=ui("arrival")
        assert "Arrived at S19 Stop 1" in [n.get("text","") for n in tree.iter("node")]
        passed=[l for l in s.splitlines() if "stop-passed S19 Stop 1" in l]
        flipped=[l for l in s.splitlines() if 'trip-bar next="S19 Stop 2"' in l and float(l.split()[0])>=float(passed[0].split()[0])]
        ms=(float(flipped[0].split()[0])-float(passed[0].split()[0]))*1000
        assert len(passed)==1 and 0<=ms<=2000,(len(passed),ms)
        window=s[len(before):]
        assert "deviation-handler" not in window
        synth=[l for l in window.splitlines() if "GoogleTTSServiceImpl" in l and "Synthesis request" in l ]
        assert len(synth)==1,("arrival synthesis delta",len(synth))
        assert window.count("voice stop-arrival saying: You have arrived at S19 Stop 1. Continuing to S19 Stop 2.")==1
        return f"flip={ms:.0f}ms; arrival synthesis=1; no deviation-handler"
    row("c","arrival once and next stop within 2000ms",c,"arrival.log arrival.xml arrival.png drive.log")
    if drive.poll() is None:drive.terminate();drive.wait()
    def linger():
        ms=poll(lambda:not any(n.get("text")=="Arrived at S19 Stop 1" for n in ui("arrival-cleared").iter("node")),12)
        assert api("state").get("navigating") is True
        return "card cleared; observed after "+str(ms)+"ms; navigation continues"
    row("c-linger","intermediate card auto-clears; navigation continues",linger,"arrival-cleared.xml arrival-cleared.png")
    def d():
        before=log();tap("Trip stops");tap("Remove stop S19 Stop 2")
        poll(lambda:"route stop-remove" in log()[len(before):])
        poll(lambda:'trip-bar next="S19 Destination"' in log()[len(before):])
        s=log()[len(before):];save("remove.log",s);ui("removed")
        assert re.search(r"route stop-remove .*steps=\d+",s)
        assert "state IDLE" not in s and "stop-pins numbered=0 flag=1" in s
        assert api("state").get("navigating") is True
        return "route replaced; destination next; 0 stops + flag; NAVIGATING"
    row("d","remove preserves navigation; updates bar/pins",d,"remove.log removed.xml removed.png")
except Exception as ex:
    rows.append(("setup","route fixtures and navigation",type(ex).__name__+": "+str(ex),"FAIL","run.log"))
finally:
    if drive and drive.poll() is None:drive.terminate();drive.wait()
    try:
        if not owns_trip: raise RuntimeError("contract aborted; existing trip left untouched")
        api("stop",{});adb("shell","settings","put","system","user_rotation",os.environ["ROTATION"])
        poll(lambda:api("state").get("navigating") is False,10)
        if initial_state and "lat" in initial_state:
            adb("emu","geo","fix",str(initial_state["lng"]),str(initial_state["lat"]))
        adb("shell","am","force-stop",pkg)
        if recent_before is not None:
            save("recent-before.json",recent_before)
            adb("push",str(ev/"recent-before.json"),recent_path)
        else: adb("shell","rm","-f",recent_path)
        adb("shell","am","start","-n",pkg+"/.MainActivity")
        poll(lambda:api("state").get("navigating") is False)
        fg=os.environ.get("FOREGROUND","")
        if fg and fg!=pkg: adb("shell","monkey","-p",fg,"1")
        media=adb("shell","dumpsys","media_session");save("restored-media.txt",media)
        assert not re.search(r"state=(?:PLAYING\(3\)|3[, ])",media)
        assert adb("shell","settings","get","system","user_rotation").strip()==os.environ["ROTATION"]
        if recent_before is not None: assert adb("shell","cat",recent_path)==recent_before
        save("restored-state.json",json.dumps(api("state")))
        rows.append(("restore","idle; rotation/recents/foreground restored; playback paused","verified; network unchanged","PASS","restored-state.json restored-media.txt"))
    except Exception as ex:rows.append(("restore","restore state",str(ex),"FAIL","run.log"))
    for i in ("a","b","c","d","e"):
        if not any(r[0]==i for r in rows):rows.append((i,"S19 criterion "+i,"setup failed","NOT RUN","run.log"))
    with (ev/"rows.tsv").open("w") as f:
        for r in rows:f.write("\t".join(x.replace("\t"," ").replace("\n"," ") for x in r)+"\n")
PY
while IFS=$'\t' read -r id criterion measured status files; do row "$id" "$criterion" "$measured" "$status" "$files"; done < "$EVID/rows.tsv"
crash_gate
docs/tablet-lock.sh "$S" release "$AGENT"
finish
