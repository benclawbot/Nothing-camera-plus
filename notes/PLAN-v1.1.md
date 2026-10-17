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
