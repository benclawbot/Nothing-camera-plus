package app.cameraplus.camera

/**
 * Capture-mode policy for the instant-shutter work (M2).
 *
 * Zero-shutter-lag captures come from the stream right before the tap, which
 * is what makes the shutter feel instant; flash, RAW output, manual
 * capture-request controls and the high-quality still-processing modes don't
 * combine with pre-captured frames, so each of them takes the standard path.
 */
object CaptureProfile {
    fun usesZeroShutterLag(
        zslSupported: Boolean,
        flashEnabled: Boolean,
        rawEnabled: Boolean = false,
        manualControlsActive: Boolean = false,
        highResolutionActive: Boolean = false,
        highQualityProcessingActive: Boolean = false,
    ): Boolean = zslSupported && !flashEnabled && !rawEnabled &&
        !manualControlsActive && !highResolutionActive && !highQualityProcessingActive
}
