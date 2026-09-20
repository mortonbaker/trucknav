#!/usr/bin/env python3
# puck.py <png> <x0> <y0> <x1> <y1>  — centroid of the location puck inside a map area, and where it sits.
# The puck (dot when browsing, arrow when navigating) and the route line share the same blue,
# so we take connected blue components and keep the compact one; the route line is a huge blob.
import sys
from collections import deque
from PIL import Image
f, x0, y0, x1, y1 = sys.argv[1], *map(int, sys.argv[2:6])
im = Image.open(f).convert("RGB")
px = im.load()
def blue(x, y):
    r, g, b = px[x, y]
    return abs(r - 53) < 25 and abs(g - 131) < 25 and abs(b - 221) < 25
seen = set(); comps = []
for y in range(y0, y1):
    for x in range(x0, x1):
        if (x, y) in seen or not blue(x, y): continue
        q = deque([(x, y)]); seen.add((x, y)); pts = []
        while q:
            cx, cy = q.popleft(); pts.append((cx, cy))
            for nx, ny in ((cx+1, cy), (cx-1, cy), (cx, cy+1), (cx, cy-1)):
                if x0 <= nx < x1 and y0 <= ny < y1 and (nx, ny) not in seen and blue(nx, ny):
                    seen.add((nx, ny)); q.append((nx, ny))
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        bw, bh = max(xs) - min(xs) + 1, max(ys) - min(ys) + 1
        comps.append((len(pts), bw, bh, sum(xs) / len(pts), sum(ys) / len(pts)))
cands = [c for c in comps if 100 <= c[0] <= 3000 and c[1] <= 70 and c[2] <= 70 and 0.5 <= c[1] / c[2] <= 2.0]
if not cands:
    print("puck: not found (components: %s)" % [(c[0], c[1], c[2]) for c in comps][:5]); sys.exit(1)
import math
def whitering(c):
    # the puck sits on a white disc; a stray route fragment does not
    cx, cy = c[3], c[4]; hits = tot = 0
    for k in range(36):
        a = k * math.pi / 18; x, y = int(cx + 20 * math.cos(a)), int(cy + 20 * math.sin(a))
        if 0 <= x < im.width and 0 <= y < im.height:
            tot += 1; r, g, b = px[x, y]
            if r > 240 and g > 240 and b > 240: hits += 1
    return hits / tot if tot else 0
n, bw, bh, cx, cy = max(cands, key=lambda c: (whitering(c), c[0]))
w, h = x1 - x0, y1 - y0
fx, fy = (cx - x0) / w, (cy - y0) / h
print(f"puck ({cx:.0f},{cy:.0f}) n={n} bbox={bw}x{bh}  map area [{x0},{y0}]-[{x1},{y1}] {w}x{h}  frac x={fx:.3f} y={fy:.3f}  off-center x={(fx-0.5)*100:+.1f}% y={(fy-0.5)*100:+.1f}%")
