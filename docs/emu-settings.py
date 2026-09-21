"""S20 acceptance: real API/MCP, bounded pixel timing, restart persistence, restore fixtures."""
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import base64
import subprocess
import time
import urllib.error
import urllib.request
from PIL import Image, ImageDraw

SERIAL = os.environ["SERIAL"]
E = Path(os.environ["S20_EVIDENCE"])
PKG = "com.morton.trucknav"
rows = []
transport_retries = 0
def adb(*args):
    global transport_retries
    try: return subprocess.check_output(["adb", "-s", SERIAL, *args])
    except subprocess.CalledProcessError:
        if args[0] != "logcat": raise
        transport_retries += 1
        print("ADB logcat transport failed; reconnecting once (recorded)",flush=True)
        subprocess.run(["adb","-s",SERIAL,"wait-for-device"],check=True,timeout=15)
        subprocess.run(["adb","-s",SERIAL,"forward","tcp:18782","tcp:8782"],check=True,stdout=subprocess.DEVNULL)
        return subprocess.check_output(["adb", "-s", SERIAL, *args])
def shell(*args):
    return adb("shell", *args).decode().strip()
def record(item, criterion, value, ok):
    rows.append(f"| {item} | {criterion} | {value} | {'PASS' if ok else 'FAIL'} |")
    print(rows[-1], flush=True)
def ui(label):
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        result=subprocess.run([str(Path.home()/"bin/ui.sh"), SERIAL, "tapx", label], stdout=subprocess.DEVNULL)
        if result.returncode==0: return
        time.sleep(.2)
    raise RuntimeError("UI control did not become ready: "+label)
def png(name):
    data = adb("exec-out", "screencap", "-p")
    (E/name).write_bytes(data)
    return Image.open(io.BytesIO(data)).convert("RGB")
def magenta(image):
    return sum(r > 230 and b > 230 and g < 45 for r,g,b in image.getdata())
