# Contributing to TruckNav

This project is built by one operator and several AI coding agents working in parallel on a
real vehicle. Humans and agents follow the same rules.

## 1. Where the work is defined

- `docs/BUILD-PLAN.md` — slices (S0…). Each has a goal, the concrete changes, and **done-criteria
  that a script can falsify**. The status table at the top is the order of work.
- `docs/NAV-AUDIT.md` — the navigation feature audit against in-car guidelines, with priorities.
- `docs/BUGS.md` — field reports with evidence and done-criteria.
- `docs/SMOKE-TEST.md` — surfaces, tests, and dated results per version.
- `docs/AGENTS.md` — the concurrency protocol: tree claim (`docs/claim.sh`) and tablet lease
  (`docs/tablet-lock.sh`). Check both before you edit, build, or touch the device.

Pick a slice or a bug. If you want to add something new, add it as a slice with done-criteria
first; a feature without a falsifiable "done" is not ready to be built.

## 2. Rules that are not negotiable

- **No secrets in the tree.** Credentials, tokens, keystores, tailnet hostnames you would not
  post publicly, and the operator's home coordinates live in `local.properties` (ignored) and
  are read through `BuildConfig`. `local.properties.example` documents the keys.
- **Material icons only.** No emoji anywhere in the UI.
- **Driving rules.** Text ≥ 24 sp for anything the driver reads at a glance, targets ≥ 64 dp
  (76 dp preferred), contrast ≥ 4.5:1, light-on-dark at night, one tap per action.
- **Only one navigator.** TruckNav owns navigation on the device; foreign navigators are stopped.
- **Never `pm clear`** the app on a device (it deletes the basemap). Every `adb install -r`
  resets the home app; follow with `cmd package set-home-activity`.
- **Network cuts are self-restoring** and started from the device, never from outside.
- **Evidence before claims.** A change is done when the done-criteria are measured on the
  device and the numbers are in `docs/SMOKE-TEST.md`. "It worked for me" is not a result.
- **Fix, don't replace.** When a harness script fails, fix that script; do not spawn a second.
- Never rewrite git history that has been pushed. Commit after every build that reaches a device.

## 3. Code style

Match the surrounding code: Kotlin + Jetpack Compose, small composables, comments that say
why (not what), one dark palette (`#10141a` rail, `#1a2028` cards, `#1f5f8b` accent,
`#9aa4b2` muted text). Keep third-party UI (Ferrostar, MapLibre) at arm's length behind our
own composables so it can be swapped.

## 4. Pull requests

- One slice or one bug per PR. Title: `S<n>: <goal>` or `B<n>: <bug>`.
- The description lists the done-criteria and the measured results (numbers, screenshots
  described, log excerpts). Link the `SMOKE-TEST.md` section you appended.
- Bump `versionCode` (+1) and `versionName` per the plan.
- Do not include APKs, evidence PNGs, or build output.

## 5. Hardware you need to reproduce

Any Android 10+ tablet works for the app; the map needs a Protomaps extract and style files
(`docs/mkstyles.py` builds the satellite/hybrid/terrain variants from the light style),
routing needs a Valhalla instance, audiobooks need an Audiobookshelf server, the power pane
needs a Venus OS device on the network, the vehicle pane needs an ESPHome relay board. Each
is optional: the app degrades to `--` / "not reachable" without it.
