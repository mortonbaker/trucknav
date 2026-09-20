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
