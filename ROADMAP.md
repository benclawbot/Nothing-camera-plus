# Roadmap

Guiding principle: **Auto mode stays simple.** Depth (RAW, manual controls) lives in opt-in expert surfaces, never on the main shutter path.

The requirements below are distilled from publicly posted user feedback. All implementations must use public Android APIs (CameraX and Camera2 interop); no proprietary code or assets are used anywhere in this project.

## M1 — Skeleton (done)

- [x] CameraX preview and still capture, saved through MediaStore
- [x] Flash toggle, lens flip, mode selector scaffold
- [x] Minimal monochrome UI shell
- [x] Build validation (`assembleDebug`) and JVM unit tests

## M2 — Instant shutter

Goal: the frame is captured at the moment of the tap; processing (HDR, denoise) finishes afterwards while the camera stays ready for the next shot. Aimed at moving subjects: kids, pets, candid shots.

- [x] Capture the frame on tap without waiting for the processing pipeline (zero-shutter-lag session where the device supports it; flash toggles back to the standard path)
- [ ] Move HDR/post-processing to a background step after the frame is buffered
- [x] Keep the shutter armed during processing; no blocked next capture (captures queue, the shutter never locks)
- [x] Honest in-progress state in the UI (shutter reflects pending saves)

## M3 — Control over the finished photo

Goal: stop forcing over-processing; make the saved image match the preview.

- [x] `Natural` preset: neutral processing intent, selectable from the processing sheet
- [x] Adjustable strength for: sharpening, noise reduction, HDR, skin smoothing (settings sheet)
- [x] Persisted user settings (clamped on load)
- [ ] Apply the intent to saved images (post-processing pipeline)
- [ ] Parity between preview and saved output (same intent applied to both)

## M4 — Video stabilization and lens switching

- [ ] Reduce jitter and dropped frames in video
- [ ] Smooth focus/exposure transitions when switching lenses
- [ ] Visual indication of the stabilization crop (show what is lost)

## M5 — Composition aids without clutter

- [ ] Horizontal level indicator
- [ ] Additional composition guides (rule of thirds, center, diagonals)
- [ ] Quick access to exposure and focus without leaving Auto
- [ ] Expert controls remain available, opt-in

## M6 — RAW where the hardware allows

- [ ] Full-resolution RAW capture where supported
- [ ] Broader Camera2 interop for enthusiast workflows (e.g. editor round-trips)
- [ ] Minimal expert RAW controls
