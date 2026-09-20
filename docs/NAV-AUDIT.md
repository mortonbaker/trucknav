# Navigation audit — TruckNav v0.14.0 vs. what a car navigator is expected to do

Written 2026-09-20. Reference set: Android Auto / AAOS navigation-app rules (Google's Design for Driving + Car App Library), Google Maps, Waze, Apple Maps (CarPlay), OsmAnd, HERE WeGo. "Have" = verified on the tablet; "Partial" = exists but fails a rule; "Missing" = not built.

Sources: [Navigation apps — Design for Driving](https://developer.android.com/design/ui/cars/guides/app-types/navigation-apps) (required flows, 24 dp map text, 36 dp markers, ETA/next-turn/lane guidance must show), [Build a navigation app — Car App Library](https://developer.android.com/training/cars/apps/navigation) (NavigationManager: only one app navigates, `onStopNavigation`; alerts ≤ 2 actions, ~10 s; `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` + `GAIN_TRANSIENT_MAY_DUCK` for voice), [Visual principles](https://developers.google.com/cars/design/design-foundations/visual-principles) (4.5:1, 76 dp targets, night = light-on-dark), [AOSP driver distraction](https://source.android.com/docs/automotive/driver_distraction/guidelines).

## 1. Single navigator (the "Waze or Google Maps, never both" rule)
Android Auto's host arbitrates: one app holds navigation focus; when another starts, the first gets `onStopNavigation()` and must stop guidance, notifications and cluster output at once. TruckNav *is* the host here, so it owns that rule.

| Rule | Status | Evidence / gap |
|---|---|---|
| Only one app produces guidance | **Missing** | OsmAnd's NavigationService ran a route and took guidance audio focus alongside TruckNav on the 09-20 drive (`dumpsys audio`, `dumpsys activity services`). |
| Starting TruckNav navigation stops any other navigator | Missing | No code path; `am force-stop` by hand. |
| Foreign guidance is detected and surfaced | Missing | — |

**Plan (S17.1):** TruckNav registers as the navigation authority: on start of a route and every 30 s while navigating, check `AudioManager` focus holders / running foreground services of known navigators (OsmAnd, Google Maps, Waze, HERE, Sygic); if one is active, kill it if we are device-owner/`KILL_BACKGROUND_PROCESSES` allows, else show a one-tap "Stop <app>" banner; disable OsmAnd on the tablet (`pm disable-user`). Done when: a 20-min drive shows only our uid with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`.

## 2. Search and destination entry
| Expected (Maps/Waze/AA) | Status | Gap |
|---|---|---|
| Free-text search: addresses, place names, categories ("Whole Foods", "gas") | Partial | Photon does names/addresses; category queries return whatever OSM tags match, no ranking by distance/opening. |
| Multiple results shown **as a lettered/numbered list with matching map markers** (A, B, C…), each row with distance and drive time, tap a marker or a row to preview | **Missing** | Results are a plain text list, no markers, no distance, no ETA. |
| Bias to current location and route corridor; "along the route" search while navigating | Partial | Photon gets a lat/lon bias only. No search while navigating (search field hidden). |
| Recent searches / history reuse (AA required flow "search using past results while driving") | Missing | — |
| Favorites: Home, Work, saved places, one tap | **Missing** | — |
| Long-press map → pin → details → Start (AA "location details then navigate") | Have | Dropped pin + sheet. |
| Keyboard use is a parked-only affair; big targets, ≥ 24 sp | Have | Search pill 24 sp, 64 dp; result rows 72 dp (0.12.2). |
| Voice destination entry | Missing | S18. |
| Offline search | Missing | Photon is komoot online; S10 self-host or on-device index. |

**Plan (S17.2):** results as `A–F` markers on the map (36 dp, letter badge) + rows "A · Whole Foods · 3.2 mi · 8 min · Flower Mound" sorted by distance; tapping either previews with the camera fitting all results; `Recent` and `Favorites` tiles above the field; "along route" bias when navigating (search corridor = route bbox). Distance/time per row come from a Valhalla `sources_to_targets` matrix call (one request for all results).

## 3. Route preview and choice
| Expected | Status | Gap |
|---|---|---|
| Preview shows the route on the map with ETA, distance, and alternatives (AA "route preview", Maps shows up to 3) | Partial | Sheet shows name + coordinates + Start; no route line, no ETA, no alternatives until Start. |
| Camera fits the whole route (or all results) with the chrome accounted for | Partial | Preview fits the pin only. |
| Avoid tolls/highways/ferries, vehicle profile (height/weight for a truck) | Missing | Valhalla `auto` costing, no options. |

**Plan (S17.3):** on selection, request the route(s) first (Valhalla `alternates: 2`), draw them, show "12 min · 8.8 mi · via 377" cards, Start on the chosen one. Costing options (avoid tolls/highways) in the Navigation settings sheet.

## 4. Active guidance
| Expected | Status | Gap |
|---|---|---|
| Turn card: maneuver icon, distance, road; next-next hint | Have | Ferrostar banner (S2 padding fixed). |
| Lane guidance | Partial | Ferrostar supports `laneInfo`; Valhalla returns it only with certain options — unverified. |
| ETA / remaining distance / arrival time strip, refreshed continuously | Have | Arrival bar. |
| Speed limit + current speed, over-limit warning | Partial | Speed-limit sign wired (MUTCD) but Valhalla speed limits not enabled in the request; no over-limit color. |
| Voice: turn prompts, "in 1 mile…", rerouting announcements; ducks music, never pauses it | Partial | Ferrostar TTS speaks; ducking not verified; **mute reported not working** (re-verify against our own TTS now OsmAnd is dead — note the S17 test). |
| Mute and alert preferences (AA: alerts are opt-in classes; Waze/Maps let you pick alerts) | **Missing** | No preferences; whatever Valhalla emits is spoken. |
| Route overview button fits the **entire route** inside the visible map, chrome excluded | **Partial (bug)** | Ferrostar's `showRouteOverview` uses `mapViewInsets` for padding and we pass none → the bbox is fitted edge-to-edge, so the route ends up under the turn card and arrival bar. Fix: pass insets (turn card height, arrival bar height, side controls, plus our strip). |
| Recenter / follow after panning; auto-recenter after N s | Have | Recenter button; auto-recenter timeout not verified. |
| Add a stop while driving (AA required flow) | Missing | Single waypoint only. |
| Arrival: "You have arrived", end navigation automatically, show destination card | Partial | Ferrostar arrival state exists; end behaviour not verified. |
| Reroute on deviation with announcement | Have (with the S12 guard) | Verified no ghost reroutes in the 09-20 log. |
| Off-route beyond N s → reroute, else "return to route" | Have | Ferrostar deviation handler. |
| Night mode automatic (sunset or light sensor); light-on-dark at night | Partial | Manual style choice only. |
| Nav continues in background / notification with next turn (AA TBT notification) | Have | Ferrostar foreground notification (id 501). |
| Nav keeps running with the screen off / app in Recents | Partial | Not verified after S1's immersive changes. |

**Plan (S17.4):** overview insets; speed-limit annotations on; mute verified end-to-end; alert classes gated before TTS/banner; auto night mode from sunrise/sunset (no network needed); arrival flow test.

## 5. Alerts
| Expected | Status |
|---|---|
| Alert classes user-selectable (Waze: police/hazard/etc.; Maps: speed cameras, crashes; AA: `Alert` ≤ 2 actions, ~10 s) | Missing |
| Railroad crossings / crosswalks / school zones | Not ours — those were OsmAnd. Valhalla emits none of these by default. |
| Timed alerts (leave-now for a calendar destination) | Missing (nice-to-have) |

**Plan:** the NavLog now records every instruction; after a week we know the real classes. Settings sheet: big toggles per class; gating happens in the spoken-instruction observer and the banner mapper.

## 6. Favorites, history, API
| Expected | Status |
|---|---|
| Home / Work / named favorites, one tap from the map | Missing |
| Recent destinations | Missing |
| Programmatic access (Maps "share to device", Waze deep links) — for us: HTTP API + MCP so an agent can add favorites / start a route | Missing |

**Plan (S17.5):** `files/favorites.json` + `recent.json`; tiles above search; "Save as…" on any result/pin; token-protected HTTP API on the tablet (`/api/favorites`, `/api/navigate`, `/api/state`) + `trucknav-mcp` on atlas01.

## 7. Map and rendering (already tracked elsewhere)
Vehicle puck (have), styles (have), camera padding (have), tile prefetch (S15), on-device routing (S6), offline search (S10/S6).

## 8. Priority order for "navigation, finished"
1. Single navigator + OsmAnd disabled (S17.1) — safety.
2. Mute + alert classes (S17.4/5) — the voice must obey.
3. Route overview insets — one-line bug.
4. Search results as lettered markers with distance/ETA; recents (S17.2).
5. Favorites + Home/Work tiles (S17.5a).
6. Route preview with ETA and alternates; avoid tolls/highways (S17.3).
7. Favorites API + MCP (S17.5b).
8. Auto night mode, speed limits, add-a-stop, arrival flow (S17.4 tail).
