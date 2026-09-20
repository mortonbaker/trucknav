# S7 implementation receipt — 2026-09-20

Status: implemented on `ui-s7`; NOT ACCEPTED / NOT INSTALLED. Atlas worktree: `/home/morton/trucknav-s7`. Source revision `fb7644e537aa798613e4afe800ffd1f651c2bee1`, including main `a09515f` (0.15.1). Feature branch retains main's version, as required; its APK must not be installed directly.

## Changes

Explicit dark Compose and native themes, named cockpit palette, typography roles, adaptive navigation icon including monochrome support. Rail uses equal-width portrait slots and scrollable landscape content; media content scrolls with capped artwork. Search-clear, destination actions, speed/download and Starlink controls receive minimum target sizing. Vehicle titles/status and media titles are enlarged. Power telemetry can scroll horizontally in narrow layouts. Control callbacks and persistence logic are unchanged.

The control inventory was committed before source changes (`e527edc`). It is the behavioral contract for acceptance: [S7-CONTROLS.md](S7-CONTROLS.md). [SLICE-WORKFLOW.md](SLICE-WORKFLOW.md) documents the full lifecycle and is also distributed with the shared tablet smoke skill on Studio PC and Atlas for Claude, Codex/Astra and Pi.

## Verification

| Gate | Actual result | Status |
|---|---|---|
| Release compilation/package | `assembleRelease` succeeded after integrating main | PASS |
| Existing unit-test task | `testDebugUnitTest` succeeded; no test sources or test-result XML found, so this supplies no behavioral coverage | NO COVERAGE |
| Callback regression | 40 extracted callbacks/references, 36 Kotlin files, zero mismatches against main a09515f; `evidence/s7-source/callbacks.json` | PASS (source only) |
| Python syntax | Both smoke and callback scripts compile | PASS |
| Lease guard fixtures | Offline, wrong-owner and expired locks each prevented input mutation | PASS |
| Skill schema | shared skill validator succeeded | PASS |
| Lint | Eight errors in unchanged MainActivity, StatusStrip, BooksPlayerService and AndroidManifest; raw `evidence/s7-source/lint.xml` | FAIL (existing) |
| V1–V6 physical acceptance and affected behavioral regressions | No tablet mutation or S7 installation; physical baseline also pending | BLOCKED |

At 13:07 CDT, `claude-studio` held the tablet through 13:53:57 for S5 and held the main source claim. These are live coordination blockers, not permission requests or evidence of a product defect. The eight lint errors concern Back dispatch/super call, API-31 NetworkCallback with minSdk29, four Media3 opt-ins, and QUERY_ALL_PACKAGES. No lint suppression was added.

Candidate artifact: `app/build/outputs/apk/release/app-release.apk`; SHA-256 `7707781b6e24f88ccd9c2b5ec7369ae9e7516449df85f296e66447b46c1d1f7b`. Build command: `ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK_ROOT=$HOME/Android/Sdk ./gradlew -q assembleRelease testDebugUnitTest --no-daemon`.

## Resume

1. Read current `docs/AGENTS.md`, source claim and live device lease. Reconcile any newer S5/S17 changes. Confirm tablet is live; do not trust the existing lock script's free message when ADB fails.
2. When available, acquire tablet lease `codex-s7` and capture baseline on the installed main build, starting from a silent, non-navigating cockpit. Preserve actual current pane, orientation, font scale, logs, version and foreground app. Run `python3 docs/s7-smoke.py --serial 100.95.16.47:5555 --output ~/evidence/s7-baseline-<unique-tag> --restore-pane <observed-pane> --baseline`. The runner deliberately requires the observed pane; never guess it.
3. Acquire main source claim, merge `ui-s7`, allocate the next unused version on main, build and archive to the documented apk-drop location. Record hash and commit. Install only main, then restore HOME with `cmd package set-home-activity com.morton.trucknav/.MainActivity`.
4. Repeat the smoke matrix without `--baseline`. Inspect measured pixels for contrast, clipping and all reachable controls, including scroll regions; check launcher chooser/Recents icon. Execute affected controls from the contract with controlled fixtures; do not toggle physical relays for visual acceptance. The smoke runner covers visible target bounds and evidence collection, not all these acceptance checks.
5. Compare crashes within the recorded run boundary; verify state restoration. Resolve lint policy explicitly before release acceptance. Append results and only then mark S7 DONE if all required gates pass. On failure, preserve evidence and rollback through revert plus a higher-version main build; never clear app data or assume release downgrade works.

Remaining risk: actual rendering, enlarged-text reachability, nested hit routing and contrast have not yet been measured on hardware. Source matching does not prove these. The S7 worktree source claim is released at handoff; reacquire before edits. No external repository remote is configured, so commits remain on Atlas.
