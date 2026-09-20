# TruckNav handoff — verified 2026-09-20

## Where the work actually is

The active Claude Code session runs on **STUDIO_PC** and executes the tablet work over SSH on **atlas01**. This is TruckNav, not the Primer/kids-tablet project.

- SSH from Studio_PC: `ssh morton@atlas01` (existing authentication works).
- Source: `/home/morton/trucknav` on Atlas. **Not a Git repository** at verification; there is no commit SHA to use as a build identifier. Do not run git reset/checkout or assume a remote backup exists.
- Tablet: Samsung Galaxy Tab A7 Lite, model `SM-T220`, ADB serial `100.95.16.47:5555`. `192.168.0.187:5555` was another visible transport for that model. Use the documented tailnet serial and recheck identity.
- Package: `com.morton.trucknav`; activity `com.morton.trucknav/.MainActivity`.
- Installed version independently read with `dumpsys package`: **0.12.2, versionCode 30**. Source `app/build.gradle` matches. Recheck on takeover because Claude may continue working.
- Existing UI driver: `/home/morton/bin/ui.sh`.

## Read these live files first

1. `/home/morton/trucknav/docs/BUILD-PLAN.md` — per-slice goals and falsifiable done; S0–S3 marked done; S4–S10 pending.
2. `/home/morton/trucknav/docs/SMOKE-TEST.md` — original surfaces and steps plus dated results through 0.12.2. The opening 0.6.0 section is historical; latest version sections override historical implementation descriptions, not unfulfilled criteria.
3. `/home/morton/trucknav/docs/BOOKS-PLAYER-PLAN.md` — D1–D11 player criteria and results.
4. `/home/morton/trucknav/docs/AGENT-HANDOFF.md` — this takeover audit, installed separately beside the live docs.

The original Claude session is `C:/Users/morto/.claude/projects/C--Users-morto/c4b30a09-ef3c-4ac7-b314-8d9c24de00f2.jsonl`; inspect it only if these files leave a concrete question unanswered. The last observed response was 2026-09-20 14:58:25Z, closing S3 and the search-field fix.

## Existing raw materials

Run from Atlas after inspecting script state assumptions. These scripts change the tablet UI, may stop the app or rotate it, and some clear logs; do not run them during the operator's hands-on test without coordination.

| Files under `/home/morton/trucknav/docs/` | Purpose |
|---|---|
| `books-smoke.sh`, `d9.sh` | Books/player and audio-focus sequences |
| `s2-camera.sh`, `puck.py` | Historical S2 camera measurement; blue-dot detector is obsolete after vehicle puck 0.11.1 |
| `s3-styles.sh <unique-tag>`, `mapstat.py` | Five styles, persistence, mid-route switch; screenshots in Atlas home |
| `search-smoke.sh <unique-tag>`, `contrast.py` | Search opacity, measured contrast, keyboard/clear/result flow |
| `mkstyles.py` | Generates map styles; not a test and not needed for takeover |

Existing evidence: `/home/morton/s0-*.png`, `s1-land.png`, `s1-port.png`, `s3-a-*.png`, `s3-terrain-scott.png`, `search-c-*.png`, `puck-browse.png`, `puck-nav.png`. Actual S3 and search PNG files were found during this audit. Keep old evidence; use unique run names.

## What is and is not established

The prior run reports: S0 books/audio-focus/layout; S1 immersive/status strip; S2 camera centering; S3 five styles/persistence/mid-route behavior; search text contrast 18.5:1 on Light/Satellite/Dark; zero TruckNav crashes. These are **prior recorded results**, not a fresh end-to-end rerun by this handoff audit.

Open items and caveats:

- S9 `docs/cockpit-smoke.sh` and `docs/RUNBOOK.md` do not exist yet. Existing shell scripts print measurements but do not consistently assert criteria or fail closed. Exit status alone cannot serve as acceptance. Do not call the full one-command harness complete.
- The 30-minute soak is explicitly pending.
- `puck.py` measures a blue dot; the current puck is the vehicle image. Adapt detection or inspect/measure the new image before repeating S2.
- S3 offline evidence explicitly records satellite cache and switching to Light. The full original offline criterion also names Dark and Terrain/uncached regions; those portions are not separately evidenced in the recorded table. Keep them unverified until exercised.
- Search result-row evidence says roughly 69 dp visually, with a claimed 72 dp clickable minimum. Measure actual clickable bounds/density before treating that size criterion as proven. The design-source 76 dp recommendation is not the same as the chosen 64/72 dp acceptance thresholds; do not claim compliance with the stricter recommendation.
- `s3-styles.sh` checks an exact road name (Oak Knoll Road), so another starting location can cause a harness false failure. `mapstat.py` uses fixed default bounds and cannot by itself distinguish Light from Terrain or Satellite from Hybrid. Inspect actual labels/relief and use current bounds.
- S4 YouTube needs the operator's sign-in. S5 offline books and S6 offline routing are pending. Do not claim fully offline routing because the basemap works offline.

Next: record the operator's hands-on findings against the matching criteria, capture the observed build and screen, then fix/retest only that scope. No implementation slice was started by this documentation task.

## Build/install references (only when a change is authorized)

The live build plan is authoritative:

```bash
cd ~/trucknav
export ANDROID_HOME="$HOME/Android/Sdk"
ANDROID_SDK_ROOT="$ANDROID_HOME" ./gradlew -q assembleRelease --no-daemon
adb -s 100.95.16.47:5555 install -r app/build/outputs/apk/release/app-release.apk
adb -s 100.95.16.47:5555 shell cmd package set-home-activity com.morton.trucknav/.MainActivity
```

Increment versionCode/versionName per the build plan; archive the APK per its routine. Installs reset default HOME, so restoring HOME is part of verification. Secrets are in documented `local.properties` and signing-property files; do not print or copy their contents into receipts.

Never `pm clear`: it wipes the offline map. Network-outage tests require an on-device self-restoring timer before cutting connectivity. Do not reboot, change Wi-Fi, install, or drive screens merely to prepare a handoff.

The operator's supplied 2026-09-20 handoff additionally confirms: start playback toggles from silence; parse `state=PLAYING(3)` inside `PlaybackState`; release builds deny `run-as`, so verify persistence by pixels; patch via scp, not SSH heredocs. These rules are preserved in the skill entrypoint. The requested reusable `ui.sh`, `puck.py`, `contrast.py`, `mapstat.py` are bundled under its `scripts/` directory; the original per-slice harnesses remain in TruckNav's `docs/`.

## Skill availability

Canonical skill: `agent-stack/shared/skills/tablet-smoke-test/` on each host. User-level links in `.claude/skills` make it available to Claude; `.agents/skills` makes it available to Codex and current Pi (verified against installed Pi documentation). A running agent may need to explicitly read `SKILL.md` or reload its skill catalog. Astra uses the Codex skill; it does not need a separate model-specific copy.

Sources for loader behavior: [Codex skills](https://learn.chatgpt.com/docs/build-skills); Atlas's installed `@earendil-works/pi-coding-agent/docs/skills.md`. No model invocation was needed to validate the file paths.
