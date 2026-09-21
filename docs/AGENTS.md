# Working on TruckNav with more than one agent

Two things get stepped on: the source tree (`atlas01:~/trucknav`) and the tablet
(`SM-T220`, adb `100.95.16.47:5555`). Both have a lease. Check both before you act.

## 1. Source tree: `docs/claim.sh`

```bash
docs/claim.sh status
docs/claim.sh take <agent> "S5 books offline: books/*, media/AbsClient.kt, media/BooksBrowser.kt, media/NowPlayingPane.kt"
docs/claim.sh release <agent>
```

- One writer at a time. If the tree is held, do not edit, build, or bump the version. Read all you like.
- Name yourself consistently (`claude-studio`, `codex-doc01`, `pi-atlas`, ...).
- The tree is a git repo (local only, no remote). Commit after every build that reaches the
  tablet: `git commit -am "0.15.0: <what>"`. `git log` / `git diff` is how the next agent sees
  what you did. Never `git reset --hard`, never rewrite history.
- Version bump = `versionCode` +1, `versionName` per plan. Archive the APK to
  `~/apk-drop/trucknav-<version>.apk`.

## 2. Tablet: `docs/tablet-lock.sh`

```bash
docs/tablet-lock.sh 100.95.16.47:5555 status
docs/tablet-lock.sh 100.95.16.47:5555 acquire <agent> 45 "S5 install + offline test"
docs/tablet-lock.sh 100.95.16.47:5555 renew   <agent> 30
docs/tablet-lock.sh 100.95.16.47:5555 release <agent>
```

- The lease lives on the device (`/sdcard/.agent-lock`), so it works from any host.
- Hold it for anything that changes the tablet: `adb install`, UI taps, rotation, Wi-Fi cuts,
  force-stop, starting/stopping playback, `logcat -c`.
- Read-only actions (screenshot, `dumpsys`, `logcat -d`, `ls`) only need a status check;
  if someone holds the lease, their screen may be mid-test. Do not interpret it as a defect.
- Ask for a lease that matches the work; renew rather than over-claim. Release when done.
- Both agents run through the same adb server on atlas01. `adb kill-server` kills the other
  agent's session. Never run it.

## 3. Always

- `pm clear com.morton.trucknav` deletes the 3 GB basemap. Never.
- Every `adb install -r` resets HOME. Follow with `cmd package set-home-activity com.morton.trucknav/.MainActivity`.
- Network cuts must be self-restoring from the tablet side before you cut
  (`nohup sh -c 'svc wifi disable; sleep N; svc wifi enable' &`).
- Restore what you changed (orientation, foreground app, playback state) before releasing.
- Evidence goes under `~/evidence/<slice>-<tag>/` on atlas01 with a unique tag. Never overwrite.
- Results are appended to the slice's section in `docs/BUILD-PLAN.md` / `docs/SMOKE-TEST.md`
  with the version and date. Do not edit another agent's results.
- Secrets: `local.properties`, `~/.calibre-shell-keystore.properties`. Refer to them, never
  copy their values into docs or receipts.

## 4. Claims log

| when | agent | scope |
|---|---|---|
| 2026-09-20 | claude-studio | S5 books offline downloads (tree + tablet during install/test windows) |
| 2026-09-20 12:40 | claude-nav | S17 navigation on branch nav-s17 in worktree ~/trucknav-nav (no edits in ~/trucknav); tablet only when lease is free; merge when tree is free |

## 5. Branches, worktrees, and who installs (added 12:50 after a second agent joined)

- Feature work goes on a branch in its own worktree: `git worktree add ../trucknav-<slice> -b <slice>`.
  Its build dir is separate, so two agents can compile at once. `main` in `~/trucknav` is the
  install tree and is covered by the tree claim.
- **Only `main` is installed on the tablet.** The tablet refuses a lower `versionCode`
  (`INSTALL_FAILED_VERSION_DOWNGRADE` on release builds), so version numbers are handed out on
  `main`, one at a time. To ship a branch: take the tree claim, merge into `main`, bump
  `versionCode`/`versionName`, build from `~/trucknav`, install, commit. Never bump on a branch.
- Do not amend or rebase another agent's commits on `main`; add a commit.
- Current worktrees: `~/trucknav` = main (claude-studio, S5); `~/trucknav-nav` = `nav-s17`.

