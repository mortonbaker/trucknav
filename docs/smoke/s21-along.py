#!/usr/bin/env python3
"""Real UI + independent matrix acceptance. Never fabricates a server outage."""
import json, math, os, re, subprocess, time, urllib.request, urllib.error, http.client, xml.etree.ElementTree as ET
from pathlib import Path
E=Path(os.environ['EVID']); ROOT=Path.cwd(); PKG='com.morton.trucknav'
ADB=[str(Path.home()/'Android/Sdk/platform-tools/adb'),'-s','emulator-5554']
rows=[]; port=None

def adb(*args):
    return subprocess.check_output(ADB+list(args),timeout=20)
def shell(*args): return adb('shell',*args).decode().strip()
def save(name,data):
    (E/name).write_bytes(data if isinstance(data,bytes) else data.encode())
def row(id,criterion,measured,passed,evidence):
    status='PASS' if passed else 'FAIL'
    rows.append((id,criterion,str(measured),status,evidence)); print(status,id,measured,flush=True)
def wait(fn,seconds=20):
    start=time.monotonic()
    while time.monotonic()-start<seconds:
        try:
            result=fn()
            if result is not None and result is not False: return result
        except (urllib.error.URLError, http.client.RemoteDisconnected, ConnectionError):
            pass
        time.sleep(.2)
    raise AssertionError('Timed out: '+getattr(fn,'__name__','observation'))
def logs(): return adb('logcat','-d','-s','NavLog').decode()
def ui(name=None):
    shell('uiautomator','dump','/sdcard/s21-ui.xml')
    data=adb('shell','cat','/sdcard/s21-ui.xml')
    if name: save(name+'.xml',data); save(name+'.png',adb('exec-out','screencap','-p'))
    return ET.fromstring(data[data.index(b'<?xml'):])
def find(tree,desc):
    return next((n for n in tree.iter('node') if n.get('content-desc')==desc),None)
