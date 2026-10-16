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
- [x] Portrait — centre-weighted simulated bokeh, explicitly without depth segmentation.
- [x] Night — four-frame software merge at bounded resolution.
- [x] Gallery grid, photo viewer and share; video playback opens the system player.
- [x] Tap-to-meter, long-duration focus/meter lock and viewfinder EV slider.
- [x] Self-timer 3s/10s and video quality-tier picker (HD/1080p/4K).
- [ ] Full original parity — other settings and vendor-only features remain open in
      `FEATURE-COVERAGE.md`.

## Validation still required

- [ ] On the target phone, verify RAW+DNG, capture latency/ZSL, lens stops and the full zoom range.
- [ ] On the target phone, verify Camera2 high-speed session support and slow-motion playback.
- [ ] On the target phone, verify time-lapse cadence, output orientation and storage visibility.
- [ ] Inspect portrait/night/panorama output quality and peak memory on-device.

## Status log

- 2026-10-16: original inventory completed from the local decompilation; the full coverage matrix
  is in `FEATURE-COVERAGE.md`.
- 2026-10-16: code changes build successfully; 166 JVM tests pass. No physical device is
  connected, so camera behavior, actual latency and image quality remain unverified.