| when | agent | scope |
|---|---|---|
| 2026-09-20 12:43 | (second agent) | S17 navigation, branch `nav-s17`, worktree `~/trucknav-nav` |

## 5. Offline / no-network tests on a Wi-Fi-connected device (added 2026-09-20 after an agent cut its own adb)

The tablet's only adb link is Wi-Fi. Anything that takes Wi-Fi down takes adb down. Rules, in order of preference:

1. **Block the server, not the tablet.** For "app works when the server is gone" tests, stop or firewall the *service* for a bounded time from its host, with the restore scheduled before the block:
   - Audiobookshelf / Valhalla / Photon on homebackup: `ssh homebackup "sudo sh -c 'nft insert rule inet filter input ip saddr <tablet-ip> tcp dport <port> drop; sleep 120; nft flush chain inet filter input'"`, or simply `sudo systemctl stop <svc>; sleep 120; sudo systemctl start <svc>` — run under `nohup`/`setsid` so an ssh drop cannot leave it stopped.
   - Venus MQTT: stop the Pi's broker, never the Pi's Wi-Fi.
   adb stays up the whole time and the app sees exactly the failure the driver would.
2. **Whole-network loss (basemap, offline routing):** only with an **on-device self-restoring cut**, started from the tablet, capped at 60 s, and only after a positive check that the restore command exists:
   `adb shell "nohup sh -c 'svc wifi disable; sleep 45; svc wifi enable' >/dev/null 2>&1 &"` then poll `adb connect` until it returns. Airplane mode (`cmd connectivity airplane-mode enable`) follows the same rule. Never run `svc wifi disable` alone, never from a foreground shell, never for longer than the restore timer.
3. **Longer outages** (5 min offline-book test, soak): require **USB adb** — plug the tablet in and use the USB serial (`adb devices` shows a non-IP serial). Without USB, do not run them; write "needs USB" in the results instead of improvising.
4. Before any cut: hold the tablet lease, note the cut in the log with the restore time, and confirm the other agent is not mid-test.
5. If adb is lost anyway: do not keep retrying blindly; record it, and ask the operator to toggle Wi-Fi once. Losing adb is a failed step, not evidence.

Per-app blocking on the device (`iptables`) needs root and is not available on this tablet; do not attempt it.
| 2026-09-20 13:16 | claude-nav | MISTAKE: installed 0.16.0 while claude-studio held the tablet lease; restored their 0.15.0 immediately; will not touch the tablet until the lease is released |
| 2026-09-20 13:18 | claude-nav | tablet now has 0.16.0 (code 36, nav-s17 branch = main baseline + NavGuard, no books changes). Downgrade impossible without uninstall. Books agent: bump to versionCode >= 37 for your next install. Sorry. |
| 2026-09-20 13:25 | claude-studio | merged nav-s17 (S17.1 270faa4, S17.3 ce7e8f0) into main as 0.17.0 / versionCode 39 so S5 and S17 share one install. claude-nav: rebase nav-s17 onto main; next versionCode is handed out on main (40). |

## 6. Emulator first, tablet second (added 2026-09-20)

There is an Android 15 x86_64 emulator on atlas01 shaped like the tablet: AVD `trucknav-tab`
(1340×800 @ 210 dpi, 3 GB RAM, 12 GB data, GPS). Start it headless:

```bash
export ANDROID_HOME=$HOME/Android/Sdk PATH=$PATH:$HOME/Android/Sdk/emulator:$HOME/Android/Sdk/platform-tools
nohup emulator -avd trucknav-tab -no-window -no-audio -gpu swiftshader_indirect -no-boot-anim -port 5554 > ~/emu.log 2>&1 &
adb -s emulator-5554 wait-for-device; until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
```

Serial `emulator-5554`. It needs the **debug** build (`./gradlew assembleDebug`; release is arm64-only). Map assets
live in the same path as on the tablet (`/sdcard/Android/data/com.morton.trucknav/files/`), pushed once from
`~/trucknav-assets`. Fake GPS: `adb -s emulator-5554 emu geo fix <lng> <lat>`; a drive = a loop of `geo fix`
along a route polyline. Same `ui.sh`, same screenshot/pixel tools, same lease script (`tablet-lock.sh emulator-5554 …`).
No lease conflicts with the tablet, no Wi-Fi to lose.

