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

## Status log

- 2026-10-16: original inventory completed from the local decompilation; the full coverage matrix
  is in `FEATURE-COVERAGE.md`.
- 2026-10-16: code changes build successfully; 178 JVM tests pass. No physical device is
  connected, so camera behavior, actual latency and image quality remain unverified.
