#!/usr/bin/env python3
# contrast.py <png> <x0> <y0> <x1> <y1> — inside the search field's bounds: the container colour
# (mode of the pixels), the brightest text pixels, and the WCAG contrast ratio between them.
import sys
from collections import Counter
from PIL import Image
f, x0, y0, x1, y1 = sys.argv[1], *map(int, sys.argv[2:6])
im = Image.open(f).convert("RGB"); px = im.load()
def lum(c):
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = c; return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
def ratio(a, b):
    la, lb = lum(a), lum(b); hi, lo = max(la, lb), min(la, lb); return (hi + 0.05) / (lo + 0.05)
pts = [px[x, y] for y in range(y0 + 4, y1 - 4) for x in range(x0 + 4, x1 - 4)]
container = Counter(pts).most_common(1)[0][0]
bright = [c for c in pts if sum(c) > 600]
text = max(pts, key=sum)
print(f"container={container} (share {Counter(pts)[container]/len(pts):.0%}) text-peak={text} text-pixels={len(bright)} "
      f"contrast={ratio(container, text):.1f}:1 height={y1-y0}px width={x1-x0}px")
