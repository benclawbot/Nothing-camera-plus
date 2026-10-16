# Camera+ vs Nothing Camera 16.1.01.93.20 — feature coverage

Ground truth mined from the decompiled original (`~/nothing-camera-reversed`,
`strings.xml`, `arrays.xml`, `camera_preferences_global.xml`, `jadx/sources`).

Status legend:
- ✅ done
- 🟡 partial / approximation
- ⬜ not built yet
- 🚫 vendor-proprietary, clean-room substitute only (never original code)

---

## Modes (the carousel)

| Original | Camera+ | Note |
|---|---|---|
| Photo | ✅ | |
| Video | ✅ | |
| Portrait / Bokeh | 🟡 | UI + centre-weighted software blur; no depth map, output downsampled |
| Night | 🟡 | UI + 4-frame alignment/merge; output downsampled, device validation pending |
| Slow motion (device-supported high-speed rates) | 🟡 | Camera2 constrained high-speed + MediaRecorder path; hardware unverified |
| Time lapse (X15…X960) | 🟡 | Camera2 + MediaRecorder interval path; hardware unverified |
| Panorama (+ direction switch, super/standard) | 🟡 | UI + translation-only software stitch; no rotation correction, downsampled |
| Macro / Detail close-up | ⬜ | planned |
| Expert / Manual | 🟡 | ISO/shutter/MF/EV present; WB, histogram, level meter missing |
| Beauty / retouching | 🚫 | needs their face pipeline |
| Beauty video | 🚫 | same |
| PanoNight | ⬜ | stretch |

## Photo

| Feature | Camera+ | Note |
|---|---|---|
| Aspect ratio picker (1:1, 4:3, 16:9, 20.5:9 …) | ⬜ | easy, CameraX crop selectors |
| HDR toggle (off/auto/on/force) | 🟡 | software tone curve only |
| Ultra HDR | 🚫 | vendor format |
| Quality 12MP / 50MP | ⬜ | 50MP needs remosaic (vendor) — 12MP achievable |
| Motion photo (live) | 🚫 | vendor capture pipeline |
| RAW / Pro RAW | 🟡 | fixed wrong CameraX overload; hardware capture still unverified |
| Flash off/on/torch/auto/screen | 🟡 | off/on/torch; no auto, no screen flash |
| Self-timer 3s / 10s | ✅ | |
| Grid / mesh | ✅ | |
| Leveler | ✅ | |
| Burst / long-press shutter | ✅ | 4 queued captures |
| Google Lens, stickers, face detect | 🚫 | out of scope |
| QR scan | ⬜ | easy-ish, ML Kit or ZXing |

## Zoom / lens

| Feature | Camera+ | Note |
|---|---|---|
| 0.6x / 1x / 2x lens stops | 🟡 | logical/physical focal planning implemented; hardware enumeration unverified |
| Pinch zoom over device range | 🟡 | gestures + range clamp implemented; hardware range unverified |
| Per-mode zoom caps (night 30x, slo-mo 2x, timelapse 10x) | ⬜ | |
| Focal-length labels (14/24/28/35mm…) | ⬜ | cosmetic |

## Video

| Feature | Camera+ | Note |
|---|---|---|
| Video quality picker | 🟡 | HD/1080p/4K quality tiers; no 30/60 fps selector yet |
| EIS standard/super/off | 🟡 | on/off only |
| Action mode 60fps | ⬜ | |
| Night video | ⬜ | |
| H.264 / H.265 encoder picker | 🟡 | CameraX picks; no UI |
| Audio mute | ✅ | |
| Record location | ⬜ | needs location permission |
| Mirror capture | ⬜ | |
| Video snapshot | ⬜ | |
| Beauty video | 🚫 | |

## Portrait

| Feature | Camera+ | Note |
|---|---|---|
| Bokeh level | ⬜ | needs depth |
| Effect presets (Velvet/Twist/Pentacle/Snowflake) | ⬜ | software bokeh can do light shapes |
| Retouching / beauty levels | 🚫 | vendor face pipeline |
| Portrait zoom 1–4x | ⬜ | |
| Front portrait | ⬜ | |

## Night

| Feature | Camera+ | Note |
|---|---|---|
| Night capture | ⬜ | multi-frame merge planned |
| Auto night detection | 🟡 | software scene guess only |
| Super night / turbo | 🚫 | vendor pipeline |

## Slow motion

| Feature | Camera+ | Note |
|---|---|---|
| Device-supported high-speed fps recording | 🟡 | camera2 `createConstrainedHighSpeedCaptureSession`; hardware unverified |
| Resolution picker | 🟡 | chooses largest supported size at or below 1080p; no user resolution picker |
| Zoom cap 2x | ⬜ | |

