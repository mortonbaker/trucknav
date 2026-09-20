# S7 control contract and acceptance ledger

Baseline: commit b6d3c0403525b5c3d5f9d98e259975b238fec3b4 (S5 code included), installed version to be recorded by the smoke run. Owner: codex-s7. Scope: presentation only. This inventory was written before source edits.

Every row below defines BOTH preserved behavior and the acceptance test. Source inspection establishes intent; runtime results must be recorded separately. No existing failure is silently promoted to the contract. All deadlines below are S7 regression budgets, not retrospective claims about the baseline.

| ID | Control / preconditions | Action and required outcome | Evidence / method |
|---|---|---|---|
| R1 | Map/Music/Books/YouTube/Power/Vehicle/Apps rail | Tap selects the corresponding pane; Map hides the side pane; selection survives rotation. All seven reachable in 3 s. | UI XML + PNG in both orientations |
| R2 | Now-playing thumbnail; active media session | Opens Books for our/ABS package, YouTube for NewPipe, Music otherwise. No playback toggle. | handler preservation + actual active-source tap |
| A1 | Apps grid | Internal entries select matching cockpit pane; Settings opens Android Settings. HOME returns to TruckNav. | XML/foreground package |
| M1 | Music/Books Play; active playing session | Tap sends pause to that pane's session; tap again sends play. State changes within 5 s. | dumpsys media_session, controlled silent start |
| M2 | Books Play; no active session but last book exists | Reopens last book via playLast; correct title appears and playback begins within 5 s when prerequisite available. Music with no session remains no-op. | media_session + server availability |
| M3 | Music Previous/Next; queue exists | Sends previous/next only to Music session; track changes where queue permits. | handler preservation + media_session |
| M4 | Books Back/Forward 30s; loaded book | Seeks -/+30000 ms, clamped to valid timeline. Test paused away from boundaries; tolerance 2 s. | before/after media_session |
| M5 | Library | Opens the pane's own browser without selecting another media source. | XML |
| M6 | Book speed | Cycles existing Speed.next ladder and calls setSpeed; preference preserved. No change to ladder. | source + media_session/persisted UI |
| M7 | Media progress | Display only; advances during playback, holds paused. No newly introduced seeking behavior. | timed media_session + PNG |
| L1 | Music browser tabs/folders/playable rows | Tab chooses source; folder browses; playable row starts item and returns to player. Back pops folder, then returns to player. | source + UI |
| L2 | Books Continue/Library, Back | Tabs select corresponding list; Back returns to player. Loading, empty and server-unreachable messages remain visible. | XML + deterministic source state coverage |
| L3 | Book cover | Starts selected book and returns to player. Download badge intercepts its own tap without starting playback. | source + media_session |
| D1 | Download or failed/retry state | Starts BookDownloads.start for current book; downloading status disabled, progress displayed; no duplicate action from disabled indicator. | S5 harness + source handlers |
| D2 | Download complete | Downloaded indicator disabled; Delete download invokes delete for current book; does not delete streaming library entry. | S5 harness; use its disposable fixture only |
| D3 | Download badge | Missing/failed: starts download; downloading: progress; done: checkmark, no delete action. | source + S5 fixture |
| Y1 | YouTube Back/Home/Subscriptions/History | Back navigates WebView history if available; destinations retain original URLs. Existing session/cookies unchanged. | handler preservation; signed-in checks BLOCKED if no session |
| P1 | Power/Vehicle unavailable or busy | Preserve existing error/state strings; busy blocks toggling. Existing unreachable behavior is NOT redefined by S7. | source + no-network rendering; do not toggle real hardware |
| P2 | Starlink strip/Power row | Existing tap sends exactly one toggle(STARLINK); Switch sends set(STARLINK, checked). | preserved handler fingerprint; physical test deferred to S16 |
| P3 | Vehicle relay tile | Named switch list, current ON/OFF, same toggle(id), busy disabled; no renaming/filtering changes. | preserved handler fingerprint; no physical relay actions |
| N1 | Map style button and sheet | Opens sheet; each option persists selected style and dismisses; Back/scrim dismiss without selecting. | XML + selected style/reopen PNG |
| N2 | Search field/clear/result | Input/search behavior unchanged; clear clears results/keyboard; result selects destination and opens preview. | existing search smoke + XML |
| N3 | Recenter and map gestures | Recenter follows current location; pan/zoom and long-press dropped pin remain unchanged. | source boundary + smoke |
| N4 | Destination Start/Close | Calls existing start/close exactly once; Close removes sheet, no route start. | source handler preservation + XML/log |
| N5 | End/mute/overview while navigating | Existing Ferrostar callbacks unchanged; post-merge S17 expectations take precedence. No new navigation behavior in S7. | navigation smoke/log when safe and lease available |
| O1 | Foreign-app overlay dock/bar | HOME returns to cockpit; shortcuts/media controls unchanged; drag positions persist; hidden over TruckNav. | unchanged-file check + Settings transition (Android may hide overlays) |

## Baseline findings and limitations

- Tablet leased to claude-studio for S5; no baseline taps, playback changes or log clears performed during source inventory. Physical baseline execution pending lease; source intent is not a runtime PASS.
- Power row contains a nested switch; overlapping ancestor/descendant semantics are intentional event dispatch, not two sibling targets. Test one action per tap if changed; S7 preserves the handlers.
- Books cover contains a download badge. Preserve nested hit routing; do not count its overlap as a sibling collision.
- Some control states require authenticated services or a loaded book; missing prerequisites are BLOCKED, never PASS.
- Physical relay behavior remains an S16 hardware check. S7 must not power-cycle the truck to test colours.
- The native theme is wallpaper/system dependent and launcher artwork is the Android sample; these are S7's intended visual changes.

## Appearance acceptance

V1: seven panes x portrait/landscape x font scale 1.0/1.3; PNG/XML evidence, no clipped actions or overlapping sibling controls. V2: enabled text/essential icons >=4.5:1 over actual background. V3: app-owned tap targets >=48x48dp; Play 80dp and transport 56dp retained. V4: compact 13-14sp noninteractive status text is the explicit exception; titles 24sp/body16sp. V5: correct adaptive icon in Settings, Recents and HOME selector. V6: no new crash, correct build installed, orientation/font/playback restored. Basemap, artwork and web content excluded from palette assertions, not from interaction checks.

## Result rules

Each receipt records ID, precondition, expected, actual, PASS/FAIL/BLOCKED/NOT RUN, revision/version, and evidence path. A generated PNG alone is not a pass. A source handler match is regression evidence, not a replacement for required end-to-end checks. Any criterion change requires an explicit reason; do not weaken thresholds to make a run pass.
