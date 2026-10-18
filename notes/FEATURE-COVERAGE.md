# Camera+ vs Nothing Camera 16.1.01.93.20 — feature coverage

Ground truth mined from the decompiled original (`~/nothing-camera-reversed`,
`strings.xml`, `arrays.xml`, `camera_preferences_global.xml`, `jadx/sources`).

Audit pass 2026-10-17: every ✅/🟡/⬜ below was re-checked against the current
`app/` sources and stale rows were corrected (touch shutter, screen flash,
per-mode zoom caps, night 30x / portrait 4x zoom, B&W look). Subject tracking
was added in the same pass. Remaining gaps are called out under "Audit
results" right below.

Status legend:
- ✅ done
- 🟡 partial / approximation
- ⬜ not built yet
- 🚫 vendor-proprietary, clean-room substitute only (never original code)

---

## Audit results (2026-10-17)

Closed since the last pass: touch shutter, screen flash, per-mode zoom caps
(night 30x, portrait/macro 4x, slo-mo 2x, time-lapse 10x, panorama 1x), B&W
look, and the new subject tracking. Closed 2026-10-18: launcher shortcuts
(static core set plus a shortcut intent that reaches every mode), the
hold-shutter continuous shot (10-frame cap, PHOTO/EXPERT only), focal-length
zoom labels, the settings sheet's restore-defaults action, the watermark
camera-parameters line, `DCIM/Camera` storage for photos and videos, a
gallery-app picker in the camera settings, in-app video playback and the
expert histogram, zebra stripes and focus peaking (one shared analysis
stream), sticky exposure (KEEP EXPOSURE), anti-flicker 50/60 Hz anti-banding
and the GrapheneOS-style focus timeout.

Genuine remaining gaps (clean-room implementable):

| Gap | Effort | Note |
|---|---|---|
| Video encoder picker (H.264 / H.265) | medium | CameraX Recorder picks the codec; Camera2 recorder path needed |
| Video HDR / SHDR toggles | medium | device/extension dependent |
| Night video mode | medium | |
| Slow-motion mute/snapshot rules, time-lapse scene hints | small | cosmetic |
| Modes editor / reordering | small | cosmetic |

Vendor-only (🚫, not reproducible as-is): beauty-video pipeline, AI scene
detection, gender estimate, glyph/LED flash and glyph mirror, Morpho/Tetras
LUT packs, motion photo, Ultra HDR / Ultra XDR, 50MP remosaic, super
resolution, TurboNight variants, tele-macro, portrait depth segmentation,
ArcSoft/morpho panorama, smile shutter (vendor face ML).

---

## Improvements beyond the original (informed by other camera apps and user feedback)

Everything below is additive — the original app has no equivalent. Sources are
the public feature sets of Open Camera and GrapheneOS Secure Camera, plus the
Nothing community's own camera feedback threads (`nothing.community`).

| Improvement | Source | Status |
|---|---|---|
| Zebra stripes for blown highlights + a wash for crushed blacks | Open Camera; Nothing forum exposure requests | Implemented; device verification pending |
| Focus peaking for manual focus | Open Camera; Nothing forum request ("Focus Peaking") | Implemented; device verification pending |
| Sticky exposure (KEEP EXPOSURE) | Nothing forum request ("persistent exposure compensation setting") | Implemented; device verification pending |
| Anti-flicker 50/60 Hz anti-banding | Nothing forum request ("Anti-Banding settings") | Implemented; device verification pending |
| Focus timeout after tap-to-focus | GrapheneOS Secure Camera | Implemented; device verification pending |
| Exposure bracketing (3 or 5 shots, ±2 EV) | Open Camera; Nothing forum bracketing suggestion | Implemented; device verification pending |
| Ghost image / onion skin of the last shot | Open Camera | Implemented; device verification pending |
| RAW ONLY (DNG without a companion JPEG) | Open Camera | Implemented; device verification pending |
| Default focal (24/28/35 mm) and 1x tap cycling | Nothing forum request ("option to set 24,28 or 35mm as default focal length") | Implemented; device verification pending |
| Self-timer up to 60 s, shot series and voice countdown | Open Camera | Implemented; device verification pending |
| Barcode types beyond QR (2D matrix, product codes) | GrapheneOS Secure Camera | Implemented; device verification pending |
| Metadata stripping (GPS and device identity) | GrapheneOS Secure Camera | Implemented; device verification pending |
| EXIF author / copyright stamping | Open Camera | Implemented; device verification pending |
| Named camera presets (save / apply / delete) | Nothing forum request ("save my settings as a preset") | Implemented; device verification pending |
| Pin a setting to the top bar | Nothing forum request ("50MP quality should be on the top bar") | Implemented; device verification pending |

