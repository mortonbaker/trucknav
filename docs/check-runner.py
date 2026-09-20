import importlib.util
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).with_name('s7-smoke.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for name, state, lock in [
    ('offline', 'offline', ''),
    ('wrong owner', 'device', f'other\n{int(time.time())+600}'),
    ('expired', 'device', 'codex-s7\n1'),
]:
    tablet = module.Tablet('fixture', 'codex-s7')
    calls = []
    def fake(*args, **kwargs):
        calls.append(args)
        return state if args == ('get-state',) else lock
    tablet.adb = fake
    try:
        tablet.shell('input', 'tap', '10', '10')
        raise AssertionError('Mutation unexpectedly allowed')
    except RuntimeError:
        assert not any('input' in call for call in calls)
    print(f'PASS: {name} blocks mutation')