## Time lapse

| Feature | Camera+ | Note |
|---|---|---|
| Interval / speed picker (X15…X960) | 🟡 | speed choices mapped to MediaRecorder capture intervals; hardware unverified |
| 1080p / 4K | 🟡 | selects largest MediaRecorder size; no user resolution picker |

## Panorama

| Feature | Camera+ | Note |
|---|---|---|
| Sweep capture + frame counter | 🟡 | downsampled frames; direction is a UI guide |
| Stitch output | 🟡 | translation-only cross-correlation + feather; no OEM-level correction |
| Direction switch | 🟡 | guide label only; stitching follows measured frame shifts |

## Macro

| Feature | Camera+ | Note |
|---|---|---|
| Super macro (4cm) | ⬜ | depends on hardware close-focus distance |
| Tele macro / detail | ⬜ | |

## Settings / global

| Feature | Camera+ | Note |
|---|---|---|
| Processing sheet (tone/skin/sharp/…) | ✅ | |
| In-app gallery (recent Camera+ media, photo viewer, share) | ✅ | video opens in system player |
| Tap-to-focus/meter | 🟡 | CameraX metering action; hardware behavior unverified |
| Viewfinder exposure-compensation control | 🟡 | EV slider wired to CameraX; hardware behavior unverified |
| Long-press focus/meter lock | 🟡 | long-duration metering action; hardware behavior unverified |
| Pinch-to-zoom / zoom readout | 🟡 | implemented, hardware behavior unverified |
| Aspect ratio picker | ⬜ | |
| Shutter sound | ⬜ | |
| Save position | ⬜ | |
| Modes editor / reordering | ⬜ | cosmetic |
| Watermark (logo/time/date/location/params) | ⬜ | doable in software |
| AI scene detection | 🚫 | vendor ML |
| Colour mode natural/vivid | 🟡 | processing sheet covers |
| Presets / user LUT import | ⬜ | |
| Filters (Morpho/Tetras + user LUT) | 🚫 | vendor renderers + assets |

---

## Priority order for the rebuild

1. Device validation of RAW, processing fallback, focus, EV and zoom on the target phone.
2. Device validation of slow motion/time lapse (high-speed capability and MediaRecorder cadence).
3. Device validation of panorama/night/portrait memory and output quality.
4. Aspect ratio, full video fps/resolution controls, macro, and remaining settings.

No item marked 🟡 is considered device-verified. Portrait/night/panorama are
clean-room software approximations and do not match the OEM pipelines (Facula
bokeh, TurboNight, ArcSoft/morpho stitching); their UI labels and output notes
state those limitations rather than claiming parity. The still-open ⬜ items
are not covered yet, and the 🚫 items rely on vendor-only assets or services.

---

## Full original-function inventory (discovery appendix)

The tables above are the compact rebuild tracker. This appendix preserves the
individual user-facing controls and per-mode options found during the source
inventory. “Not covered” is intentionally explicit: having a mode label or a
unit-tested math component does not count as hardware feature parity.

### Mode carousel and launcher surfaces

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Mode carousel with configurable/reorderable mode list | ⬜ | `CameraModePreference`, `mode_edit_drag_hint` |
| Photo / Video / Portrait / Manual / Panorama / Night / Time lapse / Slow motion / Macro mode entries | 🟡 | entries now include photo, video, portrait, panorama, night, slow-mo, time lapse, expert |
| Beauty photo and Beauty video modes | 🚫 | `ProductConfig.isSupportBeauty*`; proprietary face pipeline |
| Bokeh mode distinct from Portrait | 🟡 | clean-room simulated portrait blur only |
| Panorama-night / PanoHDR mode | ⬜ | `ModeIndex.PANORAMA_NIGHT_ID`, proprietary HDR path |
| Launcher shortcuts for Photo front/back, Video, Portrait, Time lapse, Panorama, Manual, Slo-mo, Macro, Night | ⬜ | `CameraMode`, `CameraConfig.CAMERA_SUBMODE_*` |
| Mode-switch confirmation/accessibility hints | ⬜ | `confirm_mode_tip`, `accessibility_switch_mode` |

