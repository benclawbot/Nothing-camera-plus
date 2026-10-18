# v1.1 parity pass — implementation status

## Confirmed bug fixes

- [x] RAW capture uses CameraX's two-output overload for RAW+JPEG. The single-file overload
      throws for `OUTPUT_FORMAT_RAW_JPEG`.
- [x] Zoom requests are clamped to the selected camera's reported range.
- [x] Default lens selection uses the focal/sensor plan, not HAL enumeration order.
- [x] Gallery button opens an in-app grid, photo viewer and share flow.
- [x] Pinch zoom and focal-derived lens stop controls are wired.
- [x] Processing preflights oversized frames, streams encode and saves the original frame if
      optional software processing fails.

## User-requested features implemented

- [x] Logical/physical camera focal stops (e.g. 0.6x / 1x / 2x where reported by CameraX).
- [x] Pinch zoom over the range the bound camera reports; 20x only when hardware supports it.
- [ ] Instant-shutter parity — ZSL selection is implemented, but actual latency requires a
      device test.
- [x] Slow motion — Camera2 constrained high-speed recorder with a device-supported rate picker.
- [x] Time lapse — Camera2/MediaRecorder capture-rate path and X15…X960 choices.
- [x] Panorama — continuous YUV sweep, gyro/accelerometer guide, progressive strip and
      three-band translation stitch; target-phone behavior still needs verification.
      The original's ArcSoft BurstPMK engine is intentionally not reproduced; the clean-room
      substitute remains translation-only, three-band and downsampled.
- [x] Portrait — CameraX BOKEH extension if available; otherwise centre-weighted simulated blur.
- [x] Night — CameraX NIGHT extension if available; otherwise four-frame bounded software merge.
- [x] Gallery grid, photo viewer and share; video playback opens the system player.
- [x] Tap-to-meter, long-duration focus/meter lock and viewfinder EV slider.
- [x] Self-timer 3s/10s and video quality-tier picker (HD/1080p/4K).
- [x] Requested 30/60fps video targets, gated by CameraInfo's supported frame-rate ranges.
- [x] Aspect-ratio crops, hardware-reported maximum JPEG size option, Auto flash and front mirror.
- [x] Expert white-balance presets and approximate Kelvin gains.
- [x] CameraX BOKEH/NIGHT/HDR/FACE_RETOUCH extensions when advertised, with software fallbacks.
- [x] Camera2 macro AF where the camera reports the macro AF mode.
- [x] CameraX screen flash view, real-time FAST video-NR/OIS requests and an Action 60fps + stabilization shortcut.
- [x] QR-only local scanning, gallery favorite/delete/info, opt-in geotag, shutter-sound toggle,
      and configurable text watermark.
- [x] Six clean-room color looks mirrored in the still processor and live preview shader.
- [x] Four-frame simultaneous video snapshot use-case; hardware compatibility remains to be tested.
- [x] Photo crop ratios, optional MAX JPEG, Auto flash, still/video mirror, OIS toggle, geotag, watermark,
      screen flash, shutter sound, volume-key and touch shutter.
- [ ] Full original parity — other settings and vendor-only features remain open in
      `FEATURE-COVERAGE.md`.

## Validation still required

- [ ] On the target phone, verify RAW+DNG, capture latency/ZSL, lens stops and the full zoom range.
- [ ] On the target phone, verify slow-motion playback in a player (recording/codec output at both offered rates was verified).
- [ ] On the target phone, verify time-lapse output orientation and playback (all seven speeds produced H.264 files in `Movies/Camera+`).
- [x] On the target phone, exercise every offered slow-motion rate and all seven time-lapse speeds.
- [ ] On the target phone, test both panorama directions, live guide/preview, stop/save and fallback.
- [x] On the target phone, toggle video NR and confirm preview, controls and recording still work.
- [ ] Check every screen's text contrast; visible copy must be white or black, never grey.
- [ ] Inspect portrait/night/panorama output quality and peak memory on-device.
- [ ] Verify still orientation for every pose (portrait/landscape/upside down) and both
      cameras on the target phone. Pose math is reimplemented from the decompiled original
      (`SettingContext.getCaptureOrientation`: pose + `SENSOR_ORIENTATION` with the
      sensor-270 landscape flip) and matches the standard camera2 table; on-device
      confirmation of the landscape/upside-down poses is still pending (only the desk pose
      was reachable in the current session).
