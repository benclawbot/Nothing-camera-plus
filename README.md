# Camera+

An Android camera app, built from scratch as a clean-room project.

**Clean-room statement.** Every file in this repository is original work written for this project, against public Android documentation and publicly posted user feedback. No code, resources, assets, fonts, icons, or other material from any shipped application — decompiled, extracted, or otherwise — is contained in or used by this repository. Third-party dependencies (AndroidX, CameraX, Jetpack Compose, Material Components, ZXing) are used under their own open-source licenses.

**Not affiliated.** Camera+ is an independent project. It is not affiliated with, endorsed by, or connected to any device manufacturer.

## Status

Current release: **v1.2.0** (`versionCode 5`), debug-signed, `minSdk 33` (Android 13+), developed and validated on a Nothing Phone (A001), Android 16 / API 36.

| Area | State |
| --- | --- |
| Still capture pipeline (ZSL, queueing, background post-processing) | Done, device-verified |
| Video pipeline (Recorder, stabilization, OIS, snapshots, mute) | Done, device-verified |
| Modes: Photo, Video, Portrait, Beauty, Night, Panorama, Macro, QR, Slow motion, Time lapse, Expert | Done; where an OEM pipeline is required (see below) a bounded clean-room fallback runs |
| Expert manual controls (ISO / shutter / focus / EV / WB) | Done, device-verified |
| Subject tracking (photo + video) | Done, device-verified (added in v1.2.0) |
| Gallery hand-off and in-app gallery | Done, device-verified |
| 188 JVM unit tests | Green |

Feature-by-feature status against the original-app inventory, including what is a clean-room substitute rather than a reproduction, is tracked in [notes/FEATURE-COVERAGE.md](notes/FEATURE-COVERAGE.md); device checks are logged in [notes/PLAN-v1.1.md](notes/PLAN-v1.1.md).

## Download

