"""Validate generated-token first run before provisioning the fresh emulator."""
from pathlib import Path
import json
import os
import subprocess
import struct
import time
import urllib.request

P="com.morton.trucknav"
E=Path(os.environ["S20_FRESH_EVIDENCE"])
def adb(*args): return subprocess.check_output(["adb","-s","emulator-5554",*args])
for _ in range(40):
    try: settings=json.loads(adb("shell","run-as",P,"cat","files/settings.json")); break
    except (subprocess.CalledProcessError,json.JSONDecodeError): time.sleep(.25)
else: raise RuntimeError("First run did not persist settings")
assert len(settings["apiToken"])==64 and all(c in "0123456789abcdef" for c in settings["apiToken"])
assert settings["valhallaUrl"]==""
assert settings["absPass"]==""
assert settings.get("setupComplete") is None
for _ in range(20):
    try:
        adb("shell","uiautomator","dump","/sdcard/s20-first.xml")
        xml=adb("shell","cat","/sdcard/s20-first.xml")
        if b"Viewing full screen" in xml:
            subprocess.run([str(Path.home()/"bin/ui.sh"),"emulator-5554","tapx","Got it"],check=True,stdout=subprocess.DEVNULL)
            continue
        if b"Set up your truck" in xml and b"Continue to map" in xml: break
    except subprocess.CalledProcessError: pass
    time.sleep(.5)
else: raise RuntimeError("First-run setup UI did not become ready")
(E/"first-run.xml").write_bytes(xml)
# Default QR is hidden, so this screenshot contains no credential.
(E/"first-run.png").write_bytes(adb("exec-out","screencap","-p"))
token=settings["apiToken"]
def request(method,path,body=None):
    req=urllib.request.Request("http://127.0.0.1:18782"+path,method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=10) as response: return json.load(response)
assert request("GET","/api/settings")["apiToken"]=="••••"+token[-4:]
# Follow documented configuration from this installation's private source.
properties={}
for line in Path("local.properties").read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k,v=line.split("=",1); properties[k]=v.strip()
changes={k:properties[k] for k in ["valhallaUrl","photonUrl","absUrl","absUser","absPass","venusHost","venusPortalId"] if k in properties}
changes["setupComplete"]="true"
changes["styleUrl"]="https://demotiles.maplibre.org/style.json"
request("PUT","/api/settings",changes)
(E/"first-run-result.txt").write_text("PASS empty-build fresh-profile first run; generated token64hex; blank router; no seeded password; setup UI; masked API; runtime provisioning"+chr(10))
extract=next((Path.home()/"trucknav-assets").glob("*.pmtiles"))
with extract.open("rb") as source: header=source.read(127)
assert header[:8]==b"PMTiles"+bytes([3])
lng,lat=struct.unpack_from("<ii",header,119)
adb("emu","geo","fix",str(lng/1e7),str(lat/1e7))
print("PASS: empty-build fresh profile generated token64hex and accepted runtime configuration")