- [ ] Verify night/portrait ARGB output and panorama YUV-stream orientation against the
      pose-derived rotation on-device.
- [ ] Verify video playback orientation for both recording paths (CameraX video and the
      Camera2 high-speed/time-lapse recorder); adjust the recorder hint if 180° off.
- [x] End-to-end QR test URL detected and opened in Chrome from the workstation QR target.
- [ ] Confirm the new QR result dismissal after returning from the browser.
- [ ] Confirm landscape-pose preview target/crop on the latest build and make a physical panorama sweep.
- [x] Verify the gallery hand-off on Nothing OS: verified — the gallery button opens the
      Google Photos viewer directly on the newest capture (no chooser); the reference
      phone has Nothing Gallery disabled, and the hand-off now picks the best live
      viewer/gallery app via declared package visibility.
- [x] Fresh still capture on-device: 1.02 MB JPEG in `Pictures/Camera+` with the repaired
      EXIF orientation (3072×1728, tag 8); no errors in the capture path.
- [x] Delete the two 0-byte MediaStore leftovers from the pre-fix capture path
      (`CameraPlus_1792175441365.jpg`, `CameraPlus_1792176319016.jpg`).
- [ ] On the target phone, hold-shutter continuous shot: start on hold, running
      counter, the 10-shot cap, stop on release, and the refusal notes with the
      self-timer and outside PHOTO/EXPERT.
- [ ] On the target phone, launcher shortcuts: each of the five entries opens its
      mode/facing, and a shortcut tapped while the app is open (singleTop) applies
      without a stale activity or a duplicate.
- [ ] On the target phone, zoom labels: the mm values match the lens serving each
      stop (ultrawide/main/telephoto) and track the pinch readout; RESTORE
      DEFAULTS returns capture, look and watermark options to their defaults.

## Status log

- 2026-10-16: original inventory completed from the local decompilation; the full coverage matrix
  is in `FEATURE-COVERAGE.md`.
- 2026-10-16: code changes build successfully; 178 JVM tests pass. No physical device is
  connected, so camera behavior, actual latency and image quality remain unverified.
- 2026-10-17: first on-device pass (Nothing A001): menus restructured into the categorized
  camera-settings sheet. Found and fixed a real bug — CameraX `OutputFileOptions.Builder`
  takes a collection URI, not an inserted item URI; still/RAW saves had produced 0-byte
  files. A standard capture now saves a real 967 KB JPEG.
- 2026-10-17: EXIF audit of a pulled capture showed the HAL writes orientation tag 0
  (undefined) with sideways pixels. Fixes: calibrated rotation (back 270° CW), post-save
  EXIF tag repair for file-path saves, and the same rotation applied in the processed
  (JpegProcessor) and ARGB (night/portrait/panorama) pipelines.
- 2026-10-17: gallery button now hands off to the device gallery app (AOSP review action
  pinned to the system gallery) with the in-app grid as fallback; the activity is now
  fullscreen (status bar hidden) like the original camera theme.
- 2026-10-17: second on-device pass: the gallery hand-off initially fell back to the in-app
  grid because Android 11+ package visibility hides other apps from `queryIntentActivities`
  — fixed with a `<queries>` declaration plus a resolution chain (review action → default
  viewer → gallery-only VIEW → gallery home) that never lands on the system chooser.
  Verified: the button opens the Google Photos viewer on the newest capture. Top controls
  now pad against the status-bar/display-cutout inset (fullscreen, no more mixing). Fresh
  capture regression: 1.02 MB JPEG, orientation tag 8. The two 0-byte ghost rows were
  deleted from MediaStore. 178 JVM tests pass.
