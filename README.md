# Camera+

An Android camera app, built from scratch as a clean-room project.

**Clean-room statement.** Every file in this repository is original work written for this project, against public Android documentation and publicly posted user feedback. No code, resources, assets, fonts, icons, or other material from any shipped application — decompiled, extracted, or otherwise — is contained in or used by this repository. Third-party dependencies (AndroidX, CameraX, Jetpack Compose, Material Components) are used under their own open-source licenses.

**Not affiliated.** Camera+ is an independent project. It is not affiliated with, endorsed by, or connected to any device manufacturer.

## Status

| Milestone | State |
| --- | --- |
| M1 — Skeleton (preview, capture, UI shell) | Done, build-validated |
| M2 — Instant shutter | Core done (ZSL capture + queueing) |
| M3 — Processing control (Natural + strengths) | Settings + software pipeline done; preview parity pending |
| M4 — Video stabilization & lens switching | Planned |
| M5 — Composition aids | Planned |
| M6 — RAW / Camera2 depth | Planned |

Details in [ROADMAP.md](ROADMAP.md).

**Validation honesty:** this project is validated by compilation (`assembleDebug`) and JVM unit tests only. It has not been run on a physical device yet; capture latency, processing quality and speed, and stabilization behaviour are unverified until it is.

## Features (M1)

- Live preview via CameraX (`Preview`).
- Still capture (`ImageCapture`) saved to `Pictures/Camera+/` through MediaStore.
- Flash toggle and front/back lens flip.
- Mode selector scaffold (PHOTO / VIDEO / PORTRAIT) — only PHOTO captures for now.
- Processing sheet: `Natural` (neutral) plus adjustable sharpening / noise reduction / HDR / skin smoothing; the intent is persisted.
- Non-Natural captures are processed in software before saving (full-resolution JPEG decode/encode on a background thread).
- Dark, monochrome UI: black window, white controls, monospace labels, round shutter, dot indicator under the active mode. All visuals are original; only system fonts and standard Material icons are used.

## Building

Requirements:

- JDK 17 or newer (developed with JDK 21)
- Android SDK: platform 35, build-tools 35.0.0

```bash
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:testDebugUnitTest   # unit tests
```

`local.properties` (git-ignored) points at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Layout

- `app/src/main/java/app/cameraplus/`
  - `camera/CameraSession.kt` — CameraX binding, capture, MediaStore output
  - `ui/CameraPlusApp.kt`, `ui/CameraScreen.kt`, `ui/theme/Theme.kt` — Compose UI
  - `settings/ProcessingSettings.kt` — processing-intent model (M3 groundwork)
- `app/src/test/` — JVM unit tests

## License

MIT — see [LICENSE](LICENSE).
