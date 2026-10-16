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
| Portrait / Bokeh | ⬜ | planned, needs depth |
| Night | ⬜ | planned, multi-frame merge |
| Slow motion (1080p120/240/480, 4K120) | ⬜ | planned, camera2 high-speed session |
| Time lapse (X15…X960) | ⬜ | planned |
| Panorama (+ direction switch, super/standard) | ⬜ | planned |
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
| RAW / Pro RAW | ✅ | fixed this session |
| Flash off/on/torch/auto/screen | 🟡 | off/on/torch; no auto, no screen flash |
| Self-timer 3s / 10s | ⬜ | easy |
| Grid / mesh | ✅ | |
| Leveler | ✅ | |
| Burst / long-press shutter | ✅ | 4 queued captures |
| Google Lens, stickers, face detect | 🚫 | out of scope |
| QR scan | ⬜ | easy-ish, ML Kit or ZXing |

## Zoom / lens

| Feature | Camera+ | Note |
|---|---|---|
| 0.6x / 1x / 2x lens stops | ✅ | fixed this session |
| Pinch zoom over device range | ✅ | fixed this session |
| Per-mode zoom caps (night 30x, slo-mo 2x, timelapse 10x) | ⬜ | |
| Focal-length labels (14/24/28/35mm…) | ⬜ | cosmetic |

## Video

| Feature | Camera+ | Note |
|---|---|---|
| 1080p30/60, 4K30/60 picker | 🟡 | FHD fixed, no picker |
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
| 120 / 240 / 480 fps recording | ⬜ | camera2 `createConstrainedHighSpeedCaptureSession` |
| Resolution picker | ⬜ | |
| Zoom cap 2x | ⬜ | |

## Time lapse

| Feature | Camera+ | Note |
|---|---|---|
| Interval / speed slider (X15…X960) | ⬜ | |
| 1080p / 4K | ⬜ | |

## Panorama

| Feature | Camera+ | Note |
|---|---|---|
| Guided sweep capture | ⬜ | |
| Stitch output | ⬜ | |
| Direction switch | ⬜ | |

## Macro

| Feature | Camera+ | Note |
|---|---|---|
| Super macro (4cm) | ⬜ | depends on hardware close-focus distance |
| Tele macro / detail | ⬜ | |

## Settings / global

| Feature | Camera+ | Note |
|---|---|---|
| Processing sheet (tone/skin/sharp/…) | ✅ | |
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

1. **Processing OOM fix** — shots are failing, this is a correctness bug.
2. **Slow motion** — explicitly asked for, common mode.
3. **Time lapse** — camera2-only, reuses the video path.
4. **Panorama** — the most user-visible missing mode.
5. **Portrait** — software bokeh with honest labelling.
6. **Night** — multi-frame merge.
7. Aspect ratio, self-timer, video resolution picker, macro — cheap wins.

Portrait/night beauty quality will never match their vendor pipelines
(Facula bokeh, TurboNight, MotionManager); those are marked 🚫 deliberately
rather than faked.