- 2026-10-17: released v1.1.0 (debug APK attached to the GitHub release).
- 2026-10-17: subject tracking shipped (commit 3c087aa): top-bar toggle, tap to lock a
  subject, zero-mean NCC tracker on ~192px upright luma frames, AF/AE metering follows
  the tracked point (re-meter after 1.2% view movement, 2.5 s keep-alive, suspended when
  confidence drops, cancelled on clear). Analysis stream binds only while tracking is
  armed, with a recovery ladder that keeps the camera usable if the extra stream cannot
  bind. On-device verified in photo, video and front camera: selection mapping matches the
  predicted view→frame math to the pixel, 15 Hz updates at 0.999 confidence, metering
  keep-alive cadence exact, no crashes. 188 JVM tests pass (10 new).
- 2026-10-17: coverage audit re-verified every row of notes/FEATURE-COVERAGE.md against
  the sources; stale rows corrected (touch shutter, screen flash, night 30x / portrait 4x
  zoom caps, B&W look) and the remaining gaps listed with effort estimates.
- 2026-10-17: capture orientation reworked to follow the phone's pose (gyro/accelerometer
  `OrientationEventListener`, rounded to 0/90/180/270) combined with each camera's reported
  `SENSOR_ORIENTATION`, per the decompiled original's formula. Still-capture target rotation
  is pinned to portrait so the pipeline's baked rotation is deterministic. Delivered frames
  may already carry part of the rotation (the MTK HAL bakes CameraX's requested
  JPEG_ORIENTATION into the pixels and leaves the Exif tag undefined — evidenced by a
  3072×4080 in-memory frame delivered from a 4080×3072 sensor), so every path now subtracts
  what was already applied: in-memory.decode, ARGB frame captures, and disk saves (which
  re-encode only when the pose needs more than the HAL baked, else write/replace the Exif
  tag instead). Unit tests cover the pose table, the applied/remaining split and both HAL
  behaviors (198 tests pass). On-device verification pending — the phone has been off USB.
- 2026-10-17: user-reported panorama gap. The stock panorama is video-based: the decompiled
  `PanoramaModeV2` streams continuous NV21 preview frames (2-deep queue) into the ArcSoft
  `BurstPMKEngine` via `Panorama23114Delegate`; the engine reports per-frame progress plus a
  downscaled stitched preview (posted to the UI as a live strip) and a tips status; a
  gyroscope/accelerometer listener (`GyroscopeRoundDetector`, `RoundDetector`) supplies the
  "stay in line" guidance and the 360° loop end detection. Before this rework, Camera+ captured
  discrete stills and stitched only on finish, guided by a frame counter and direction toggle.
  Clean-room rework implemented: continuous CameraX `ImageAnalysis` YUV frames at bounded
  resolution → incremental translation matcher and progressive preview strip → gyro yaw,
  speed and accelerometer level guidance → stitch and save on stop/target sweep. Quality
  remains translation-only, three-band and downsampled (documented); on-device verification is pending.
- 2026-10-17: user reported unreadable grey copy, non-working SLO-MO/TIME-LAPSE, and video
  3DNR blocking controls. UI text now uses only white/black. Camera2 MediaRecorder keeps the
  native HAL-advertised dimensions (orientation is container metadata), slow motion exposes
  both endpoints of variable high-speed brackets and does not request microphone permission,
  and video NR now requests advertised `FAST` rather than the potentially stalling
  `HIGH_QUALITY` mode. At that point, device checks were still waiting on USB reconnection;
  see the target-phone follow-up below. 213 JVM tests pass; APK compiles.
