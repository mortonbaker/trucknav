#!/usr/bin/env python3
"""S7 real-tablet visual/control evidence. Never acquire a lease implicitly.

Run under an existing codex-s7 lease, from a silent, non-navigating cockpit.
Example: python3 docs/s7-smoke.py --serial SERIAL --output ~/evidence/s7-TAG --restore-pane Music
Use --baseline to record pre-existing size failures without calling them passes.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'com.morton.trucknav'
PANES = ('Map','Music','Books','YouTube','Power','Vehicle','Apps')


class Tablet:
    def __init__(self, serial: str, owner: str):
        self.serial, self.owner = serial, owner

    def adb(self, *args: str, binary: bool = False):
        return subprocess.check_output(['adb','-s',self.serial,*args],timeout=30, text=not binary)

    def guard(self):
        if self.adb('get-state').strip() != 'device':
            raise RuntimeError('Tablet is not live; offline is NOT an unheld lease')
        lock = self.adb('shell','cat','/sdcard/.agent-lock').strip().splitlines()
        if len(lock) < 2 or lock[0].strip() != self.owner or int(lock[1]) < time.time()+45:
            raise RuntimeError('Missing/wrong/expiring lease; no tablet mutation allowed')

    def shell(self,*args):
        self.guard()
        return self.adb('shell',*args)

    def xml(self):
        self.shell('uiautomator','dump','/sdcard/s7-ui.xml')
        text=self.adb('shell','cat','/sdcard/s7-ui.xml')
        return text, ET.fromstring(text[text.index('<?xml'):])

    def tap(self,label, rail=False):
        _,tree=self.xml()
        matches=[n for n in tree.iter('node') if label in (n.get('text'),n.get('content-desc'))]
        if rail:
            # Match the semantic rail icon, not a similarly labelled Apps tile.
            matches=[n for n in matches if n.get('content-desc')==label]
            coords=[(n, bounds(n)) for n in matches]
            candidates=[n for n,b in coords if b[0] < 120 or b[1] > 1100]
            matches=candidates or matches
        if len(matches)!=1:
            raise RuntimeError(f'Expected unique {label!r}, got {len(matches)}')
        x0,y0,x1,y1=bounds(matches[0])
        self.shell('input','tap',str((x0+x1)//2),str((y0+y1)//2))


def bounds(node):
    return tuple(map(int,re.findall(r'\d+',node.get('bounds',''))))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True)
    p.add_argument('--owner',default='codex-s7')
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--restore-pane',choices=PANES,required=True)
    p.add_argument('--baseline',action='store_true')
    args=p.parse_args()
    t=Tablet(args.serial,args.owner)
    t.guard()
    args.output.mkdir(parents=True,exist_ok=False)
    density_text=t.adb('shell','wm','density')
    density=int(re.findall(r'(?:Physical|Override) density: (\d+)',density_text)[-1])/160
    saved={key:t.adb('shell','settings','get','system',key).strip() for key in ('font_scale','user_rotation','accelerometer_rotation')}
    package=t.adb('shell','dumpsys','package',PACKAGE)
    media=t.adb('shell','dumpsys','media_session')
    (args.output/'before-media.txt').write_text(media)
    if re.search(r'state=PLAYING\(3\)',media):
        raise RuntimeError('Start from a known silent state; this runner does not pause another session')
    rows=[]
    (args.output/'before-crash.txt').write_text(t.adb('logcat','-d','-b','crash'))
    (args.output/'identity.json').write_text(json.dumps({'serial':args.serial,'density':density,'settings':saved,'version':re.findall(r'version(?:Code|Name)=\S+',package),'baseline':args.baseline},indent=2))
    try:
        t.shell('settings','put','system','accelerometer_rotation','0')
        for scale in ('1.0','1.3'):
            t.shell('settings','put','system','font_scale',scale)
            for orientation,rotation in [('land',1),('port',0)]:
                t.shell('settings','put','system','user_rotation',str(rotation))
                time.sleep(3)
                for pane in PANES:
                    t.tap(pane,rail=True)
                    time.sleep(2)
                    tag=f'{scale}-{orientation}-{pane}'
                    xml,tree=t.xml()
                    (args.output/f'{tag}.xml').write_text(xml)
                    (args.output/f'{tag}.png').write_bytes(t.adb('exec-out','screencap','-p',binary=True))
                    small=[]
                    for n in tree.iter('node'):
                        if n.get('package')!=PACKAGE or n.get('clickable')!='true' or n.get('enabled')!='true': continue
                        x0,y0,x1,y1=bounds(n)
                        if not (x1>x0 and y1>y0): continue
                        if min(x1-x0,y1-y0)/density <47.5:
                            small.append({'label':n.get('content-desc') or n.get('text'),'bounds':n.get('bounds'),'dp':[round((x1-x0)/density,1),round((y1-y0)/density,1)]})
                    rows.append({'id':'V3-visible-bounds','screen':tag,'status':'FAIL' if small else 'PASS','small_targets':small,'note':'Visible size check only; V1 clipping/coverage and V2 contrast remain NOT RUN'})
    finally:
        (args.output/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
        # Restore even after failures, but never steal an expired/reassigned lease.
        for key,value in saved.items():
            if value=='null': t.shell('settings','delete','system',key)
            else: t.shell('settings','put','system',key,value)
        time.sleep(3)
        t.tap(args.restore_pane,rail=True)
        (args.output/'after-crash.txt').write_text(t.adb('logcat','-d','-b','crash'))
        (args.output/'results.json').write_text(json.dumps(rows,indent=2)+'\n')
    failures=[r for r in rows if r['status']!='PASS']
    print(f'{len(rows)} screens captured; {len(failures)} size failures. No blanket visual/behavior PASS is implied.')
    raise SystemExit(1 if failures and not args.baseline else 0)


if __name__=='__main__': main()
