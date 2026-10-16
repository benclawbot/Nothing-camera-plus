# v1.1 plan — parity pass against the original

Working list driven by the gap report. Status is updated as each item lands.

## A. Confirmed crash / bug fixes (do first, no discovery needed)

- [ ] A1. RAW capture crashes the app. Root cause: `ImageCapture.takePicture(OutputFileOptions, …)`
      is documented to throw `IllegalArgumentException` when the use case output format is
      `OUTPUT_FORMAT_RAW_JPEG`; RAW needs the two-options overload
      `takePicture(rawOptions, jpegOptions, executor, callback)`. Fix in `CameraSession.captureRaw`.
- [ ] A2. `setZoomRatio` is never clamped to the bound camera's `ZoomState`, so out-of-range
      values throw / snap badly. Clamp + report through `onError`.
- [ ] A3. Default lens: `requireLensFacing(BACK)` binds whatever the HAL lists first (the 2x lens).
      Need an explicit wide/main selector.
- [ ] A4. Gallery button is a stub (`status = "Gallery view is not part of this build"`).
- [ ] A5. No pinch-to-zoom; no 0.6/1/2 lens stop buttons wired to real lenses.

## B. Feature parity (needs the original-feature discovery pass)

Discovery agents (jadx/apktool corpus → feature checklist) are being retried after a rate limit.
Once the checklist is in, each item becomes a task. Known-missing from the user's report:

- [ ] B1. 0.6x / 1x / 2x lens selection.
- [ ] B2. Pinch-to-zoom + manual zoom 0.6x..20x.
- [ ] B3. Instant shutter parity (ZSL correctness).
- [ ] B4. Slow motion.
- [ ] B5. Panorama.
- [ ] B6. Time lapse.
- [ ] B7. Portrait mode.
- [ ] B8. Night mode.
- [ ] B9. Gallery view / last-shot viewer.
- [ ] B10. Tap-to-focus + on-viewfinder brightness (AE) control.

## Status log

- 2026-10-16: plan created. A1–A5 next.