- 2026-10-17: target-phone follow-up: the EV slider crashed at +4.0 because its top knob used
  negative padding; it now uses a signed offset, and both EV +4.0 and -4.0 were exercised
  without a crash. Camera2 slow motion produced valid H.264 1080p files at 120 and 240 fps;
  preview restoration after stop was added and CameraX became active again after each clip.
  The FHD-bounded time-lapse path produced valid H.264 files at X15, X30, X60, X120, X240,
  X480 and X960; the 4K `MediaRecorder.prepare()` combination failed and motivated the FHD cap.
  Video NR remained usable through a video capture. Public CameraX/Camera2 on this A001 exposes
  camera IDs 0 (back main, 5.56 mm / ~24 mm-equivalent, zoom 1..10) and 1 (front), with no
  public physical camera IDs; the app's 2x+ stops are digital crops, not OEM 0.6x/tele lenses.
  Shared ViewPort crop improved preview-to-file framing correlation (FIT viewport 0.66 NCC vs
  0.41 when simulated as a fill crop). Real depth, physical-lens switching, QR target scanning,
  landscape-pose preview confirmation and a physical panorama sweep remain to be verified.
- 2026-10-17: camera inventory on A001: Android exposes camera IDs 0 (back main) and 1 (front);
  CameraX reports one back lens (5.56 mm, about 24 mm-equivalent, zoom range 1..10) and no
  physical-camera IDs. A 1x file has 5.56 mm / 24 mm-equivalent EXIF; a 2x file retains that
  focal length and records `DigitalZoomRatio=2`. The 0.6x and telephoto physical modules are
  present in HAL-private dumpsys data but not exposed through this app's public Camera2 API,
  so clean-room lens switching cannot target them. Camera2 also exposes no public
  `DEPTH_OUTPUT` capability and the CameraX BOKEH extension is unavailable; the device has
  MediaTek-private VSDOF depth-size metadata, which this app does not call. Portrait fallback
  now uses face-guided software masking when possible and displays a simulated live blur; the
  live mask is still centred and the saved mask can follow a detected face. Both remain
  explicitly depth-free. A workstation QR test was detected and opened in Chrome; the new
  result-dismissal change still needs a retest. The source activity is portrait-locked and
  forwards sensor orientation to camera preview/UI nodes (`OrientationManager` and
  `CameraUIContext`); Camera+ keeps the controls portrait-locked and now changes Preview's
  target rotation/crop dynamically while saving the shutter-time pose. The latest landscape
  viewfinder appearance and physical panorama sweep still need confirmation.
- 2026-10-18: preview framing rework (supersedes the dynamic per-pose preview rotation
  described above). Preview and VideoCapture target rotation are pinned to
  `Surface.ROTATION_0` and the shared `ViewPort` stays portrait, matching the
  portrait-locked activity: the device pose now affects only the saved still, never the
  preview surface. The viewfinder is sized to the selected capture ratio (FILL_CENTER)
  instead of filling the screen with a FIT viewport, and the guides, level line,
  stabilization-crop overlay, screen flash and tap handling all share that same frame;
  CameraX now binds after the first layout so the surface starts valid. Device re-test
  pending: landscape/upside-down poses, preview-to-file framing correlation, and the
  physical panorama sweep. 213 JVM tests pass; APK compiles.
- 2026-10-18: launcher shortcuts. Static shortcuts (`res/xml/shortcuts.xml`) for the
  five highest-priority modes — Photo (back), Selfie (front), Video, Portrait, Time
  lapse — with MainActivity as a singleTop target; the `ModeShortcut` contract
  (`app.cameraplus.action.SHORTCUT_CAMERA`, extras `SHORTCUT_MODE` / `SHORTCUT_FACING`)
  reaches every mode for shortcut builders and automation and is unit-tested.
  Shortcut art reuses the app icon; the system caps displayed shortcuts per launcher
  (`getMaxShortcutCountPerActivity`). Device verification pending.
- 2026-10-18: hold-shutter continuous shot. Holding the shutter queues stills at
  queue capacity until release, capped at 10 frames (the original's burst count
  limit; its hold-shutter preference maps to continuous shot via
  `SettingKeys.KEY_HOLD_SHUTTER_KEY`). PHOTO/EXPERT only; the self-timer and special
  pipelines refuse with a status note. The UI shows a running "BURST n/10" counter
  and a final count; `ContinuousShotPlan` is the pure, unit-tested policy. 223 JVM
  tests pass (10 new); APK compiles. Device verification still pending.