---

## Modes (the carousel)

| Original | Camera+ | Note |
|---|---|---|
| Photo | ✅ | |
| Video | ✅ | |
| Portrait / Bokeh | 🟡 | CameraX BOKEH extension if advertised; otherwise face-guided software blur, no depth map |
| Night | 🟡 | CameraX NIGHT extension if advertised; otherwise 4-frame downsampled merge |
| Slow motion (device-supported high-speed rates) | 🟡 | Camera2 constrained high-speed + MediaRecorder path; hardware unverified |
| Time lapse (X15…X960) | 🟡 | Camera2 + MediaRecorder interval path; hardware unverified |
| Panorama (+ direction switch, super/standard) | 🟡 | Continuous YUV sweep, gyro/level guide and progressive preview; three-band translation stitch, device check pending |
| Macro / Detail close-up | 🟡 | Camera2 macro AF mode when the lens reports it; 4cm/tele macro unverified |
| Expert / Manual | ✅ | ISO/shutter/MF/EV, WB presets/custom Kelvin, and a live luma histogram with mean and clip readout |
| Beauty / retouching | 🟡 | CameraX FACE_RETOUCH extension when available; fallback is basic smoothing |
| Beauty video | 🚫 | no video face-retouch pipeline |
| PanoNight | ⬜ | stretch |

## Photo

| Feature | Camera+ | Note |
|---|---|---|
| Aspect ratio picker (1:1, 4:3, 16:9, 20.5:9 …) | ✅ | CameraX crop ratios |
| HDR toggle (off/auto/on/force) | 🟡 | single-frame software HDR + capability-gated CameraX HDR extension; no Force/auto scene detection |
| Ultra HDR | 🚫 | vendor format |
| Quality 12MP / max JPEG | 🟡 | selects CameraX highest advertised output; does not promise 50MP remosaic |
| Still capture quality (FAST / BALANCED / HIGH) | 🟡 | FAST leaves HAL defaults, BALANCED requests advertised preview-tuned NR/edge modes, HIGH requests only advertised high-quality Camera2 processing stages and leaves ZSL; HAL may ignore hints, hardware validation pending |
| Motion photo (live) | 🚫 | vendor capture pipeline |
| RAW / Pro RAW | 🟡 | fixed wrong CameraX overload; hardware capture still unverified |
| Flash off/on/torch/auto/screen | 🟡 | off/on/auto, ScreenFlashView and video torch; Glyph/LED variants absent |
| Self-timer 3s / 10s | ✅ | |
| Grid / mesh | ✅ | |
| Leveler | ✅ | |
| Burst / continuous capture | ✅ | Tap captures queue up to four in flight; hold-shutter runs the 10-frame continuous shot (PHOTO/EXPERT; self-timer and special modes refuse with a status note); device unverified |
| Google Lens | 🚫 | requires the external Google app/service |
| Stickers | ⬜ | no clean-room sticker pack/editor |
| Face detection overlay | ⬜ | no face detector integrated |
| QR scan | ✅ | local ZXing QR-only analyzer; open/copy result actions |

## Zoom / lens

