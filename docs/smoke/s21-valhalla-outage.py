#!/usr/bin/env python3
"""Run ON homebackup after all agents grant a short backend-test window."""
import subprocess
import sys
import time
from pathlib import Path

running = subprocess.check_output(['docker', 'inspect', '--format', '{{.State.Running}}', 'valhalla'], text=True).strip()
if running != 'true':
    raise SystemExit('Valhalla was not running; aborting')
log = Path('/tmp') / ('s21-valhalla-restore-' + str(time.time_ns()) + '.log')
with log.open('wb') as output:
    restore = subprocess.Popen(
        [sys.executable, '-c', 'import time,subprocess; time.sleep(45); subprocess.run(["docker","start","valhalla"],check=True)'],
        stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
    )
print(f'Restart scheduled in45s, pid={restore.pid}, log={log}', flush=True)
subprocess.run(['docker', 'stop', 'valhalla'], check=True)
print('Stopped; detached restart remains active.', flush=True)
