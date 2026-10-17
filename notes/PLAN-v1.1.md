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
- [x] Panorama — frame capture + translation-only stitch; low-resolution software substitute.
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
- [x] CameraX screen flash view, 3DNR/OIS requests and an Action 60fps + stabilization shortcut.
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
- [ ] On the target phone, verify Camera2 high-speed session support and slow-motion playback.
- [ ] On the target phone, verify time-lapse cadence, output orientation and storage visibility.
- [ ] Inspect portrait/night/panorama output quality and peak memory on-device.
- [ ] Verify still orientation calibration for the front camera and for mirrored selfies
      (the back-camera mapping, 270° CW, is confirmed by ON-device capture; front is provisional).
- [ ] Verify night/portrait/panorama output orientation (ARGB pipeline now uses the same
      calibrated rotation as stills).
- [ ] Verify video playback orientation for both recording paths (CameraX video and the
      Camera2 high-speed/time-lapse recorder); adjust the recorder hint if 180° off.
- [x] Verify the gallery hand-off on Nothing OS: verified — the gallery button opens the
      Google Photos viewer directly on the newest capture (no chooser); the reference
      phone has Nothing Gallery disabled, and the hand-off now picks the best live
      viewer/gallery app via declared package visibility.
- [x] Fresh still capture on-device: 1.02 MB JPEG in `Pictures/Camera+` with the repaired
      EXIF orientation (3072×1728, tag 8); no errors in the capture path.
- [x] Delete the two 0-byte MediaStore leftovers from the pre-fix capture path
      (`CameraPlus_1792175441365.jpg`, `CameraPlus_1792176319016.jpg`).

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