| Feature | Camera+ | Note |
|---|---|---|
| 0.6x / 1x / 2x lens stops | 🟡 | uses public CameraX/Camera2 lens metadata; the tested A001 exposes one public back camera, so 2x+ stops are digital and hidden OEM lenses are unavailable |
| Pinch zoom over device range | 🟡 | gestures + range clamp implemented; preview and saved still now share the same viewport |
| Per-mode zoom caps (night 30x, slo-mo 2x, timelapse 10x) | 🟡 | CameraX modes are capped; Camera2 slow-motion/time-lapse gestures remain limited |
| Focal-length labels (14/24/28/35mm…) | ✅ | Zoom chips label stops with their 35 mm-equivalent focal ("24mm") and the live readout shows ratio + mm; conversion uses the serving lens' focal and sensor size (43.27 mm frame — the original's zoom-slider basis). Device unverified |
| Resolution-aware lens choice for digital zoom | 🟡 | `LensPlan` scores each reachable lens by the sensor pixels left after the crop (readout ÷ ratio²), so a mid-zoom request is served by cropping the telephoto slightly instead of the wide lens a lot; the bound lens is kept while it stays within half the best score. Unit-tested; device behaviour unverified |

## Video

| Feature | Camera+ | Note |
|---|---|---|
| Video quality picker | 🟡 | HD/1080p/4K plus requested 30/60fps; camera/encoder combination remains device-dependent |
| EIS standard/super/off | 🟡 | on/off only |
| OIS control | 🟡 | Camera2 OIS request when reported by the bound lens |
| Action mode 60fps | 🟡 | 60fps + stabilization shortcut; stabilization behavior hardware-dependent |
| Night video | ⬜ | |
| Video noise reduction | 🟡 | Camera2 FAST spatial NR request when reported; temporal 3DNR is not implemented |
| OIS on/off | 🟡 | Camera2 OIS request when supported by the bound lens |
| H.264 / H.265 encoder picker | 🟡 | CameraX picks; no UI |
| Audio mute | ✅ | |
| Record location | 🟡 | explicit runtime opt-in, last/current fix stored in MediaStore/EXIF; hardware/provider behavior unverified |
| Mirror capture | 🟡 | front-camera still/video mirror is wired; output behavior unverified |
| Video snapshot | 🟡 | concurrent still ImageCapture is attempted; video-only fallback if unsupported |
| Beauty video | 🚫 | |

## Portrait

| Feature | Camera+ | Note |
|---|---|---|
| Bokeh level | 🟡 | CameraX OEM BOKEH extension when available; otherwise software ellipse, not depth segmentation |
| Effect presets (Velvet/Twist/Pentacle/Snowflake) | ⬜ | software bokeh can do light shapes |
| Retouching / beauty levels | 🚫 | vendor face pipeline |
| Portrait zoom 1–4x | ⬜ | |
| Front portrait | ⬜ | |

## Night

| Feature | Camera+ | Note |
|---|---|---|
| Night capture | 🟡 | CameraX OEM NIGHT extension when available; otherwise 4-frame software merge |
| Auto night detection | ⬜ | no scene classifier; user selects Night manually |
| Super night / turbo | 🚫 | vendor pipeline |

## Slow motion

| Feature | Camera+ | Note |
|---|---|---|
| Device-supported high-speed fps recording | 🟡 | camera2 `createConstrainedHighSpeedCaptureSession`; hardware unverified |
| Resolution picker | 🟡 | chooses largest advertised size up to 4K; no manual size picker |
| Zoom cap 2x | 🟡 | CameraX zoom UI capped; Camera2 high-speed camera has no live zoom controls |

## Time lapse

| Feature | Camera+ | Note |
|---|---|---|
| Interval / speed picker (X15…X960) | 🟡 | speed choices mapped to MediaRecorder capture intervals; hardware unverified |
| 1080p / 4K | 🟡 | uses the largest FHD-or-smaller MediaRecorder size for reliable low-rate capture; no user resolution picker |