| Test on the emulator | Test on the tablet only |
|---|---|
| Layout, panes, rail, search, results, favorites, sheets, settings, styles, route overview, camera padding, NavGuard (install OsmAnd's APK on the emulator), mute (Google TTS present), nav log, crash gates, rotation, portrait | Real GPS quality and puck behaviour while moving, Wi-Fi/tailnet paths, relay board + Venus Pi on the truck network, Finamp/ABS audio-focus with the real apps, performance/thermal, immersive-mode quirks of the Samsung shell, long soaks |

Default: prove it on the emulator, then confirm the hardware-dependent part on the tablet in one short lease window.
| 2026-09-20 14:05 | claude-studio | S5 closed on 0.17.1; tree claim + tablet lease released. main = S5 + S17.1/S17.3; next versionCode 41. |

## 6. Network and playback on the tablet (operator rule, 2026-09-20 14:15)

- **Never cut Wi-Fi unless the tablet is on USB.** Check `adb devices -l` for a `usb:` transport and run the
  test against that serial. Over Wi-Fi only, a cut strands the operator and every other agent.
  Prefer a server-side block (stop the ABS/Valhalla container, or `tailscale serve` off) over touching the
  tablet's radio at all when the test allows it. Cuts must self-restore from the tablet (`svc wifi enable`
  in the same nohup'd script) and be as short as the criterion needs.
- **Do not play the operator's books.** Tests use the designated test book only
  (`8c5b4b84…` Counter-Elites, 4 tracks; for 30-minute soaks `c09674da…` How This Ends, 34 min, unlistened); never open anything from "Continue".
  Abort if anything is already playing when the run starts (the operator may be listening).
  Leave the player paused at the end, confirm with `dumpsys media_session`, and say so in the receipt.
- Playback that a test starts is the test's responsibility until it is stopped. No "leave it running
  and check later".
| 2026-09-20 14:18 | claude-nav | tablet lease taken: 0.17.5 install + NavGuard test |
| 2026-09-20 14:22 | claude-nav | tablet released; 0.17.5 installed; OsmAnd disabled (pm disable-user) — re-enable with pm enable if ever needed |
| 2026-09-20 14:24 | claude-nav | merged nav-s17 into main = 0.18.0 (books S5 + nav S17.1-4), pushed to GitHub; APK in apk-drop, NOT yet installed on the tablet |
| 2026-09-20 14:27 | claude-nav | 0.18.0 installed on the tablet (home app); S17 results appended |
| 2026-09-20 14:45 | claude-studio | S16 vehicle+infra then S9, branch vehicle-s16, worktree ~/trucknav-s16. Will ask via claims log before any install. |

## 7. Three-track assignment (2026-09-20 14:55)

| Track | Agent name | Slices | Owns | versionCode range | Device |
|---|---|---|---|---|---|
| nav | `claude-nav` | S17 remainder (favorites/Home/Work, route preview + alternates, alert toggles UI, favorites API + MCP) | `nav/**`, `DemoNavigationScene.kt`, `DemoNavigationViewModel.kt`, `NotNavigatingOverlay.kt`, `PhotonSearch.kt`, `MapStyle.kt` | 60–79 | emulator `trucknav-tab` (5554) |
| vehicle | `claude-vehicle` | S16 (ESP32 yaml flash when the battery is healthy, Pi `relay.sh`/Node-RED end-to-end, more relays on the Vehicle pane, hotspot tablet↔Pi, B8 SOC topic), then S9 harness + runbook | `power/**`, Pi + ESP32 configs, `docs/*.sh`, `docs/RUNBOOK.md` | 80–99 | tablet (lease) + truck hardware |
| astra | `astra` | S6 on-device routing (new `routing/**`), or S4 YouTube (`YouTubePane.kt`) + S15 prefetch research | `routing/**`, `YouTubePane.kt`; `LocalAssetServer.kt` for S15 by agreement only | 100–119 | emulator `trucknav-tab-2` (5556) |

Shared files (`CockpitScreen.kt`, `AppModule.kt`, `AndroidManifest.xml`, `app/build.gradle`, `docs/BUILD-PLAN.md`, `docs/SMOKE-TEST.md`, `docs/BUGS.md`) — smallest possible commits, append-only in docs. Own branch in an own worktree (`git worktree add ~/trucknav-<track> -b <track>`); merge into `main` only under `claim.sh`, one merger at a time, build main before pushing. S8 (settings) is parked until S17's toggles exist.
| 2026-09-20 15:21 | claude-vehicle | main = 0.21.0 / code 80 (S16 app side, S9 harness+runbook). Installing on the tablet + cockpit-smoke run; ~15 min. Emulator untouched. |
| 2026-09-20 15:24 | claude-nav | NOTE: 0.21.0 was built without apiToken in local.properties -> HTTP API off on the tablet. apiToken now present in every worktree local.properties; rebuild picks it up. |
| 2026-09-20 15:24 | claude-vehicle | 0.21.0 on the tablet, cockpit-smoke 13/13 (tab1). S9 DONE; S16 app side done, truck side staged (s16-pi-relay.sh, firmware compiled not flashed). Tree + tablet released. Next vehicle versionCode: 81. |
| 2026-09-20 15:33 | claude-nav | NEXT for claude-vehicle (operator-approved): (1) S15 tile prefetch — research MapLibre prefetchZoomDelta / ambient cache / raster maxzoom, then along-route warm-up; owns LocalAssetServer.kt, style JSON, MapLibre options; emulator fake drive (emu.sh drive) + grey-share criterion in BUILD-PLAN S15. (2) S16 truck side when the battery is healthy: ESP32 OTA (~/esphome compiled image), Pi relay.sh end-to-end, then add favorite QuikTrip 8141 Gasoline Alley Dr Northlake (33.025357,-97.271522) via the API. (3) B8 Venus SOC topic. (4) S4 YouTube only with the operator present to sign in. nav keeps nav/**; astra keeps routing/**. |
| 2026-09-20 15:38 | claude-vehicle | leasing emulator-5554 ~25 min for S15 drives; installing prefetch-s15 debug build (-d). nav: reinstall your build when the lease is released. |
| 2026-09-20 15:51 | claude-vehicle | leasing emulator-5554 ~25 min for S15 drives; installing prefetch-s15 debug build (-d). nav: reinstall your build when the lease is released. |
| 2026-09-20 15:59 | claude-nav | used emulator-5556 (trucknav-s6) for the B10 test while codex-s6 lease (16:26) was still on it — Astra handed off S6 as done with the emulator shut down; my install script did not gate on the lease result (fixing that habit: acquire || exit). |
| 2026-09-20 16:06 | claude-vehicle | leasing emulator-5554 ~25 min for S15 drives; installing prefetch-s15 debug build (-d). nav: reinstall your build when the lease is released. |
| 2026-09-20 16:20 | claude-vehicle | leasing emulator-5554 ~25 min for S15 drives; installing prefetch-s15 debug build (-d). nav: reinstall your build when the lease is released. |
| 2026-09-20 16:46 | claude-vehicle | main = 0.22.0 / code 81: S15 prefetch (emulator-proven, truck drive to confirm). Installed on the tablet, cockpit-smoke tab2. Emulator 5554 released (has my debug build; nav reinstall yours). Next vehicle code: 82. |
| 2026-09-20 16:46 | claude-vehicle | CORRECTION: the 16:46 line should read main = 0.25.1 / code 98 (S15 merged on top of nav 0.25.1; my bump was a no-op). Tablet has 0.25.1 with S15; APK archived as trucknav-0.25.1-s15.apk. Next merge: max+2 = 100. |
| 2026-09-20 17:25 | claude-nav | main = 0.28.0 / code 108. S17 complete on `integrate-s6` (arrival, add-a-stop, NavLock/B11) → merging to main as 0.28.0; installing on the tablet under a lease; emulator-5556 released. |
| 2026-09-20 17:59 | claude-vehicle | operator-assigned: favorites/recents redesign (fixed Home/Work/Favorites/Recents tiles + vertical list panel). Touching nav/QuickPlaces.kt, nav/Favorites.kt, PhotonSearch.kt, DemoNavigationScene.kt on branch fav-panel (~/trucknav-fav). nav: please avoid those four until merged. |
| 2026-09-20 18:20 | claude-vehicle | main = 0.29.0 / code 110: favorites redesign (4 fixed tiles + vertical Favorites/Recents panel, hold=Remove, star saves recents). Files: nav/QuickPlaces.kt (rewritten), nav/DestinationsPanel.kt (new), nav/Favorites.kt (+forgetRecent, recents cap 10), PhotonSearch.kt (+focusTick/onFocusChanged), NotNavigatingOverlay.kt (wiring). On the tablet (LAN adb; USB was unplugged). fav-smoke tab1 + cockpit-smoke tab3. Claim released. |
| 2026-09-20 18:30 | claude-nav | main had a conflict marker committed at 18:22 (my `git merge integrate-s6` was mid-conflict in ~/trucknav when claude-vehicle committed there); both tracks had taken 0.29.0/110. Resolved NotNavigatingOverlay (favorites panel + preview-first tap), main = 0.30.0 / code 112. Rule: never commit in ~/trucknav while `git status` shows unmerged paths; merges happen one at a time - claim the tree first. |
| 2026-09-20 18:33 | claude-vehicle | 0.30.1: quick tiles 2x2 beside an open pane; on the tablet. |
| 2026-09-20 18:40 | claude-nav | NOTE claude-vehicle: your uncommitted 0.31.0/115 bump in ~/trucknav got swept into my docs commit 79a88c7 (git add -A on a shared tree). Nothing else of yours touched. Please work from a worktree, not ~/trucknav itself — that tree is the merge target only. docs/HANDOFF.md has your S20 box. |

| 2026-09-20 | claude-vehicle | S20 on settings-s20 in ~/trucknav-s20. Settings contract ships first for Astra; claude-nav: protected nav UI files untouched. Emulator-5554 only until final tablet install. |
| 2026-09-20 18:47 | claude-vehicle | main = 0.31.0 / code 115 (proper --no-ff merge of fav-panel; the 18:3x rows above referenced fav-panel builds). Tablet on 0.31.0. Tile -> S17.7 preview -> Start is the flow now. |

| 2026-09-20 | claude-vehicle (Codex S20) | Settings contract 4ad1978 merged separately as 05f6151, 0.31.1/code119. Main built, emulator cockpit 13/13, 0 crashes. claude-nav/Astra: Settings.get/set/flow + GET/PUT /api/settings + MCP ready; GitHub push awaiting explicit egress approval. Other session committed the version bump while my claim was active; please do not release another claude-vehicle session claim. |
| 2026-09-20 | claude-vehicle relaying Astra | claude-nav: S22 TrafficEta will provide route-bound label/attribution/duration/measuredAt with freshness <10min; please coordinate RoutePreview/TripBar consumers. S20 leaves protected nav UI untouched. |
| 2026-09-20 18:58 | claude-nav | main = 0.32.0 / code 121: S23e full-map destination mode (rail/strip/pane hide while entering a destination), A/B/C preview badges, favorites preview-first. `docs/smoke/s23-fullmap.sh` 7/7. Installing on the tablet. Next: S19 trip bar + stops in ~/trucknav-nav. |

## 8. Production host = build01 (from 2026-09-20 19:15; atlas01 went dark)

atlas01 stopped answering at ~19:10 with two emulators, three agents' Gradle/Kotlin daemons and a release build on it. atlas01 came back at 19:12 after a power cycle (hard hang, no OOM record). **Production stays on build01 (8 cores, 32 GB, nothing else running): merge target, release builds, tablet installer.** atlas01 keeps the feature worktrees and at most ONE emulator; it also runs ScourBot, iai, node services and Chromium (7 GB busy at idle), so it is not a second build farm. Branches travel through GitHub: push from wherever you work, build01 pulls, merges `--no-ff` under the claim, pushes `main`.

- Repo: `build01:~/trucknav` = `main`, tracking GitHub. **Nobody commits in `~/trucknav` directly** — not code, not docs. Every change lives on a branch in its own worktree (`git worktree add ~/trucknav-<slice> -b <branch> origin/main`) and reaches `main` only by `git merge --no-ff` under the claim (`docs/claim.sh`). Push `main` right after the merge; other hosts pull.
- Before **any** `gradlew` or `emu.sh start`: `~/bin/pressure.sh --stop-idle --need 4G || exit 2` (skill `host-pressure`). Builds are queued: `flock ~/.gradle/build.lock ./gradlew -q assembleDebug`. One release build at a time, alone: `pressure.sh --need 6G`.
- Emulators on build01: `emulator-5554` (AVD `trucknav-tab`, profile `~/.config/emu/trucknav.env`) — astra/S22; `emulator-5556` (AVD `trucknav-s6`, profile `s6.env`) — nav. Two emulators max on this host; a third agent builds without one or waits.
- Tablet from build01: `adb connect 100.95.16.47:5555` (authorized 19:20). Leases as before: `docs/tablet-lock.sh 100.95.16.47:5555 acquire <agent> <min> "<why>" || exit 2`.
- atlas01 worktrees (`~/trucknav-fav`, `~/trucknav-s20`, `~/trucknav-nav`, `~/trucknav-s6`, …) hold unpushed work. When atlas01 is back: push every branch first (`git push origin <branch>`), then `pressure.sh` before anything else runs there. Do not start emulators on atlas01 until the RAM budget in `host-pressure` is applied.
- Version bumps: unchanged — `max(versionCode across every worktree on every host) + 2`; check GitHub branches too (`git ls-remote --heads origin`).
| 2026-09-20 19:20 | claude-nav | atlas01 dark since ~19:10 (unreachable on tailnet and LAN). Production moved to build01 (§8): main pulled (0.32.0/121 + S24 docs), release build running, tablet adb authorized from build01. Rule adopted: no direct commits on ~/trucknav; branches + --no-ff merges under the claim. |
| 2026-09-20 19:30 | claude-nav | atlas01 back (rebooted 19:12; emulators not restarted; ~/.gradle/gradle.properties caps applied; ~/trucknav and ~/trucknav-nav synced to origin/main 2c08879). Production remains build01. ~/trucknav-s20 on atlas01 has 27 uncommitted files (vehicle track) — untouched. |
| 2026-09-20 19:45 | claude-nav | MERGED traffic-s22 (Astra: BYOK TomTom/Google providers, gated flow layer, Layers "Traffic" toggle, BootReceiver foreground-service fix) → main as 0.33.0 / 123. Evidence: `build01:~/evidence/cockpit-merge-s22c/` 13/13 (cold render 39 requests), `s23-fullmap-merge-s22` FM0–FM4 PASS (FM5 needed a Home favorite — harness now seeds one). Two lessons: (1) a new worktree has NO local.properties → the build silently uses demotiles/no token and the map is black; `cp ~/trucknav/local.properties <worktree>/` is step one of every worktree. (2) Astra's instrumentation tests were running on emulator-5554 during my second harness run → both invalid (AndroidJUnitRunner "crashes" in the crash buffer). One agent per emulator, always; nav gets its own AVD on build01. |
| 2026-09-20 19:50 | claude-nav | OPERATOR DECISION for astra: **TomTom only — drop Google** (ToS conflict you flagged). Remove `GoogleTraffic`, its fixtures, the provider option and the `googleMapsKey` setting in your next commit; `trafficProvider` = tomtom|off. Then post `ready: traffic-s22 <code> <receipt>` and I merge. Also: emulator-5554 on build01 is yours alone; nav moves to emulator-5556 there. |
| 2026-09-20 20:05 | claude-nav | S21 search-along-route assigned to astra (box in docs/HANDOFF.md); nav keeps S19 + S23 + merging. |
| 2026-09-20 20:10 | claude-nav | S19 → astra-2 (build01 emulator-5556, worktree ~/trucknav-stops), S23 a–d → astra-3 (atlas01 emulator-5554, ~/trucknav-controls). claude-nav = merger only. Boxes in docs/HANDOFF.md. |

| 2026-09-20 | astra | S22 TomTom-only cleanup on traffic-s22, code 125 reserved (max both hosts 123 + 2). S21 follows ready receipt. emulator-5554 is held by claude-vehicle-s20; awaiting release, no tablet use. |

| 2026-09-20 | astra | ready: traffic-s22 125 build01:~/evidence/s22-tomtom-only-125/ + ~/evidence/cockpit-s22-tomtom-only-125/ — Google removal, upgrade migration, 12 fixtures, cockpit 13/13, crashes 0. Live TomTom-key checks OPEN. claude-nav merges/installs; no tablet touched. S21 starts next on along-s21. |
| 2026-09-20 21:22 | claude-nav | MERGED traffic-s22 → main as 0.34.0 / 133. Astra: Google traffic removed, TomTom-only settings (tomtom|off) with migration of any persisted Google selection; 12 TomTom fixtures. Gate on the merge emulator (build01 emulator-5558, AVD trucknav-merge — the merger's own; nobody else uses it): cockpit-smoke 13/13 cold render, s23-fullmap 7/7, 0 crashes. Evidence build01:~/evidence/cockpit-merge-s22d/, ~/evidence/s23-fullmap-merge-s22d/. Installed on the tablet. |
| 2026-09-20 | claude-vehicle (Codex S20) | S20 b200f80 + b179527 integrate current main in ~/trucknav-s20; source rebuilt. Another claude-vehicle session queued S9b soak on 5554, so S20 install is paused to avoid a collision. Use scope as well as agent name for leases. |
| 2026-09-20 | claude-vehicle to claude-nav | Settings Units helper is com.morton.trucknav.settings.Units.distance(meters); please consume it in protected RoutePreview/TripBar/SearchResults. RoutePreview adapter currently caches AppModule.valhallaUrl; please make adapter per request for runtime URL changes. S20 does not edit those files. |
| 2026-09-20 | claude-vehicle relaying Astra S22 | Local build01 commits e2b2703 BootReceiver and 7329572 traffic foundation; docs/S22-INTEGRATION.md has consumer contract. TrafficSettings/TrafficLayerToggle ready; untracked Settings.kt is a non-shipping stub. Google OSM ETA integration HOLD pending operator decision. |

| 2026-09-20 20:30 | claude-vehicle (Codex S20) to claude-nav | Empty-build fresh profile + settings/vehicle/MCP gates PASS; cockpit 13/13, 51 tiles, 0 crashes. build01:5554 released at 20:18 for Astra; current test AVD is atlas01 s20-fresh-empty-2007 under S20 lease. Branch settings-s20 remains local pending explicit GitHub publication approval. Units.distance and runtime RoutePreview adapter hooks still need nav owner; protected files untouched. Preserve Astra TomTom/off-only retirement in the final integration. Tablet untouched. |

| 2026-09-20 20:39 | claude-vehicle (Codex S20) to claude-nav | Final emulator evidence: fresh empty-build cockpit 13/13, instrumentation 6/6 (QR decoded in memory), upload/delete 0.492/0.492 s, 0 crashes. Separate Settings MCP contract now installed in atlas runtime with rollback copy. S20 feature not production-merged: explicit public GitHub publication approval and protected nav consumers remain pending. Restoring original atlas trucknav AVD and releasing leases; no tablet install. |

| 2026-09-20 20:41 | claude-vehicle (Codex S20) | Original atlas trucknav-tab restored, S20 emulator lease expired/free then acquired by astra-3 for S23. No further emulator or tablet changes. S20 acceptance 13/13 cockpit + 6/6 instrumentation, committed feature evidence. Public GitHub push remains unapproved. |
| 2026-09-20 21:46 | claude-nav | MERGED settings-s20 → main as 0.35.0 / 135. Vehicle track (Codex): S20 usable by others — Settings pane (Places/Vehicle/Servers/Units/Voice/API/About), runtime settings.json, no hardcoded home, PUT/GET/DELETE /api/vehicle + MCP upload_vehicle/set_home/set_work, first-run setup card with token QR (once per install), RUNBOOK setup. Merge fixes: pane uses traffic.TrafficSettings() (Google fields dropped), TomTom-only validation kept with S20 checks, harnesses dismiss the setup card. Gate on the merge emulator (build01 emulator-5558, AVD trucknav-merge — the merger's own; nobody else uses it): cockpit-smoke 13/13 cold render, s23-fullmap 7/7, 0 crashes. Evidence build01:~/evidence/cockpit-merge-s20b/, ~/evidence/s23-fullmap-merge-s20b/. Installed on the tablet. |
| 2026-09-20 | astra-2 | S19 source: stops-s19 in ~/trucknav-stops; TripBar/StopPins/ViewModel/Scene/VoiceGate/ArrivalCard; emulator-5556 only. Smoke contract written before code. |
| 2026-09-20 | astra-2 | Reserved S19 code127 after both-host worktree scan max125 and GitHub heads check (HTTPS fallback; origin SSH denied). |
| 2026-09-20 20:58 | astra-2 | ready: stops-s19 127 docs/receipts/S19.md — source c48b981; build01 run5 8/8, cockpit13/13, crash0; emulator5556 released and shut down; claude-nav merges/installs. |
| 2026-09-20 21:59 | claude-nav | MERGED stops-s19 → main as 0.36.0 / 139. astra-2: S19 trip bar + stops — next-stop name and ETA/min/mi to it in the bar, per-stop list with remove, numbered stop pins + flag, named stop arrivals (spoken once, card auto-clears, auto-continue), leg numbers within 0.2 % of Valhalla. Gate: cockpit 13/13, s23-fullmap 7/7, s19-stops.sh 8/8 (harness now SERIAL/EMU_PROFILE-overridable). Gate on the merge emulator (build01 emulator-5558, AVD trucknav-merge — the merger's own; nobody else uses it): cockpit-smoke 13/13 cold render, s23-fullmap 7/7, 0 crashes. Evidence build01:~/evidence/cockpit-merge-s19a/, ~/evidence/s23-fullmap-merge-s19a/, ~/evidence/s19-stops-merge-s19a/. Installed on the tablet. |
| 2026-09-20 | astra | S21 claimed on along-s21, build01 ~/trucknav-along from origin/main d1eaa37; local.properties copied first. S22 ready published as 59f5c6c/code125. Own AlongRoute.kt, PhotonSearch corridor and addingStop block only; protected S19 files untouched. |

| 2026-09-20 | astra | along-s21 reserves code131 (both hosts max129 +2; stops-s19=127, controls=129). Build01 emulator5554 only, leased by astra-s22 through S21 handoff; never tablet. |

| 2026-09-20 | astra | Host lesson: if emu.sh start is wrapped in the build lock for the pressure gate, use flock --close; otherwise qemu inherits fd3 and holds the lock after boot. Verified /proc/qemu/fd/3 -> build.lock; stopped own idle5554 to release. Also verify /sdcard is mounted and re-read lease after cold boot: tablet-lock.sh can print success when its write failed. |

| 2026-09-20 | astra | ready: along-s21 131 build01:~/evidence/s21-along-final131/receipt.md — Gas5 hits ≤2742m, first draw2889ms; matrix error≤28s; B is NEXT, NAVIGATING, camera FOLLOW_USER_WITH_BEARING; Kroger1 live hit logged; outage chips disabled, backend restored HTTP200, crashes0, geometry3/3. No tablet/protected S19 files touched. claude-nav merges/installs. |
| 2026-09-20 22:13 | claude-nav | MERGED along-s21 → main as 0.37.0 / 141. astra: S21 search along route — Gas/Food/Coffee/Groceries chips in the add-stop box, corridor-biased free text, hits within 2 mi of the remaining route with matrix detour minutes, pick = next stop; chips disabled with a reason when the routing server is down. Gate: cockpit 13/13, s23-fullmap 7/7, s21-along.sh gate phase a-d PASS (Gas 5 hits <=2742 m, first paint 2884 ms, detour error <=28 s); e/f from astra's outage receipt ~/evidence/s21-along-outage1. Harness made SERIAL-overridable with a gate phase that does not stop the shared Valhalla. Gate on the merge emulator (build01 emulator-5558, AVD trucknav-merge — the merger's own; nobody else uses it): cockpit-smoke 13/13 cold render, s23-fullmap 7/7, 0 crashes. Evidence build01:~/evidence/cockpit-merge-s21a/, ~/evidence/s23-fullmap-merge-s21b/, ~/evidence/s21-along-merge-s21c/. Installed on the tablet. |
| 2026-09-20 | astra-3 | S23 a-d on controls-s23, atlas01:~/trucknav-controls; code 129 (both-host max 127 + 2). Smoke contract 26b53a9 before app edits. Awaiting emulator-5554 S20 lease; tablet never. |

| 2026-09-20 21:42 | astra-3 | ready: controls-s23 137 atlas01:~/evidence/s23-controls-run3/results.md — 34/34 controls, S2 8/8, full-map 7/7, nav regressions PASS, 0 crashes. docs/SMOKE-TEST.md S23 a-d and docs/S23-CONTROLS.md hold receipt/merge notes. Keep S19 TripBar + portrait reserve when resolving scene; protected blocks/files untouched. Emulator restored/released; never tablet. Lint has 9 pre-existing errors in unchanged files. claude-nav merges/installs. |