- 2026-10-18: focal-length zoom labels. Zoom chips now show 35 mm-equivalent
  focal lengths ("24mm") instead of bare ratios, and the live readout shows
  ratio + mm while pinching. `LensLayout.eqFocalMmFor` resolves the serving lens
  (exact for ultrawide/telephoto stops, digital zoom included) and the session
  publishes `zoomStopFocalMm` / `referenceEqFocalMm`; same basis as the
  original's zoom slider (`Util.formatFocalLength`, 43.27 mm frame). Device
  verification pending.
- 2026-10-18: restore defaults. The camera settings sheet gained a RESTORE
  DEFAULTS action that resets capture preferences, the processing look and the
  watermark (and switches geotag off) in one tap, mirroring the original's
  settings-reset row. 227 JVM tests pass (4 new); APK compiles. Device verification
  pending.
- 2026-10-18: watermark camera-parameters line. Stills can stamp the original's
  EXIF-style line ("24MM F/1.9 1/50S ISO 123") behind a new "Camera params"
  toggle: `CameraParameters` formats the 35 mm-equivalent focal from the
  session, and aperture/shutter/ISO are read from the still's own EXIF
  (`JpegCameraParameters`); software merges (night, panorama) stamp the static
  lens values. Unknown parts are omitted rather than faked. 232 JVM tests pass
  (5 new); APK compiles. Device verification pending.
- 2026-10-18: captures move to `DCIM/Camera`. Photos (JPEG, RAW+JPEG and the
  software merges) and videos now land in the platform's standard camera
  folder, matching the original (`CameraSet.CAMERA = "DCIM/Camera"`, whose
  thumbnail scans look in `DCIM/Camera/`). `MediaPaths` is the single source of
  the path; the in-app gallery switched from folder filtering to
  `OWNER_PACKAGE_NAME`, because DCIM/Camera is shared with other camera apps
  (it still finds shots made under the old `Pictures/Camera+` path). 232 JVM
  tests pass; APK compiles. Device verification pending.
- 2026-10-18: default-gallery picker. The camera settings sheet lists the
  installed galleries (gallery-home handlers plus image viewers, sorted, this
  app excluded) and can pin one; `SystemGallery.open(preferredPackage)` tries
  that package first (review → view → gallery home) and silently falls back to
  the existing chain when it cannot serve, so an uninstalled pin is harmless.
  Full reset returns the picker to Automatic. 234 JVM tests pass (2 new); APK
  compiles. Device verification pending.
- 2026-10-18: in-app video playback. The gallery viewer's video branch renders
  a platform `VideoView` through `AndroidView` with its own `MediaController`,
  auto-play and looping, and stops/releases the player in a `DisposableEffect`
  when the viewer closes. The old "Tap to open in your video player" hand-off
  and its helper are gone; Share on a video still uses the system chooser. 234
  JVM tests pass; APK compiles. Device verification pending.
- 2026-10-18: expert histogram. EXPERT mode gains a HISTOGRAM toggle (persisted
  with the capture preferences, cleared by the settings sheet's restore
  defaults): a 32-bar luma histogram with mean and clipped-black/white
  percentages drawn over the viewfinder. `LumaHistogram` is the pure,
  unit-tested math; `HistogramAnalyzer` reuses `LumaExtractor` at ~10 Hz.
  Because CameraX wants a single analysis use case, tracking and the histogram
  now share one stream through `FanOutAnalyzer`, which owns closing the frame.
  The histogram rebinds like tracking and turns itself off with a status note
  when the camera cannot host an analysis stream. 240 JVM tests pass (6 new);
  APK compiles. Device verification pending.