## Panorama

| Feature | Camera+ | Note |
|---|---|---|
| Sweep capture + frame counter | 🟡 | downsampled frames; direction is a UI guide |
| Stitch output | 🟡 | translation-only cross-correlation + feather; no OEM-level correction |
| Direction switch | 🟡 | guide label only; stitching follows measured frame shifts |

## Macro

| Feature | Camera+ | Note |
|---|---|---|
| Super macro (4cm) | 🟡 | Camera2 macro AF mode where advertised; no distance guarantee |
| Tele macro / detail | ⬜ | |

## Settings / global

| Feature | Camera+ | Note |
|---|---|---|
| Processing sheet (tone/skin/sharp/…) | ✅ | Includes clean-room Natural/B&W/Warm/Cool/Vivid/Vintage looks |
| Volume-key and touch shutter | ✅ | both toggles ship in the processing sheet and are applied to their gestures; smile shutter is not implemented |
| In-app gallery (recent Camera+ media, photo viewer, share) | 🟡 | thumbnail opens the device's gallery app on the newest item (review hand-off); in-app grid/viewer remains as fallback with view/share/favorite/delete |
| Tap-to-focus/meter | 🟡 | CameraX metering action; hardware behavior unverified |
| Viewfinder exposure-compensation control | 🟡 | EV slider wired to CameraX; hardware behavior unverified |
| Long-press focus/meter lock | 🟡 | long-duration metering action; hardware behavior unverified |
| Pinch-to-zoom / zoom readout | 🟡 | implemented, hardware behavior unverified |
| Aspect ratio picker | ✅ | |
| Shutter sound | ✅ | explicit on/off; platform may enforce regional behavior |
| Save position | ✅ | photos and videos go to the standard `DCIM/Camera` folder, as the original does; still no user-selectable path or SD card |
| Modes editor / reordering | ⬜ | cosmetic |
| Watermark (brand/date/time/location/author/parameters) | ✅ | software stamp options including the original's camera-parameters line; original artwork/styles not mirrored |
| AI scene detection | ⬜ | no local scene classifier |
| Colour mode natural/vivid | 🟡 | six clean-room saved/preview looks; no scene-driven original Colour Mode |
| Presets / user LUT import | ⬜ | no LUT import/export |
| Filters (Morpho/Tetras + user LUT) | 🟡 | six clean-room looks; original packs/renderers and LUT import are absent |

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
| Mode carousel with configurable/reorderable mode list | 🟡 | clean-room modes are listed; reorder editor absent |
| Photo / Video / Portrait / Beauty / Manual / Panorama / Night / Time lapse / Slow motion / Macro / QR entries | 🟡 | public-API modes are available; PanoNight is absent |
| Beauty photo mode | 🟡 | CameraX FACE_RETOUCH extension if advertised; otherwise software skin smoothing |
| Beauty video mode | 🚫 | `ProductConfig.isSupportBeautyVideo`; no video face-retouch pipeline |
| Bokeh mode distinct from Portrait | 🟡 | clean-room simulated portrait blur only |
| Panorama-night / PanoHDR mode | ⬜ | `ModeIndex.PANORAMA_NIGHT_ID`, proprietary HDR path |
| Launcher shortcuts for Photo front/back, Video, Portrait, Time lapse, Panorama, Manual, Slo-mo, Macro, Night | 🟡 | Static shortcuts for the five highest ranks (Photo both facings, Video, Portrait, Time lapse); `app.cameraplus.action.SHORTCUT_CAMERA` reaches every mode via `ModeShortcut`; launchers show up to `getMaxShortcutCountPerActivity()`. Shortcut art reuses the app icon; device unverified |
| Mode-switch confirmation/accessibility hints | ⬜ | `confirm_mode_tip`, `accessibility_switch_mode` |