def request(method, path, data=None, raw=False, token=None):
    body = data if isinstance(data, bytes) else json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request("http://127.0.0.1:18782"+path, data=body, method=method,
        headers={"Authorization":"Bearer "+(TOKEN if token is None else token), "Content-Type":"application/octet-stream" if isinstance(data,bytes) else "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        value=r.read()
        return value if raw else json.loads(value)
def ready():
    for _ in range(30):
        try: return request("GET","/api/settings")
        except (urllib.error.URLError, ConnectionError): time.sleep(.3)
    raise RuntimeError("API failed to start")
def restart():
    shell("am","force-stop",PKG)
    shell("am","start","-n",PKG+"/.MainActivity")
    ready()
def mcp_call(code):
    env=os.environ.copy()
    env.update(TRUCKNAV_URL="http://127.0.0.1:18782",TRUCKNAV_TOKEN=TOKEN)
    path=Path.cwd()/"mcp/trucknav_mcp.py"
    program="import importlib.util; s=importlib.util.spec_from_file_location('s20mcp',"+repr(str(path))+"); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "+code
    return subprocess.check_output([os.environ.get("S20_MCP_PYTHON",str(Path.home()/"trucknav-mcp/venv/bin/python3")),"-c",program], env=env)

adb("forward","tcp:18782","tcp:8782")
# Private debug data is read into memory only; never emit the actual token in evidence.
saved=json.loads(adb("shell","run-as",PKG,"cat","files/settings.json"))
TOKEN=saved["apiToken"]
(E/"pre-logcat.txt").write_bytes(adb("logcat","-d"))
adb("logcat","-c","-b","crash")
shell("am","start","-n",PKG+"/.MainActivity")
ready()
original_places=request("GET","/api/favorites")
try: original_vehicle=request("GET","/api/vehicle",raw=True)
except urllib.error.HTTPError as err:
    if err.code != 404: raise
    original_vehicle=None
backup_fd,backup_name=tempfile.mkstemp(prefix="trucknav-s20-restore-",suffix=".json")
with os.fdopen(backup_fd,"w") as backup:
    json.dump({"settings":saved,"favorites":original_places,"vehicle":base64.b64encode(original_vehicle).decode() if original_vehicle else None},backup)
print("Private recovery snapshot (mode 0600): "+backup_name,flush=True)
try:
    ready()
    initial=png("initial.png")
    request("PUT","/api/settings",{"setupComplete":"true"})
    restart()
    ui("Map")
    time.sleep(.5)
    initial=png("map-before.png")
    checks = ["valhallaUrl","photonUrl","styleUrl","absUrl","venusHost","relayHost","units","voiceDisabled","autoNight","apiToken"]
    snapshot=request("GET","/api/settings")
    record("a settings", "All pane settings readable; token generated or migrated", f"{sum(k in snapshot for k in checks)}/{len(checks)} fields; token {len(TOKEN)} chars", all(k in snapshot for k in checks) and bool(TOKEN))
    old_token = TOKEN
    replacement_token = secrets.token_hex(32)
    request("PUT","/api/settings",{"apiToken":replacement_token})
    TOKEN = replacement_token
    try: request("GET","/api/settings",token=old_token); revoked = False
    except urllib.error.HTTPError as err: revoked = err.code == 401
    record("token rotation","Old token rejected immediately; new token accepted",revoked,revoked and bool(request("GET","/api/settings")))
    request("PUT","/api/settings",{"tomtomKey":"s20-disposable-test-9876","trafficProvider":"off"})
    masked=request("GET","/api/settings")["tomtomKey"]
    record("secrets","Only last 4, no plaintext in response",masked,masked=="••••9876")
    for body, status in [({"trafficProvider":"invalid"},400),({"units":[]},400)]:
        try: request("PUT","/api/settings",body); code=200
        except urllib.error.HTTPError as err: code=err.code
        record("validation","Bad Settings request = 400",code,code==status)
    image=Image.new("RGBA",(512,512))
    ImageDraw.Draw(image).polygon([(256,5),(500,500),(256,390),(12,500)],fill=(255,0,255,255))
    image.save(E/"vehicle-input.png")
    started=time.monotonic()
    reply=request("PUT","/api/vehicle",(E/"vehicle-input.png").read_bytes())
    count=0
    while time.monotonic()-started <= 2:
        shot=png("vehicle-hot.png"); count=magenta(shot)
        if count>200: break
    elapsed=time.monotonic()-started
    record("b hot reload","Visible uploaded puck <=2 s",f"{elapsed:.3f}s; {count} magenta px",elapsed<=2 and count>200)
    rendered=Image.open(io.BytesIO(request("GET","/api/vehicle",raw=True)))
    record("b resize","512px PNG -> 256x256 PNG",str(rendered.size),rendered.size==(256,256))
    restart(); time.sleep(2); ui("Map"); time.sleep(.4)
    count=magenta(png("vehicle-restart.png"))
    record("b persistence","Uploaded puck survives force-stop",f"{count} magenta px",count>200)
    request("DELETE","/api/vehicle")
    started=time.monotonic()
    while time.monotonic()-started<=2:
        count=magenta(png("vehicle-delete.png"))
        if count<30: break
    record("b delete","Default restored <=2 s",f"{time.monotonic()-started:.3f}s; {count} magenta px",count<30)
    for data,expected in [(b"invalid png",400),(b"x"*(2*1024*1024+1),413)]:
        try: request("PUT","/api/vehicle",data); code=200
        except urllib.error.HTTPError as err: code=err.code
        record("vehicle reject","Invalid=400; >2MB=413",code,code==expected)
    mcp_call("m.set_home(33.214, -97.133, 'S20 smoke home')")
    home=next(p for p in request("GET","/api/favorites") if p["kind"]=="home")
    shell("uiautomator","dump","/sdcard/s20-ui.xml")
    hierarchy=adb("shell","cat","/sdcard/s20-ui.xml").decode()
    (E/"home.xml").write_text(hierarchy)
    record("c MCP home","MCP Home updates persistent singleton and visible Go: Home tile",f'{home["name"]}; {home["lat"]},{home["lng"]}',home["name"]=="S20 smoke home" and "Go: Home" in hierarchy)
    mcp_call("m.set_work(33.22, -97.14, 'S20 smoke work')")
    record("MCP work","Work singleton",len([p for p in request("GET","/api/favorites") if p["kind"]=="work"]),len([p for p in request("GET","/api/favorites") if p["kind"]=="work"])==1)
    # Use identical harmless fixture to exercise real MCP upload transport.
    mcp_call("m.upload_vehicle("+repr(str(E/"vehicle-input.png"))+")")
    record("MCP upload","Real upload_vehicle(path) HTTP roundtrip",len(request("GET","/api/vehicle",raw=True)),True)
    if os.environ.get("S20_SOURCE_CHECK") == "external":
        print("Source check is recorded separately on atlas01 against the built source; no source gate claimed here", flush=True)
    else:
        forbidden="home"+"Lat"
        result=subprocess.run(["grep","-r",forbidden,"app/"],capture_output=True)
        record("d no hardcoded home","grep -r homeLat app/ empty",result.returncode,result.returncode==1)
    logs=adb("logcat","-d")
    record("secret logs","No Settings secret in logs","absent" if b"s20-disposable-test-9876" not in logs else "present",b"s20-disposable-test-9876" not in logs)
    (E/"post-logcat.txt").write_bytes(logs)
finally:
    adb("forward","tcp:18782","tcp:8782")
    current=request("GET","/api/settings")
    restore={key:saved.get(key) for key in current}
    restore.update(saved)
    request("PUT","/api/settings",restore)
    TOKEN = saved["apiToken"]
    for kind in ("home","work"):
        old=next((p for p in original_places if p["kind"]==kind),None)
        if old: request("PUT","/api/favorites/"+kind,{"lat":old["lat"],"lng":old["lng"],"name":old["name"]})
        else: request("DELETE","/api/favorites/"+kind)
    if original_vehicle: request("PUT","/api/vehicle",original_vehicle)
    else: request("DELETE","/api/vehicle")
    os.unlink(backup_name)
crash=adb("logcat","-d","-b","crash")
(E/"crash.txt").write_bytes(crash)
count=crash.count(b"Process: "+PKG.encode())
record("transport", "ADB log collection retries (informational)", transport_retries, True)
record("crash gate","0 TruckNav crashes",count,count==0)
(E/"results.md").write_text(chr(10).join(["| Check | Criterion | Measured | Result |", "|---|---|---|---|", *rows, ""]))
raise SystemExit(1 if any("| FAIL |" in row for row in rows) else 0)
