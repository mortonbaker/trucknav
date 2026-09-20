#!/usr/bin/env python3
"""Compare presentation callbacks to a committed baseline; no device actions."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def handlers(text: str) -> list[str]:
    # Extract balanced callback bodies, including nested lambdas. This is a
    # regression guard for existing source, not a Kotlin parser or runtime test.
    found = []
    pattern = r'(?:onClick|onCheckedChange|onDismissRequest)\s*=\s*\{|\.clickable(?:\([^\n]*?\))?\s*\{'
    for match in re.finditer(pattern, text):
        start = match.end() - 1
        depth = 0
        quote = None
        escape = False
        for i in range(start, len(text)):
            c = text[i]
            if quote:
                if escape:
                    escape = False
                elif c == '\\':
                    escape = True
                elif c == quote:
                    quote = None
                continue
            if c in ('"', "'"):
                quote = c
            elif c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    found.append(re.sub(r'\s+', ' ', text[start:i+1]).strip())
                    break
        else:
            raise ValueError('Unterminated callback')
    # Callbacks passed by reference must survive too.
    found += re.findall(r'(?:onClick|onCheckedChange|onDismissRequest)\s*=\s*([A-Za-z_][\w.]*)', text)
    return sorted(found)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    rows = []
    for path in sorted((repo/'app/src/main/java').rglob('*.kt')):
        rel = path.relative_to(repo).as_posix()
        old = subprocess.run(['git','show',f'{args.base}:{rel}'], cwd=repo, capture_output=True, text=True)
        if old.returncode:
            rows.append({'file': rel, 'status': 'FAIL', 'reason': 'No baseline: review added source explicitly'})
            continue
        before, after = handlers(old.stdout), handlers(path.read_text())
        rows.append({'file': rel, 'callbacks': len(after), 'status': 'PASS' if before == after else 'FAIL',
                     'baseline_sha256': hashlib.sha256('\n'.join(before).encode()).hexdigest(),
                     'candidate_sha256': hashlib.sha256('\n'.join(after).encode()).hexdigest()})
    result = {'baseline': args.base, 'kind': 'source callback regression, not runtime proof', 'rows': rows}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    failures = [row for row in rows if row['status'] != 'PASS']
    print(f'{len(rows)} files, {sum(r.get("callbacks",0) for r in rows)} callbacks, {len(failures)} mismatches')
    for row in failures:
        print(row['file'])
    raise SystemExit(bool(failures))


if __name__ == '__main__':
    main()