### Photo capture and still options

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Aspect ratios 1:1, 4:3, 16:9, 20.5:9, 20:9, 19.5:9, 19:9, 18:9 | ✅ | `pref_camera_aspect_ratio_entryvalues` |
| HDR Off / On / Force and auto-detection | 🟡 | software curve + hardware extension when available; no Force/scene detection |
| Ultra HDR / Ultra XDR output | 🚫 | `UltraHDRFragment`; original format pipeline not reproduced |
| Photo size 12MP / 50MP | 🟡 | Standard/MAX JPEG; max output is hardware-reported and not guaranteed to be 50MP |
| Remosaic 50MP capture | 🚫 | `RemosaicMode`, `NT_REMOSAIC_CAPTURE_KEY` |
| Super-resolution capture / zoom | ⬜ | `SRModeHelper`, `RawSuperResolutionHelper` |
| Motion photo / live photo on/off | 🚫 | `MotionManager`, `TemplateMtkMotionMode` |
| RAW + companion JPEG / DNG | 🟡 | correct CameraX overload implemented; actual device capability/output unverified. RAW ONLY (DNG with no companion JPEG) added 2026-10-18 |
| Flash Off / On / Auto | ✅ | CameraX OFF/AUTO/ON; hardware behavior unverified |
| Steady torch | ✅ | torch available in Video mode |
| Screen flash / Glyph flash / LED variants | 🟡 | ScreenFlashView screen flash cycles with the flash button; Glyph/LED flash variants are vendor hardware |
| Self timer Off / 3s / 10s | ✅ | |
| Grid / mesh-line options | 🟡 | guide cycle exists; not all original mesh variants |
| Leveler / gradienter | ✅ | gravity-driven horizon line |
| Burst / continuous capture count | ✅ | Tap captures queue up to four in flight; hold-shutter runs the 10-frame continuous shot (the original's storage burst limit); device unverified |
| Long-press shutter burst behavior | ✅ | Hold-shutter runs continuous shot up to 10 frames (the original's burst count limit; `SettingKeys.KEY_HOLD_SHUTTER_KEY` = `CONTINUOUS_SHOT`). The drag-to-quick-record gesture is not reproduced; device unverified |
| Google Lens entry point | ⬜ | `GoogleFilter` |
| Sticker picker / sticker capture | ⬜ | original sticker assets intentionally absent |
| Face detection overlay / capture behavior | ⬜ | `FaceDetectionManager` |

### Video, slow motion and time lapse

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| 1080p30 / 1080p60 / 4K30 / 4K60 choices | 🟡 | quality and 30/60fps requested separately; valid combinations vary by camera/encoder |
| Video HDR / SHDR toggles | ⬜ | `pref_video_hdr_plus_key`, `pref_video_shdr_key` |
| Action Mode 60fps | 🟡 | 60fps + stabilization shortcut; hardware behavior unverified |
| Standard / Super EIS / Off | 🟡 | capability-detected standard/preview stabilization toggle; no Super EIS mode |
| Night video | ⬜ | `pref_video_night_key` |
| Video noise reduction | 🟡 | Camera2 FAST spatial NR request when camera reports support; not temporal 3DNR |
| Beauty video and retouch levels | 🚫 | vendor face pipeline |
| Recording red-light preference | ⬜ | `pref_video_record_red_light_key` |
| H.264 / H.265 selector | ⬜ | CameraX/MediaRecorder selects codec; no user selector |
| Video microphone mute | ✅ | microphone toggle in standard Video mode |
| OIS preference | 🟡 | independent Camera2 OIS request when supported by the lens |
| Record-location / geotag toggle | 🟡 | permission-gated current/last fix written where provider supports it |
| Mirror capture | 🟡 | front-camera still/video mirroring is wired; device behavior unverified |
| Zoom/lens switching while recording | 🟡 | UI zoom planning exists; hardware transition behavior unverified |
| Video snapshot while recording | 🟡 | concurrent CameraX still use case with video-only fallback; device support unverified |
| Slow motion 1080p120/240/480 and 4K120 | 🟡 | dynamically enumerates advertised high-speed sizes/rates up to 4K; device behavior unverified |
| Slow-motion supported-rate picker | 🟡 | device-reported rate picker; needs hardware validation |
| Slow-motion zoom cap and mute/snapshot rules | 🟡 | 2x zoom cap implemented (`ModeZoomPlan`); mute/snapshot rules absent |
| Time lapse 1080p / 4K choice | 🟡 | bounded to FHD-or-smaller to avoid `prepare()` failures in low-rate Camera2 sessions |
| Time-lapse X15 / X30 / X60 / X120 / X240 / X480 / X960 choices | 🟡 | choices map to capture intervals; output cadence unverified on hardware |
| Time-lapse scene-specific speed suggestions | ⬜ | traffic/cloud/sunrise/night-sky hints absent |

### Portrait, night, panorama and macro

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Portrait / Bokeh still mode | 🟡 | face-guided simulated blur when a face is detected; explicitly no depth map |
| Adjustable bokeh / facula level | 🟡 | software blur-strength slider |
| Portrait light-shape effects: Original / Velvet / Twist / Pentacle / Snowflake | ⬜ | `PresetConstant.PORTRAIT_EFFECT_ARRAY` |
| Portrait beauty / smoothing / eye / face / whitening controls | 🚫 | proprietary face segmentation/retouch pipeline |
| Gender estimate UI | ⬜ | `pref_gender_estimate_key` |
| Portrait optical zoom 1x–4x | ✅ | `ModeZoomPlan` caps portrait at 4x |
| Front portrait / portrait repair | ⬜ | `PortraitRepairMode` |
| Dual/single-camera portrait bokeh and shine tuning | 🚫 | OEM depth and ISP implementation absent |
| Night photo mode / multi-frame merge | 🟡 | 4-frame software alignment/merge at bounded resolution |
| Automatic/forced night-scene detection | ⬜ | no scene detector or force-night toggle |
| Night Lite / Turbo Night / front-night / UW-night variants | 🚫 | OEM capture pipeline, `TemplateTurboNightMode` |
| Night zoom ranges up to 30x | ✅ | `ModeZoomPlan` NIGHT cap 30x |
| Panorama sweep capture and stitch | 🟡 | three-band horizontal correlation stitch; output downsampled |
| Panorama left/right direction guide | 🟡 | guide toggle; stitcher follows measured shifts |
| Standard / Super panorama | ⬜ | no OEM quality mode |
| Panorama capture line/speed/alignment warnings | 🟡 | gyro/accelerometer guide, speed warning and live accepted-frame alignment; device check pending |
| Panorama-night / ArcSoft/morpho engine | 🚫 | original third-party/vendor pipeline not reproduced |
| Super Macro / ~4cm close focus | 🟡 | Camera2 macro AF mode where advertised; no 4cm distance guarantee |
| Tele-macro / Detail close-up | ⬜ | `ProductConfig.isSupportTeleMacro` |
| Auto macro switching in photo/video | ⬜ | no lens-distance auto-switch logic |
| Macro zoom 1x/2x/4x, exposure and session config | ⬜ | original `STREAM_CONFIG_MODE_MACRO` path absent |

### Expert controls, viewfinder gestures and zoom

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| Expert / Manual photo mode | ✅ | ISO, exposure time, manual focus, RAW and the live histogram present |
| ISO Auto and manual ISO 50–6400 | 🟡 | device-reported ISO range, not original fixed options |
| Shutter Auto / 1/12000…30s | 🟡 | device-reported exposure range |
| Manual focus progress control | 🟡 | manual focus slider; device range varies |
| White-balance presets and custom temperature | 🟡 | Camera2 AWB presets + approximate Kelvin gains; sensor calibration differs |
| Global exposure compensation controls | 🟡 | AE-compensation slider is available after tapping the preview |
| Tap-to-focus and tap-to-meter | 🟡 | CameraX combined AF/AE action; hardware behavior unverified |
| Long-press AF/AE lock | 🟡 | long-duration metering action; hardware behavior unverified |
| On-viewfinder brightness/EV slider | 🟡 | CameraX exposure-compensation index; hardware behavior unverified |
| Subject tracking (tap a subject, focus follows it) | ✅ | clean-room zero-mean NCC tracker (2026-10-17); top-bar toggle, reticle overlay, AF/AE metering follows the subject in photo modes and video; device-verified coordinate mapping and metering cadence |
| Expert histogram and level meter | ✅ | live luma histogram (32 bars, mean, black/white clip percentages) in EXPERT behind a HISTOGRAM toggle, plus ZEBRAS and PEAKING overlays sharing the same analysis stream; the composition level line is present. The original's separate level-meter widget and Pro-meter screen are not mirrored |
| Lens stops from physical focal lengths (0.6x/1x/2x/…) | 🟡 | planner handles logical and separate cameras; actual phone inventory unverified |
| Pinch-to-zoom and manual zoom across device range | 🟡 | implemented and clamped; range depends on hardware |
| Original focal labels (14/24/28/35/50/85mm) | ✅ | Computed from HAL focal + sensor size (the original's own convention: 35 mm-equivalent, 43.27 mm diagonal); shown on zoom chips and the live readout; device unverified |

### AI, filters, watermark, gallery and global settings

| Original item | Status | Evidence / Camera+ note |
|---|---|---|
| AI scene detection / ASD and scene-specific effects | ⬜ | no generic scene classifier; original vendor models not included |
| Auto/Vivid/Natural color-mode picker and scene descriptions | 🟡 | general processing settings exist; original scene-driven mode is absent |
| Morpho/Tetras filter packs and filter-strength picker | 🚫 | vendor renderers/assets intentionally excluded |
| User LUT import / presets and preset sharing | ⬜ | no LUT import/export yet |
| Black-and-white filter | ✅ | clean-room B&W look in the processing sheet |
| Watermark on/off and logo/time/date/location/author/camera-parameters options | ✅ | software text stamp; camera parameters read from the still's EXIF plus session lens data; original artwork/style not mirrored |
| Glyph mirror preference | ⬜ | `glyph_mirror` |
| QR-code scanning | ✅ | local ZXing QR analyzer with open/copy actions; not every barcode type |
| In-app recent gallery and photo viewer | ✅ | gallery button hands off to the system gallery app (newest item, or the pinned app); in-app grid/viewer kept as the no-gallery-app fallback |
| In-app video playback | ✅ | platform `VideoView` with media controls, looping playback, released when the viewer closes |
| Gallery delete/favorite/info actions | 🟡 | delete/favorite/date/size implemented; crop/rotate/editor absent |
| Motion-photo playback in gallery | 🚫 | motion photo capture absent |
| Shutter sound toggle | ✅ | MediaActionSound toggle; hardware/region policy may override |
| Volume-key still shutter | ✅ | explicit opt-in and only while enabled |
| Touch / smile shutter | 🟡 | touch shutter ships as a ProcessingSheet toggle and captures on preview taps; smile shutter absent |
| Save-position / default-gallery selection | ✅ | captures go to `DCIM/Camera` and the settings sheet can pin a gallery app (automatic by default, uninstalled pins fall back); no user-selectable save path/SD card |
| Anti-flicker, exposure-metering, dehaze/distortion controls | 🟡 | 50/60 Hz anti-banding is implemented from the camera's own advertised modes (2026-10-18); exposure metering weighting, dehaze and distortion controls remain vendor-only |
| Restore defaults | ✅ | Camera settings sheet: RESTORE DEFAULTS resets capture preferences, processing look and watermark (geotag switched off); device unverified |