- 2026-10-18: zebra stripes and focus peaking. The analysis stream grew from
  the histogram into the viewfinder's shared consumer
  (`ViewfinderAnalyzer`, replacing `HistogramAnalyzer`): one luma extraction
  per frame now feeds the histogram, a clipping map and an edge map.
  `OverlayMasks` is the pure grid maths — 48x36 cells, a cell lights up when a
  fifth of its pixels clip at either end or when it holds an edge — with
  unit-tested row-run merging so the overlay draws one rectangle per run.
  The zebras are drawn in the subject tracker's frame space
  (`TrackingGeometry`), so the stripes sit on the clipped pixels. EXPERT gains
  ZEBRAS and PEAKING chips beside HISTOGRAM; all three persist and share one
  rebind. 249 JVM tests pass (9 new); APK compiles. Device verification
  pending.
- 2026-10-18: sticky exposure, anti-flicker and focus timeout, all three from
  the community request list or a peer app. `AntiBanding` (AUTO / 50 HZ /
  60 HZ) is offered only for modes the camera advertises
  (`AntiBandingSupport`, unit-tested), applied as a Camera2 capture-request
  option on the preview, still and video requests, and the session rebinds on
  a change; `FocusTimeout` (off / 5 / 15 / 30 s) hands an unlocked tap-to-focus
  back to continuous autofocus; KEEP EXPOSURE re-applies the dialled-in
  compensation index to every bind and remembers it across launches, with the
  viewfinder slider persisting each change. The settings sheet gains FOCUS
  TIMEOUT, ANTI-FLICKER and KEEP EXPOSURE rows. 253 JVM tests pass (4 new);
  APK compiles. Device verification pending.
- 2026-10-18: photographer kit — exposure bracketing, ghost image, RAW only.
  `ExposureBracket` (off / 3 shot ±2 EV / 5 shot ±2 EV) is converted from EV
  to compensation-index offsets against the camera's own step by
  `ExposureBracketPlan` (pure, unit-tested, folding duplicate indexes away on
  coarse-step cameras); `CameraSession.captureBracket` moves the index,
  settles, captures and restores the dialled-in value, reporting progress so
  the viewfinder reads BRACKET n/N. `RawMode` turns the EXPERT RAW chip into a
  three-state cycle (off / RAW+JPG / RAW ONLY), with the DNG-only path writing
  a single `image/x-adobe-dng` entry. Ghost image (Open Camera's onion skin)
  holds the last shot over the viewfinder at 38% opacity, decoded off the main
  thread with a power-of-two downsample (`ghostSampleSize`, unit-tested). 263
  JVM tests pass (10 new); APK compiles. Device verification pending.
- 2026-10-18: shooting flow — default focal, timer extras, barcode types.
  `FocalPresets` (pure, unit-tested) turns 24/28/35 mm presets into displayed
  zoom ratios against the reference lens, labels them for the settings sheet
  and defines the 1x-tap cycle (28 -> 35 -> the lens's own wide stop);
  tapping the active zoom chip steps it and makes the choice the new default.
  The self-timer gained 20/30/60 s, a TIMER SHOTS series of 1-5 frames fired
  from one countdown, and an optional `VoiceCountdown` (TextToSpeech, built
  only while the preference is on). QR mode's ZXing decoder now takes a
  format list, and `BarcodeScanning` widens the search from QR only to the 2D
  matrix codes or every format ZXing knows (`BarcodeFormats`, unit-tested).
  `capturePreferences` became live state so settings read back what the sheet
  just changed. 273 JVM tests pass (10 new); APK compiles. Device verification
  pending.
- 2026-10-18: metadata privacy. `ExifPrivacy` (media package, unit-tested)
  holds the tag names to remove — GPS position/heading/speed, Make, Model,
  Software, lens model and the serial numbers, owner and image ID — as plain
  strings, and applies strip + author/copyright stamps through a
  `ParcelFileDescriptor`. Every still passes the pass on its way out of
  `CameraSession.capture` (one choke point for the neutral, processed and RAW
  paths) and `saveArgbJpeg` covers the night/portrait/panorama saves; a DNG is
  left untouched. The settings sheet gains STRIP METADATA and the EXIF AUTHOR /
  EXIF COPYRIGHT fields. 278 JVM tests pass (5 new); APK compiles. Device
  verification pending.