- [`cameraplus-1.2.0-debug.apk`](https://github.com/benclawbot/Nothing-camera-plus/releases/tag/v1.2.0) — debug-signed, for Android 13+. The same file is attached to the v1.2.0 release.
- Historical builds live in [dist/](dist/) and on the [releases page](https://github.com/benclawbot/Nothing-camera-plus/releases).

Install by sideloading (`adb install cameraplus-1.2.0-debug.apk`) or by opening the file on the device.

## Features

### Modes

The carousel ships eleven entries: **PHOTO, PORTRAIT, BEAUTY, NIGHT, PANORAMA, MACRO, QR, VIDEO, SLO-MO, TIME-LAPSE, EXPERT**.

- **PHOTO** — the Auto path: CameraX still capture with the processing sheet, HDR and RAW options.
- **PORTRAIT** — CameraX BOKEH extension where the device advertises it (real depth lens blur); otherwise a documented software ellipse blur with an adjustable strength slider.
- **BEAUTY** — CameraX FACE_RETOUCH extension when available; otherwise bounded software skin smoothing.
- **NIGHT** — CameraX NIGHT extension when advertised; otherwise a bounded 4-frame alignment/merge fallback.
- **PANORAMA** — sweep capture with frame counter and direction guide, stitched on-device (translation-only correlation + feathering).
- **MACRO** — Camera2 macro AF mode when the lens reports it.
- **QR** — local ZXing QR analyzer with open/copy actions for the result.
- **SLO-MO / TIME-LAPSE** — Camera2 constrained high-speed recording and MediaRecorder interval capture (see below).
- **EXPERT** — manual ISO, shutter, focus, EV and white balance on top of the standard pipeline.

### Photo

- Aspect-ratio picker (1:1, 4:3, 16:9, 20.5:9 and the device's other crops) and a Standard/MAX resolution choice (MAX selects the largest advertised JPEG output).
- HDR toggle (software tone-mapping by default; the CameraX HDR extension is used when the device advertises it).
- RAW/DNG capture **plus** a companion JPEG where the hardware reports raw support.
- Flash cycle: off / auto / on, plus **screen flash** for the front camera (CameraX `ScreenFlashView`); steady torch in video.
- Self-timer off / 3 s / 10 s with a live countdown that can be cancelled.
- Composition guides (rule of thirds, center, diagonal) and a gravity-driven level line.
- Capture queueing: the shutter never locks while previous frames are written (up to four in flight).
- **Touch shutter** (tap the preview to capture) and **volume-key shutter**, both opt-in toggles; shutter-sound toggle.
- Front-camera mirror capture preference, and opt-in geotagging written to MediaStore/EXIF.
- Watermark stamp: on/off with date, time, location and author lines.

### Subject tracking (new in v1.2.0)

A clean-room tracking autofocus for both photo modes and video:

- Tap the crosshair icon in the top bar to arm tracking, then **tap your subject** on the preview to lock it.
- A corner-bracket reticle follows the subject; the AF/AE metering point is re-issued as the subject moves, so focus stays on it instead of drifting back to the frame centre.
- The tracker is a zero-mean normalized cross-correlation matcher over a ~192 px upright luma frame (rotation- and mirror-corrected, integral-image statistics, slow template adaptation). Metering is suspended while match confidence is low, so an occluded subject never pulls focus onto the background.
- Re-metering happens after ~1.2 % of view-width of movement with a 2.5 s keep-alive; the reticle fades when the lock is weak. Long-press clears the subject; the toggle is persisted across launches.
- The analysis stream is bound only while tracking is armed; if a device cannot host the extra stream, the camera stays usable and the app says so instead of failing.

### Zoom & lenses

- Zoom stops derived from the physical focal lengths of the device's lenses, applied through zoom ratios (no session rebind — video keeps recording across a lens switch).
- Pinch-to-zoom with honest hardware-range clamping, plus per-mode caps (night up to 30x, portrait/macro 4x, slow motion 2x, time lapse 10x, panorama 1x).
- Front/back switch that keeps mode and preferences.

### Video

- Quality (HD / 1080p / 4K) and frame-rate (30/60 fps) requests, validated against device support on bind.
- Stabilization toggle, capability-detected: preview stabilization → standard video stabilization → optical; the estimated crop is shown as a labelled overlay so the loss is visible.
- Action-mode shortcut (60 fps + stabilization), independent OIS request where the lens supports it, and high-quality noise-reduction (3DNR) requests where advertised.
- Microphone mute, torch, record-location geotagging, front-camera mirror preference.
- Video snapshot: a concurrent still capture while recording, with a video-only fallback on devices that cannot host both.
- Recording keeps running across lens/zoom switching.

### Slow motion & Time lapse

- Slow motion: Camera2 constrained high-speed session, picking the device's advertised high-speed rates (1080p up to what the HAL reports).
- Time lapse: MediaRecorder interval capture with speed choices from X15 up to X960.
- Both modes are hardware-dependent and honestly capability-gated: only device-supported rates/sizes are offered.

### Panorama

- Sweep capture with a progress counter and direction hint; frames are stitched on-device with a translation model and feathered seams.
- Bounded and downsampled by design — it is a clean-room stitcher, not an OEM-grade engine, and the UI notes say so.

### Portrait & Beauty

- Portrait: OEM bokeh extension when available, otherwise adjustable software background blur (no depth map is implied).
- Beauty: OEM face-retouch extension when available, otherwise bounded smoothing controlled by the processing sheet.
- Both work with the device's normal lens set and respect the per-mode zoom cap (4x).

### Night

- Multi-frame night capture (OEM extension when advertised, otherwise the 4-frame fallback), with the bounded resolution noted in the UI.

### Macro

- Macro autofocus where the lens reports `AF_MODE` macro support; no 4 cm distance claim is made.

### QR

- Fully local QR scanning (ZXing), with open/copy result actions. No network, no account.

### Expert (manual)

- Manual ISO, exposure time and focus, with honest AUTO positions and ranges taken from the camera characteristics (log-scale gauges keep the low end usable).
- White-balance presets plus custom Kelvin.
- EV compensation with an on-viewfinder slider after a tap.
- All manual surfaces are opt-in; PHOTO stays simple.

### Focus & exposure gestures

- Tap-to-focus / tap-to-meter (combined AF+AE metering action; auto-cancels).
- Long-press to lock focus/exposure until released.
- Viewfinder EV slider after a tap, reading the device's compensation step.
- Subject tracking (above) supersedes the plain tap while it is armed.

### Post-processing & looks

- Processing sheet: tone, sharpening, noise reduction, HDR strength and skin smoothing, persisted across launches.
- Six clean-room looks — Natural, B&W, Warm, Cool, Vivid, Vintage — with the same intent applied to the viewfinder through a GL effect, so what you see is what the still pipeline produces.
- The saved image stays the authority; RAW captures bypass the software pipeline.

### Watermark

- Optional stamp with date, time, location and author lines, rendered into the saved still.

### Gallery

- The gallery button opens the **system gallery app directly on the newest capture** (Atom-11+-safe resolution chain: review action → default viewer → gallery-only VIEW → gallery home — never the system chooser).
- In-app fallback grid with a photo viewer, share, favourite and delete; videos hand off to the system player.

### Settings sheet

- All capture preferences in one categorised sheet: flash/screen flash, aspect ratio, resolution, HDR, RAW, video quality/fps, self-timer, grid, volume-key shutter, touch shutter, shutter sound, mirror capture, watermark, processing intent and looks.
- Preferences persist across launches and are applied live where the session allows.

### UI & layout

- Dark, monochrome design: black window, white hairline controls, monospace labels, round shutter/record buttons, dot indicator under the active mode.
- Edge-to-edge fullscreen with status-bar/display-cutout insets respected — the top control row never mixes with the system status bar.
- All visuals are original; only system fonts and standard Material icons are used.

## Honest validation notes

- Device-verified on Nothing Phone (A001), Android 16: preview, still capture (with EXIF-orientation repair), ZSL, gallery hand-off, tracking (photo/video/front camera), processing sheet, expert controls and the camera-settings flows.
- Feature rows marked 🟡 in [notes/FEATURE-COVERAGE.md](notes/FEATURE-COVERAGE.md) are implemented but depend on hardware/provider behaviour and are not guaranteed on every device (slow motion rates, time-lapse cadence, extensions, RAW output, stabilization).
- Vendor-only features that this project intentionally does **not** reproduce: beauty-video pipelines, AI scene detection, gender estimate, glyph/LED flash, Morpho/Tetras LUT packs, motion photo, Ultra HDR/Ultra XDR, 50 MP remosaic, super-resolution, TurboNight, tele-macro, depth-segmentation portrait and OEM panorama engines. Clean-room substitutes are labelled as such in the app and in the coverage matrix; no parity is claimed for them.

## Building

Requirements:

- JDK 17 or newer (developed with JDK 21)
- Android SDK: platform 36

```bash
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:testDebugUnitTest   # JVM unit tests (188)
```

`local.properties` (git-ignored) points at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

Runtime requirement: Android 13 or newer (`minSdk 33` — the preview-parity GL effect reads `SurfaceRequest` surfaces directly).

## Layout

- `app/src/main/java/app/cameraplus/`
  - `camera/CameraSession.kt` — CameraX binding: preview, still capture, video, RAW, focus/EV, subject tracking and MediaStore output
  - `camera/SubjectTracker.kt`, `camera/TrackingGeometry.kt`, `camera/SubjectTrackingAnalyzer.kt` — clean-room subject tracking (tracker maths, view↔frame geometry, analysis plumbing)
  - `camera/Camera2Recorder.kt` — Camera2 high-speed slow motion and MediaRecorder time lapse
  - `camera/LensPlan.kt`, `camera/RecorderPlan.kt` — lens/zoom and recorder planning math
  - `camera/Panorama*`, `camera/NightMerger.kt`, `camera/PortraitRenderer.kt` — bounded software mode prototypes
  - `camera/LookEffect.kt` — GL preview look (preview parity)
  - `camera/VideoProfile.kt` — stabilization policy, zoom/lens plan and per-mode zoom caps
  - `camera/ManualControlPlan.kt` — manual-control gauge math
  - `camera/SessionProfile.kt` — session modes and the camera capability model
  - `camera/CaptureProfile.kt`, `camera/LevelMath.kt` — pure policy math
  - `media/SystemGallery.kt`, `media/MediaLibrary.kt` — gallery hand-off resolution and MediaStore queries
  - `ui/CameraScreen.kt`, `ui/GalleryScreen.kt`, `ui/FocusControls.kt`, `ui/CompositionOverlay.kt`, `ui/ProcessingSheet.kt`, `ui/CameraPlusApp.kt`, `ui/theme/Theme.kt` — Compose UI
  - `processing/` — software still pipeline (`ImageProcessor` pure math, `JpegProcessor` glue, `WatermarkRenderer`)
  - `settings/` — capture/processing/watermark preference models and stores
- `app/src/test/` — JVM unit tests (188)
- `dist/` — built APKs

## License

MIT — see [LICENSE](LICENSE).
