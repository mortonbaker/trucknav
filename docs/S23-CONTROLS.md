# S23 a-d: map control placement

Owner: astra-3. Branch: controls-s23. Host: atlas01. Device: emulator-5554 only.
Pre-code acceptance contract: 26b53a9, docs/smoke/s23-controls.sh.

## Control inventory

| Stable accessible name | State | Action preserved | Placement |
|---|---|---|---|
| Route Overview | navigating, including add-stop | CameraMode.OVERVIEW activates the existing fit effect | top-right 1 |
| Mute / Unmute | navigating | viewModel.toggleMute | top-right 2 |
| Map style | all | opens the existing MapStyleSheet | top-right 1 idle, 3 navigating |
| Add stop | navigating | toggles scene.addingStop | top-right 4 |
| Center on my location | all | navigationMapState.recenter(actual navigation state) | bottom-right 1 |
| Zoom in | all | navigationMapState.zoomIn | bottom-right 2 |
| Zoom out | all | navigationMapState.zoomOut | bottom-right 3 |

All map-action buttons are 56 dp; each group has 12 dp gaps and 16 dp corner
margins, identical in portrait and landscape. Information views reserve the
rightmost 84 dp (button + margin + gap). The bottom-left 120 dp remains available
for S19. The source of S19's TripBar, StopPins and ViewModel is untouched.

## Verified Ferrostar mechanism

Pinned dependency: com.stadiamaps.ferrostar ui-compose/ui-maplibre 0.56.0.
The sources jars on Maven Central were inspected before wiring.

- InnerGridView constrains each row to one third of its available height, so a
  four-button 260 dp stack cannot reliably fit its topEnd slot on this tablet.
- VisualNavigationViewConfig.showRecenter is not consulted by the 0.56.0
  cameraControlState extension. Disabling the flag alone leaves a second
  overview/recenter button.
- CornerNavigationView composes the public NavigationMapView and the original
  LandscapeNavigationOverlayView / PortraitNavigationOverlayView, with
  CameraControlState.Hidden and zoom/mute disabled. Their information
  measurements still populate the existing ClampedInsets instance.
- The adapter retains 0.56.0's bottom-clearance calculation and ornament padding;
  action-stack dimensions never feed camera padding. DemoNavigationScene's
  NavigationCameraOptions and ClampedInsets blocks remain byte-identical.
- NavigationViewComponentBuilder still owns instruction/progress/custom overlay
  wiring. The future S19 progress view must keep the portrait 84 dp end reserve.
- NotNavigatingOverlay's protected addingStop block remains byte-identical.
  Its caller supplies the information lane; PhotonSearch is untouched.
- DestinationSelectionBottomSheet is wrapped in the same reserved lane without
  editing its implementation. Scene map size and full-map destination mode are
  unchanged.

## Acceptance and regression

Run from the worktree, under its emulator lease:

    EXPECTED_CODE=<installed-code> PYTHON=<python-with-cv2-numpy-pillow> \
      docs/smoke/s23-controls.sh <unique-tag> astra-3

The geometry script records each control's clickable rectangle, information
rectangles, pixel density, screenshot, and dump in each state/orientation. It
fails for missing expected controls or missing state evidence. The gap/size
tolerance is half a dp for pixel rounding; corner tolerance is 1.1 dp.

S2's old harness was tablet-hardcoded and searched for a retired blue dot. The
updated docs/s2-camera.sh accepts SERIAL and measures the existing rendered truck
asset with masked template matching over the entire map (no expected-position
prior). Original position thresholds remain: browse center +/-5%; navigating
lower third; both orientations with Map and Music pane states. Match errors,
annotated detections and raw screenshots are retained. The isolated vision
environment is only a host tool, not an app dependency.

Harness self-checks proved that an overlap, 48 dp button, missing state, and
off-center truck fail, while the valid geometry and centered truck pass.
Runtime evidence and final status are recorded in SMOKE-TEST.md, not inferred
from these fixtures.

## Build checks

Debug assembly passed. Android lint reported nine errors, each in an unchanged
file: MissingSuperCall and GestureBackNavigation (MainActivity), NewApi
(ApiServer and StatusStrip), four UnsafeOptInUsageError (BooksPlayerService),
and QueryAllPackagesPermission (AndroidManifest). No lint baseline was added and
no unrelated source was changed.

## Merge notes

Only claude-nav merges/installs. Resolve the shared DemoNavigationScene builder
with S19 by retaining its TripBar invocation and this slice's portrait end
reserve, not by restoring the default TripProgressView. Leave S21's addingStop
content intact; the information-lane modifier must continue to reach it.

Runtime acceptance pending the shared emulator lease. Do not mark DONE or ready
until all required rows have current build evidence.
