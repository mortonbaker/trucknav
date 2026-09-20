#!/usr/bin/env python3
# mapstat.py <png> [x0 y0 x1 y1] — mean/stddev of the map region; classifies the style on screen.
import sys
from PIL import Image, ImageStat
f = sys.argv[1]; box = tuple(map(int, sys.argv[2:6])) if len(sys.argv) > 5 else (117, 200, 851, 700)
st = ImageStat.Stat(Image.open(f).convert("RGB").crop(box))
m = tuple(round(v) for v in st.mean); sd = tuple(round(v) for v in st.stddev)
kind = "dark" if sum(m) < 200 else ("imagery" if max(sd) > 50 else "light/terrain")
print(f"mean={m} stddev={sd} -> {kind}")
