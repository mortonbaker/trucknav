#!/usr/bin/env python3
# Derive satellite / hybrid / terrain styles from the Protomaps light style.
# Run on atlas01: python3 mkstyles.py  → writes ~/trucknav-assets/style-{satellite,hybrid,terrain}.json
import copy, json, os
A = os.path.expanduser("~/trucknav-assets")
light = json.load(open(f"{A}/style-light.json"))

ESRI = {
    "type": "raster", "tileSize": int(os.environ.get("ESRI_TILE", "256")), "maxzoom": 19,
    "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
    "attribution": "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community",
}
TERRARIUM = {
    "type": "raster-dem", "encoding": "terrarium", "tileSize": 256, "maxzoom": 15,
    "tiles": ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"],
    "attribution": "Terrain: Mapzen / AWS Open Data",
}

def base(name):
    s = copy.deepcopy(light); s["name"] = name; return s

# --- satellite: imagery only
sat = base("satellite")
sat["sources"] = {"esri": ESRI}
sat["layers"] = [
    {"id": "background", "type": "background", "paint": {"background-color": "#0c2a1e"}},
    {"id": "imagery", "type": "raster", "source": "esri", "paint": {"raster-fade-duration": 200}},
]
json.dump(sat, open(f"{A}/style-satellite.json", "w"), indent=1)

# --- hybrid: imagery + offline roads / boundaries / labels, recoloured for a dark background
hyb = base("hybrid")
hyb["sources"]["esri"] = ESRI
keep_prefix = ("roads_", "boundaries", "water_waterway_label", "water_label", "places_", "roads_labels", "roads_shields")
layers = [copy.deepcopy(l) for l in light["layers"] if l["id"].startswith(keep_prefix) and l["id"] not in ("roads_runway", "roads_taxiway", "roads_pier")]
for l in layers:
    p = l.setdefault("paint", {})
    if l["type"] == "line":
        if "casing" in l["id"]:
            p["line-color"] = "#000000"; p["line-opacity"] = 0.55
        elif l["id"] == "roads_rail":
            p["line-color"] = "#dddddd"
        else:
            p["line-color"] = "#ffffff"; p["line-opacity"] = 0.9
        p.pop("line-dasharray", None) if "tunnels" in l["id"] else None
    elif l["type"] == "symbol":
        p["text-color"] = "#ffffff"; p["text-halo-color"] = "#000000"; p["text-halo-width"] = 1.4
hyb["layers"] = [
    {"id": "background", "type": "background", "paint": {"background-color": "#0c2a1e"}},
    {"id": "imagery", "type": "raster", "source": "esri", "paint": {"raster-fade-duration": 200}},
] + layers
json.dump(hyb, open(f"{A}/style-hybrid.json", "w"), indent=1)

# --- terrain: light basemap + hillshade between landcover and landuse
ter = base("terrain")
ter["sources"]["terrarium"] = TERRARIUM
hill = {"id": "hillshade", "type": "hillshade", "source": "terrarium",
        "paint": {"hillshade-exaggeration": 0.8, "hillshade-shadow-color": "#4a4036", "hillshade-highlight-color": "#ffffff", "hillshade-accent-color": "#5a5040"}}
idx = next(i for i, l in enumerate(ter["layers"]) if l["id"] == "water")  # above every landuse fill, under water and roads
ter["layers"].insert(idx, hill)
json.dump(ter, open(f"{A}/style-terrain.json", "w"), indent=1)

for n in ("satellite", "hybrid", "terrain"):
    s = json.load(open(f"{A}/style-{n}.json")); print(n, len(s["layers"]), "layers", list(s["sources"]))
