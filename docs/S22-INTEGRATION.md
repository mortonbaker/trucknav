# S22 traffic integration contract

Owner: Astra. Worktree: build01 `~/trucknav-traffic`, branch `traffic-s22`.
Physical tablet is not used; claude-nav installs main when acceptance passes.

## Before/after control contract

The existing five basemap buttons retain their behavior: choose a style, save it,
close Layers. Navigation, End, preview selection and Start remain owned by nav.

| Control | Behavior | Evidence required |
|---|---|---|
| Layers: Traffic | Saves `trafficLayer`; TomTom only. Adds transparent flow above roads. Offline hides it without changing preference. | flow pixels within 5 s; offline no requests; restore within 10 s |
| Traffic provider: Off | Stops traffic requests and removes traffic ETA/flow/pins. | no network activity; base navigation unchanged |
| TomTom | Own key required; flow, ETA and incidents. | request fixtures + live smoke |
| Google (ETA only) | Own key required; no flow layer or incident pins. | request fixture TRAFFIC_AWARE, ETA text attribution |
| Save provider key | Commits masked input to Settings, clears text field. | restart persistence, no key in source/logs |
| Remove | Deletes provider key; provider becomes silently keyless. | no subsequent requests |
| Test key (per provider) | One request, 3 s maximum; reports OK, HTTP status, timeout, offline or missing key. | invalid key HTTP status; navigation still works |

Targets are at least 48 dp; traffic duration/status text is 24 sp.

## S20 owner hooks

Settings must be initialized before `Traffic.init(context)` in AppModule.
Call `com.morton.trucknav.traffic.TrafficSettings()` once inside the Settings pane.
Preserve `TrafficLayerToggle()` in MapStyleSheet when moving Voice/Auto-night.
Never log Settings PUT bodies, headers, provider URLs, raw errors or key values.
Keys are only `Settings.get("tomtomKey")` / `Settings.get("googleMapsKey")`.
No build configuration credentials. The development Settings stub is NOT shipped.

## Nav owner hooks (Astra does not edit RoutePreview/TripBar)

Convert geometry with `route.geometry.map { TrafficPoint(it.lat, it.lng) }`.

- Each A/B/C card can call `TrafficEtaText(points)` for the separate attributed
  `· 19 min w/ traffic` line. Google integration is HOLD pending the decision below.
- Custom rendering: suspend `Traffic.etaWithTraffic(points): TrafficEta?`.
  Timeout/error/offline/keyless => null. Queries run asynchronously, cancel on
  candidate/route change, and do not delay showing Valhalla's plain duration.
- `TrafficEta` has `duration`, `label`, `attribution`, `provider`, `measuredAt` and
  `fresh(SystemClock.elapsedRealtime())` (<600000 ms, strict boundary).
- TripBar supplies the REMAINING next-leg geometry. Never reuse an original
  whole-trip number after passing/removing a stop. Refresh at most once/minute;
  discard on stop removal, arrival, route replacement, provider/key changes or
  offline. The result belongs only to the geometry passed into the request.
- `Traffic.setRemainingRoute(points)` refreshes TomTom incident pins for the next
  25 km, filtered within 100 m of remaining segments. Call with emptyList on End.
  Do not call on every GPS tick: refresh on route/leg change and once/minute.

The only DemoNavigationScene edit is `TrafficLayer()` before pin overlays.
Valhalla remains the router; traffic never changes Ferrostar navigation state.

## Request/security policy

TomTom raster tile URLs never enter MapLibre: a loopback-only proxy signs outgoing
requests. No response cache; no-store honored. Four concurrent workers, bounded
queue, 2 MiB response limit, 2.8 s network deadline. Attempts count against a
persistent sliding 2000/30-minute budget, including key-test flow requests.
Daily NavLog `traffic tiles=N day=YYYY-MM-DD` counts attempts, including failures.
Offline/provider/key changes cancel in-flight calls and invalidate ETA results.
ETA cache is memory-only, at most eight exact geometries and <10 minutes old.

## Research sources and limitations

- [TomTom flow](https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-flow/raster-flow-tiles): relative0 transparent raster, 512 px.
- [TomTom routing](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route): traffic=true, departAt=now, strict supporting-point reconstruction.
- [TomTom incidents](https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-incidents/incident-details): v5, current incidents, bounded corridor.
- [Google computeRoutes](https://developers.google.com/maps/documentation/routes/compute_route_directions): TRAFFIC_AWARE, ordered via points, duration-only field mask.
- [Google attribution](https://developers.google.com/maps/documentation/routes/policies): Google Maps attribution is required with returned text.
- [Google service-specific terms §19.2](https://cloud.google.com/maps-platform/terms/maps-service-terms): Routes content cannot be used in conjunction with a non-Google map. This conflicts with the requested Google ETA on TruckNav's MapLibre/OSM view. Attribution alone does not cure it. The operator has been asked to choose TomTom-first with the adapter deferred, or a separate Google-map view. No Google map-card integration or main merge pending that decision.

No live provider key was supplied at implementation time. Fixture tests cannot
prove real traffic pixels, provider account permissions, live-route matching,
30-minute request usage, or end-to-end nav/Settings UI integration. Those remain
explicit acceptance gates; no DONE or main merge until they pass.


## Resume this worktree

The S20 Settings class is still a local, untracked development stub in
`app/src/main/java/com/morton/trucknav/settings/Settings.kt`. Do not stage or ship
it. Its source/template is in build01 `~/s22-work/Settings.kt.stub`. Replace this
stub with the real S20 contract when that branch becomes available; S20 was
reported merged locally on atlas01 as 05f6151, followed by receipt 0348a53.
At handoff, atlas01 was reported unreachable and its GitHub push was awaiting
explicit operator approval. S22 has not attempted any GitHub push.

No traffic credentials were supplied. No valid-key live traffic test or 30-minute
soak has run. The nav-owned files have not been edited. Before merging: integrate
the owner hooks, finish the live UI harness, resolve the Google-map decision,
run every live gate plus cockpit, claim main, and allocate max(versionCode)+2
across all hosts/worktrees. No version has been allocated for S22.