def tap(desc):
    n=find(ui(),desc)
    assert n is not None and n.get('enabled')=='true', 'Missing/disabled '+desc
    x,y,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    assert x2>x and y2>y, 'Empty bounds '+desc
    shell('input','tap',str((x+x2)//2),str((y+y2)//2))
def api(path,data=None):
    req=urllib.request.Request(f'http://127.0.0.1:{port}/api/{path}',data=None if data is None else json.dumps(data).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(req,timeout=10))
def start_route():
    api('stop',{}); wait(lambda:not api('state')['navigating'])
    for attempt in range(2):
        api('navigate',dict(lat=33.072812,lng=-97.085042,name='Whole Foods Market'))
        try:
            wait(lambda:api('state')['navigating'],30)
            break
        except AssertionError:
            if attempt: raise
            print('Route precondition retry after cold-start network failure',flush=True)
            api('stop',{}); wait(lambda:not api('state')['navigating'])
    tap('Add stop')
    wait(lambda:find(ui(),'Along status: Search along route') is not None)
def events(text,tag):
    return [json.loads(line.split(tag+' ',1)[1]) for line in text.splitlines() if tag+' {' in line]
def results(query,start):
    text=wait(lambda: (l if f'along-paint query={query} ' in (l:=logs()[start:]) else None),20)
    save(query.lower()+'-log.txt',text)
    context=events(text,'along-context')[-1]
    geometry=[p for c in events(text,'along-geometry') for p in c['points']]
    hits=[h for h in events(text,'along-hit') if h['query']==query]
    paint=int(re.findall(r'along-paint query='+re.escape(query)+r' hits=\d+ elapsed_ms=(\d+)',text)[-1])
    return context,geometry,hits,paint,text

def point_segment(p,a,b):
    # Independent projection using vector dot product; compare to the app log too.
    k=math.pi*6371000/180
    def xy(c): return ((c[1]-p[1])*k*math.cos(math.radians(p[0])),(c[0]-p[0])*k)
    x,y=xy(a); u,v=xy(b); dx=u-x;dy=v-y; norm=dx*dx+dy*dy
    t=max(0,min(1,-(x*dx+y*dy)/norm)) if norm else 0
    return math.hypot(x+t*dx,y+t*dy)
def matrix(sources,targets,name):
    body=json.dumps(dict(sources=sources,targets=targets,costing='auto')).encode()
    req=urllib.request.Request(valhalla.rstrip('/').removesuffix('/route')+'/sources_to_targets',data=body,headers={'Content-Type':'application/json'})
    raw=urllib.request.urlopen(req,timeout=10).read();save(name,raw)
    return json.loads(raw)['sources_to_targets']
def verify_rows(hits,prefix):
    found={}; scrolls=0
    for attempt in range(4):
        tree=ui(prefix+'-rows'+str(attempt))
        parents={child:parent for parent in tree.iter() for child in parent}
        nodes=[n for n in tree.iter('node') if n.get('content-desc','').startswith('Along result ') and n.get('bounds')!='[0,0][0,0]']
        for n in nodes:
            letter=n.get('content-desc').split()[2].rstrip(':')
            values=[c.get('text') for c in parents[n].iter('node') if re.fullmatch(r'\+\d+ min',c.get('text',''))]
            if values: found[letter]=values[0]
        if len(found)>=len(hits): break
        assert nodes, 'No rendered result rows'
        boxes=[list(map(int,re.findall(r'\d+',n.get('bounds')))) for n in nodes]
        valid=[b for b in boxes if b[3]>b[1] and b[2]>b[0]]
        x=(valid[0][0]+valid[0][2])//2; top=min(b[1] for b in valid);bottom=max(b[3] for b in valid)
        shell('input','swipe',str(x),str(bottom-8),str(x),str(top+8),'350');scrolls+=1
    # Restore list scroll using only bounds actually observed above.
    for _ in range(scrolls+1 if scrolls else 0):
        shell('input','swipe',str(x),str(top+8),str(x),str(bottom-8),'350')
    return found

try:
    settings=dict(line.split('=',1) for line in (ROOT/'local.properties').read_text().splitlines() if '=' in line)
    token=settings['apiToken'];valhalla=settings['valhallaUrl']
    port=adb('forward','tcp:0','tcp:8782').decode().strip()
    assert 'versionCode=131 ' in shell('dumpsys','package',PKG), 'Unexpected build code'
    shell('am','force-stop',PKG)
    adb('emu','geo','fix','-97.204973','33.080088')
    shell('am','start','-W','-n',PKG+'/.MainActivity')
    wait(lambda:api('state').get('lat') is not None)
    tree=ui()
    if find(tree,'Map') is not None: tap('Map')
    start_route()
    if os.environ.get('S21_PHASE') != 'outage':
        offset=len(logs()); tap('Along Gas')
        ctx,geom,hits,ms,text=results('Gas',offset)
        distances=[min(point_segment((h['lat'],h['lng']),a,b) for a,b in zip(geom,geom[1:])) for h in hits]
        save('gas-distances.json',json.dumps(dict(hits=hits,independentM=distances,geometry=geom),indent=2))
        ui('gas')
        row('a','Gas >=3, all <=3218.688m, first paint <4000ms',f'hits={len(hits)} maxM={max(distances,default=99999):.1f} paint={ms}ms',len(hits)>=3 and all(d<=3218.688 for d in distances) and ms<4000,'gas.png; gas-distances.json; gas-log.txt')
        assert hits, 'Gas returned no hits'
        now=dict(lat=ctx['nowLat'],lon=ctx['nowLng']); nxt=dict(lat=ctx['nextLat'],lon=ctx['nextLng'])
        coords=[dict(lat=h['lat'],lon=h['lng']) for h in hits]
        a=matrix([now],coords+[nxt],'matrix-out.json')[0];b=matrix(coords,[nxt],'matrix-onward.json')
        shown=verify_rows(hits,'gas')
        comparisons=[]
        for i,h in enumerate(hits):
            detour=max(0,a[i]['time']+b[i][0]['time']-a[-1]['time'])
            display=shown.get(h['letter'],'missing')
            actual=int(display[1:-4])*60 if display!='missing' else -9999
            comparisons.append(dict(letter=h['letter'],display=display,matrixS=detour,errorS=abs(actual-detour)))
        save('detour-comparison.json',json.dumps(comparisons,indent=2))
        row('b','Every A-F row shows matrix detour +/-60s',comparisons,all(c['errorS']<=60 for c in comparisons),'detour-comparison.json; matrix-*.json; gas-rows*.png')
        picked=next(h for h in hits if h['letter']=='B');offset=len(logs())
        tap('Along result B: '+picked['label']+' '+shown['B'])
        after=wait(lambda:(l if 'along-added letter=B ' in (l:=logs()[offset:]) else None),25)
        save('picked-B-log.txt',after); ui('picked-B');state=api('state');save('picked-B-state.json',json.dumps(state))
        nextmatch=re.search(r'along-added letter=B nextLat=([\d.-]+) nextLng=([\d.-]+) navigating=true camera=(\w+)',after)
        good=bool(nextmatch) and nextmatch[3] not in ('FREE','OVERVIEW') and 'route stop-add' in after and state['navigating']
        if nextmatch: good=good and math.hypot(float(nextmatch[1])-picked['lat'],float(nextmatch[2])-picked['lng'])<.002
        row('c','Pick B becomes NEXT, NAVIGATING, camera following',nextmatch[0] if nextmatch else after[-300:],good,'picked-B-log.txt; picked-B.png')
        start_route(); offset=len(logs());tap('Search field');shell('input','text','Kroger')
        ctx,geom,kroger,ms,text=results('Kroger',offset)
        # Hide IME only when it reports being shown; otherwise Back would close add-stop.
        if 'mInputShown=true' in shell('dumpsys','input_method'): shell('input','keyevent','KEYCODE_BACK')
        kroger_ui=ui('kroger');orders=events(text,'along-orders')[-1]
        assert any(n.get('content-desc','').startswith('Along result ') for n in kroger_ui.iter('node')), 'Kroger rows not visible'
        row('d','Kroger sorted by detour; record puck and detour orders',orders,bool(kroger) and all(x['detourS']<=y['detourS'] for x,y in zip(kroger,kroger[1:])), 'kroger.png; kroger-log.txt')
    print('OUTAGE_READY: coordinate then run the self-restoring homebackup Valhalla stop now',flush=True)
    disabled=wait(lambda:(t if find(t:=ui(),'Along status: Routing server unavailable — search disabled') is not None else None),180)
    ui('outage')
    parents={child:parent for parent in disabled.iter() for child in parent}
    enabled=[parents[find(disabled,'Along '+x)].get('enabled') for x in ['Gas','Food','Coffee','Groceries']]
    row('e','Server down disables all four chips with one-line reason',enabled,enabled==['false']*4,'outage.xml; outage.png')
    wait(lambda:find(ui(),'Along status: Search along route') is not None or find(ui(),'Along status: No matches within 2 mi of your route') is not None,90)
    print('SERVER_RECOVERED',flush=True)
except Exception as e:
    row('exception','Smoke workflow completes',f'{type(e).__name__}: {e}',False,'run.log')
    import traceback;traceback.print_exc()
finally:
    try:
        if port:
            try: api('stop',{})
            finally: adb('forward','--remove','tcp:'+port)
        save('driver-logcat.txt',adb('logcat','-d'))
        ui('restored')
        print('restored: route stopped; playing='+str('state=PLAYING(3)' in shell('dumpsys','media_session')),flush=True)
    except Exception as e: row('restore','Restore succeeds',str(e),False,'run.log')
    (E/'rows.tsv').write_text('\n'.join('\t'.join(str(v).replace('\t',' ').replace('\n',' ') for v in r) for r in rows)+'\n')
    for wanted in (['e'] if os.environ.get('S21_PHASE')=='outage' else ['a','b','c','d','e']):
        if not any(r[0]==wanted for r in rows): print('NOT RUN',wanted,flush=True)
raise SystemExit(1 if any(r[3]=='FAIL' for r in rows) or not all(any(r[0]==x for r in rows) for x in (['e'] if os.environ.get('S21_PHASE')=='outage' else ['a','b','c','d','e'])) else 0)
