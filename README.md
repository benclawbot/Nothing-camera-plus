# Camera+

An Android camera app, built from scratch as a clean-room project.

**Clean-room statement.** Every file in this repository is original work written for this project, against public Android documentation and publicly posted user feedback. No code, resources, assets, fonts, icons, or other material from any shipped application — decompiled, extracted, or otherwise — is contained in or used by this repository. Third-party dependencies (AndroidX, CameraX, Jetpack Compose, Material Components) are used under their own open-source licenses.

**Not affiliated.** Camera+ is an independent project. It is not affiliated with, endorsed by, or connected to any device manufacturer.

## Status

All planned milestones are implemented in v1.0.0 and build-validated:

| Milestone | State |
| --- | --- |
| M1 — Skeleton (preview, capture, UI shell) | Done, build-validated |
| M2 — Instant shutter | Done (ZSL capture, capture queueing, background post-processing) |
| M3 — Processing control (Natural + strengths) | Done (settings, software pipeline, live preview parity via GL effect) |
| M4 — Video stabilization & lens switching | Done (capability-detected stabilization + crop indication, zoom-ratio lens switching) |
| M5 — Composition aids + expert controls | Done (guides, level, tap-to-focus, EXPERT mode) |
| M6 — RAW / Camera2 depth | Done (RAW/DNG where supported, manual ISO/exposure/focus via interop) |

Details in [ROADMAP.md](ROADMAP.md).

**Validation honesty:** the v1.0.0 release was validated by compilation and JVM tests. The current local parity work builds and passes 178 JVM tests, but has not been run on a physical device; capture latency, camera enumeration, high-speed recording, extension support, processing quality, video stabilization, lens transitions, gallery visibility and RAW output remain unverified on hardware.

**Parity work in progress:** local development now includes an in-app gallery with delete/favorite/share, exposure/focus controls, lens and zoom planning, aspect ratios, max JPEG selection, Auto flash, front-camera mirror, geotagging, watermark options, shutter sound, volume-key shutter, white balance, clean-room color looks, QR scanning, video quality/fps requests and video snapshots. It also tries CameraX BOKEH/NIGHT/HDR/FACE_RETOUCH extensions when a device advertises them, with bounded software fallbacks for still modes; slow motion/time lapse use Camera2. These changes are not in the v1.0.0 APK linked below and are not released. The original-app feature inventory, current status and limitations are tracked in [notes/FEATURE-COVERAGE.md](notes/FEATURE-COVERAGE.md); device checks are in [notes/PLAN-v1.1.md](notes/PLAN-v1.1.md).

For device testing of the unreleased worktree, build `:app:assembleDebug`; its current candidate is `app/build/outputs/apk/debug/app-debug.apk` (`1.1.0-dev`, versionCode 3). It is not attached to the v1.0.0 release.

## Download

- [`dist/cameraplus-1.0.0-debug.apk`](dist/cameraplus-1.0.0-debug.apk) — debug-signed, for Android 13+ (`minSdk 33`). The same file is attached to the [v1.0.0 release](https://github.com/benclawbot/Nothing-camera-plus/releases/tag/v1.0.0).
- Install by sideloading (`adb install dist/cameraplus-1.0.0-debug.apk`) or by opening the file on the device.

## Features

**Capture & processing (M1–M3)**

- Live preview via CameraX; still captures saved to `Pictures/Camera+/` through MediaStore.
- Instant shutter: a zero-shutter-lag session where the device supports it; captures queue and the shutter never locks while previous frames are written. Flash, RAW and manual controls fall back to the standard path honestly.
- Post-processing intent: `Natural` (no forced over-processing) plus adjustable sharpening / noise reduction / HDR / skin smoothing, persisted across launches. Non-Natural captures run through the software pipeline on a background thread.
- Preview parity: the viewfinder runs the same intent through a GL look effect — the same tone curve and blend factors as the still pipeline, with GLSL approximations for the spatial steps. The saved image stays the authority.

**Video (M4)**

- Video mode binds a `Recorder` (FHD with automatic lower fallback) and saves to `Movies/Camera+/` through MediaStore.
- Stabilization is capability-detected and toggleable (preview stabilization > standard video stabilization > optical); the estimated crop is shown as a labelled overlay so the loss is visible instead of implicit.
- Lens switching: zoom stops derived from the physical focal lengths; switching happens through zoom ratios, which the platform resolves as a camera-managed lens transition — no session rebind, so recording keeps running.
- Microphone toggle with a runtime permission flow; the flash button becomes a torch in video mode.

**Expert (M5–M6)**

- `EXPERT` mode: manual ISO, exposure time and focus with honest AUTO positions, mapped onto Camera2 capture-request keys through `Camera2Interop`; ranges come from the camera characteristics, gauges use a log curve so the low end is usable.
- RAW: DNG + companion JPEG where the hardware reports the raw capability, gated in the UI; RAW captures bypass the software pipeline — the DNG is the product.
- Composition aids stay out of the way: rule-of-thirds / center / diagonal guides (off by default), a gravity-driven level line, and tap-to-focus / tap-to-meter.
- All expert surfaces are opt-in; the Auto path (PHOTO mode) stays simple.

**UI**

- Dark, monochrome design: black window, white hairline controls, monospace labels, round shutter/record buttons, dot indicator under the active mode. All visuals are original; only system fonts and standard Material icons are used.

## Building

Requirements:

- JDK 17 or newer (developed with JDK 21)
- Android SDK: platform 36

```bash
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:testDebugUnitTest   # JVM unit tests (178)
```

`local.properties` (git-ignored) points at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

Runtime requirement: Android 13 or newer (`minSdk 33` — the preview-parity GL effect reads `SurfaceRequest` surfaces directly).

## Layout

- `app/src/main/java/app/cameraplus/`
  - `camera/CameraSession.kt` — CameraX binding: still capture, video, RAW, focus/EV and MediaStore output
  - `camera/Camera2Recorder.kt` — Camera2 high-speed slow motion and MediaRecorder time lapse
  - `camera/LensPlan.kt`, `camera/RecorderPlan.kt` — lens/zoom and recorder planning math
  - `camera/Panorama*`, `camera/NightMerger.kt`, `camera/PortraitRenderer.kt` — bounded software mode prototypes
  - `camera/LookEffect.kt` — GL preview look (M3.3 preview parity)
  - `camera/VideoProfile.kt` — stabilization policy and zoom/lens plan (M4)
  - `camera/ManualControlPlan.kt` — manual-control gauge math (M5)
  - `camera/SessionProfile.kt` — session modes and the camera capability model
  - `camera/CaptureProfile.kt`, `camera/LevelMath.kt` — pure policy math
  - `ui/CameraScreen.kt`, `ui/GalleryScreen.kt`, `ui/FocusControls.kt`, `ui/CompositionOverlay.kt`, `ui/ProcessingSheet.kt`, `ui/CameraPlusApp.kt`, `ui/theme/Theme.kt` — Compose UI
  - `processing/` — software still pipeline (`ImageProcessor` pure math, `JpegProcessor` glue)
  - `settings/ProcessingSettings.kt` — processing-intent model
- `app/src/test/` — JVM unit tests (178)
- `dist/` — built APK

## License

MIT — see [LICENSE](LICENSE).