### Photo capture and still options

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Aspect ratios 1:1, 4:3, 16:9, 20.5:9, 20:9, 19.5:9, 19:9, 18:9 | ⬜ | `pref_camera_aspect_ratio_entryvalues` |
| HDR Off / On / Force and auto-detection | 🟡 | current HDR is a software tone curve, not multi-frame/vendor HDR |
| Ultra HDR / Ultra XDR output | 🚫 | `UltraHDRFragment`; original format pipeline not reproduced |
| Photo size 12MP / 50MP | ⬜ | `pref_camera_toggle_entries`; hardware remosaic path absent |
| Remosaic 50MP capture | 🚫 | `RemosaicMode`, `NT_REMOSAIC_CAPTURE_KEY` |
| Super-resolution capture / zoom | ⬜ | `SRModeHelper`, `RawSuperResolutionHelper` |
| Motion photo / live photo on/off | 🚫 | `MotionManager`, `TemplateMtkMotionMode` |
| RAW + companion JPEG / DNG | 🟡 | correct CameraX overload implemented; actual device capability/output unverified |
| Flash Off / On / Auto | 🟡 | Off/On are implemented; Auto selection is absent |
| Steady torch | ✅ | torch available in Video mode |
| Screen flash / Glyph flash / LED variants | ⬜ | `ScreenFlashManager`, glyph flash strings |
| Self timer Off / 3s / 10s | ✅ | |
| Grid / mesh-line options | 🟡 | guide cycle exists; not all original mesh variants |
| Leveler / gradienter | ✅ | gravity-driven horizon line |
| Burst / continuous capture count | 🟡 | up to four queued captures; original continuous/burst semantics differ |
| Long-press shutter burst behavior | ⬜ | no long-press continuous-burst gesture |
| Google Lens entry point | ⬜ | `GoogleFilter` |
| Sticker picker / sticker capture | ⬜ | original sticker assets intentionally absent |
| Face detection overlay / capture behavior | ⬜ | `FaceDetectionManager` |

### Video, slow motion and time lapse

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| 1080p30 / 1080p60 / 4K30 / 4K60 choices | 🟡 | HD/1080p/4K tiers exist; explicit fps selector is missing |
| Video HDR / SHDR toggles | ⬜ | `pref_video_hdr_plus_key`, `pref_video_shdr_key` |
| Action Mode 60fps | ⬜ | `pref_camera_video_action_mode` |
| Standard / Super EIS / Off | 🟡 | capability-detected standard/preview stabilization toggle; no Super EIS mode |
| Night video | ⬜ | `pref_video_night_key` |
| Video 3DNR | ⬜ | `pref_video_3dnr_title` |
| Beauty video and retouch levels | 🚫 | vendor face pipeline |
| Recording red-light preference | ⬜ | `pref_video_record_red_light_key` |
| H.264 / H.265 selector | ⬜ | CameraX/MediaRecorder selects codec; no user selector |
| Video microphone mute | ✅ | microphone toggle in standard Video mode |
| OIS preference | 🟡 | hardware stabilization capability is exposed; no independent OIS setting |
| Record-location / geotag toggle | ⬜ | `pref_camera_recordlocation_key`; no location permission/metadata |
| Mirror capture | ⬜ | `pref_capture_mirror_key` |
| Zoom/lens switching while recording | 🟡 | UI zoom planning exists; hardware transition behavior unverified |
| Video snapshot while recording | ⬜ | no snapshot action |
| Slow motion 1080p120/240/480 and 4K120 | 🟡 | dynamically enumerates high-speed rates up to 1080p; 4K120 is not offered |
| Slow-motion supported-rate picker | 🟡 | device-reported rate picker; needs hardware validation |
| Slow-motion zoom cap and mute/snapshot rules | ⬜ | no mode-specific zoom cap or snapshot action |
| Time lapse 1080p / 4K choice | 🟡 | picks largest MediaRecorder size; no user resolution selector |
| Time-lapse X15 / X30 / X60 / X120 / X240 / X480 / X960 choices | 🟡 | choices map to capture intervals; output cadence unverified on hardware |
| Time-lapse scene-specific speed suggestions | ⬜ | traffic/cloud/sunrise/night-sky hints absent |

