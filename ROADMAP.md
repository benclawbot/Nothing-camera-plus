# Roadmap

Guiding principle: **Auto mode stays simple.** Depth (RAW, manual controls) lives in opt-in expert surfaces, never on the main shutter path.

The requirements below are distilled from publicly posted user feedback. All implementations must use public Android APIs (CameraX and Camera2 interop); no proprietary code or assets are used anywhere in this project.

## M1 — Skeleton (done)

- [x] CameraX preview and still capture, saved through MediaStore
- [x] Flash toggle, lens flip, mode selector scaffold
- [x] Minimal monochrome UI shell
- [x] Build validation (`assembleDebug`) and JVM unit tests

## M2 — Instant shutter (done)

Goal: the frame is captured at the moment of the tap; processing (HDR, denoise) finishes afterwards while the camera stays ready for the next shot. Aimed at moving subjects: kids, pets, candid shots.

- [x] Capture the frame on tap without waiting for the processing pipeline (zero-shutter-lag session where the device supports it; flash, RAW and manual controls toggle back to the standard path)
- [x] Move HDR/post-processing to a background step after the frame is buffered (the frame is buffered in memory, processed off the main thread, then written)
- [x] Keep the shutter armed during processing; no blocked next capture (captures queue, the shutter never locks)
- [x] Honest in-progress state in the UI (shutter reflects pending saves)

## M3 — Control over the finished photo (done)

Goal: stop forcing over-processing; make the saved image match the preview.

- [x] `Natural` preset: neutral processing intent, selectable from the processing sheet
- [x] Adjustable strength for: sharpening, noise reduction, HDR, skin smoothing (settings sheet)
- [x] Persisted user settings (clamped on load)
- [x] Apply the intent to saved images (software pipeline for stills: full-resolution decode, process, encode, off the main thread)
- [x] Parity between preview and saved output (same intent applied to both; a GL look effect on the preview mirrors the still pipeline's tone curve and blend factors, with GLSL approximations for the spatial steps — the saved image stays the authority)

## M4 — Video stabilization and lens switching (done)

- [x] Reduce jitter and dropped frames in video (platform stabilization requested through CameraX, capability-detected per device: preview stabilization > standard > optical; toggleable)
- [x] Smooth focus/exposure transitions when switching lenses (zoom-ratio switching resolved by the platform — no session rebind, so recording keeps running)
- [x] Visual indication of the stabilization crop (show what is lost) (labelled estimate overlay; the real crop is camera-managed and not exposed through public APIs)

## M5 — Composition aids without clutter (done)

- [x] Horizontal level indicator (gravity sensor; line counter-rotates and brightens when level)
- [x] Additional composition guides (rule of thirds, center, diagonals — cycled from the top bar, off by default)
- [x] Quick access to exposure and focus without leaving Auto (tap-to-focus / tap-to-meter)
- [x] Expert controls remain available, opt-in (EXPERT mode: manual ISO, exposure time and focus with AUTO positions; never on the Auto shutter path)

## M6 — RAW where the hardware allows (done)

- [x] Full-resolution RAW capture where supported (RAW_JPEG: DNG plus companion JPEG, gated on the raw capability)
- [x] Broader Camera2 interop for enthusiast workflows (manual ISO/exposure/focus ride the capture request through `Camera2Interop`; an editor round-trip is not in scope for this build)
- [x] Minimal expert RAW controls (a single RAW toggle in EXPERT mode, disabled honestly when the hardware does not report the raw capability)
