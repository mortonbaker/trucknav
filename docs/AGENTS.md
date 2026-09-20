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