### Portrait, night, panorama and macro

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Portrait / Bokeh still mode | 🟡 | centre-weighted simulated blur, explicitly no depth map |
| Adjustable bokeh / facula level | 🟡 | software blur-strength slider |
| Portrait light-shape effects: Original / Velvet / Twist / Pentacle / Snowflake | ⬜ | `PresetConstant.PORTRAIT_EFFECT_ARRAY` |
| Portrait beauty / smoothing / eye / face / whitening controls | 🚫 | proprietary face segmentation/retouch pipeline |
| Gender estimate UI | ⬜ | `pref_gender_estimate_key` |
| Portrait optical zoom 1x–4x | ⬜ | regular lens zoom only |
| Front portrait / portrait repair | ⬜ | `PortraitRepairMode` |
| Dual/single-camera portrait bokeh and shine tuning | 🚫 | OEM depth and ISP implementation absent |
| Night photo mode / multi-frame merge | 🟡 | 4-frame software alignment/merge at bounded resolution |
| Automatic/forced night-scene detection | ⬜ | no scene detector or force-night toggle |
| Night Lite / Turbo Night / front-night / UW-night variants | 🚫 | OEM capture pipeline, `TemplateTurboNightMode` |
| Night zoom ranges up to 30x | ⬜ | no mode-specific ceiling |
| Panorama sweep capture and stitch | 🟡 | translation-only correlation stitch; output downsampled |
| Panorama left/right direction guide | 🟡 | guide toggle; stitcher follows measured shifts |
| Standard / Super panorama | ⬜ | no OEM quality mode |
| Panorama capture line/speed/alignment warnings | ⬜ | basic frame count only; no live motion guidance |
| Panorama-night / ArcSoft/morpho engine | 🚫 | original third-party/vendor pipeline not reproduced |
| Super Macro / ~4cm close focus | ⬜ | no dedicated close-focus mode |
| Tele-macro / Detail close-up | ⬜ | `ProductConfig.isSupportTeleMacro` |
| Auto macro switching in photo/video | ⬜ | no lens-distance auto-switch logic |
| Macro zoom 1x/2x/4x, exposure and session config | ⬜ | original `STREAM_CONFIG_MODE_MACRO` path absent |

### Expert controls, viewfinder gestures and zoom

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Expert / Manual photo mode | 🟡 | ISO, exposure time, manual focus and RAW present |
| ISO Auto and manual ISO 50–6400 | 🟡 | device-reported ISO range, not original fixed options |
| Shutter Auto / 1/12000…30s | 🟡 | device-reported exposure range |
| Manual focus progress control | 🟡 | manual focus slider; device range varies |
| White-balance presets and custom temperature | ⬜ | original Auto/incandescent/daylight/fluorescent/cloudy/custom absent |
| Global exposure compensation controls | 🟡 | AE-compensation slider is available after tapping the preview |
| Tap-to-focus and tap-to-meter | 🟡 | CameraX combined AF/AE action; hardware behavior unverified |
| Long-press AF/AE lock | 🟡 | long-duration metering action; hardware behavior unverified |
| On-viewfinder brightness/EV slider | 🟡 | CameraX exposure-compensation index; hardware behavior unverified |
| Expert histogram and level meter | ⬜ | composition level exists, but no histogram or Pro meter |
| Lens stops from physical focal lengths (0.6x/1x/2x/…) | 🟡 | planner handles logical and separate cameras; actual phone inventory unverified |
| Pinch-to-zoom and manual zoom across device range | 🟡 | implemented and clamped; range depends on hardware |
| Original focal labels (14/24/28/35/50/85mm) | ⬜ | current UI shows zoom ratios only |

### AI, filters, watermark, gallery and global settings

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| AI scene detection / ASD and scene-specific effects | 🚫 | proprietary models/services not included |
| Auto/Vivid/Natural color-mode picker and scene descriptions | 🟡 | general processing settings exist; original scene-driven mode is absent |
| Morpho/Tetras filter packs and filter-strength picker | 🚫 | vendor renderers/assets intentionally excluded |
| User LUT import / presets and preset sharing | ⬜ | no LUT import/export yet |
| Black-and-white filter | ⬜ | no dedicated filter toggle |
| Watermark on/off and logo/time/date/location/camera-parameter/author options | ⬜ | software watermark pipeline absent |
| Glyph mirror preference | ⬜ | `glyph_mirror` |
| QR-code scanning | ⬜ | `QrCodeMode`, no scanner dependency |
| In-app recent gallery and photo viewer | 🟡 | Camera+ MediaStore grid/viewer/share implemented; not a general device gallery |
| In-app video playback | ⬜ | video opens in a system player instead |
| Gallery delete/favorite/info/editor/crop/rotate actions | ⬜ | not implemented |
| Motion-photo playback in gallery | 🚫 | motion photo capture absent |
| Shutter sound toggle | ⬜ | no sound preference |
| Volume-key / touch / smile shutter | ⬜ | `pref_camera_volume_key`, `pref_shutter_touch_key`, `pref_shutter_smile_key` |
| Save-position / default-gallery selection | ⬜ | app currently writes to Camera+ MediaStore folders |
| Anti-flicker, exposure-metering, dehaze/distortion controls | ⬜ | advanced vendor settings not implemented |
| Restore defaults | ⬜ | no global settings screen yet |
