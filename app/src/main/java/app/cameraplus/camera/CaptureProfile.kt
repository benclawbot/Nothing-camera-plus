package app.cameraplus.camera

/**
 * Capture-mode policy for the instant-shutter work (M2).
 *
 * Zero-shutter-lag captures come from the stream right before the tap, which
 * is what makes the shutter feel instant; flash, RAW output and manual
 * capture-request controls don't combine with pre-captured frames, so each of
 * them takes the standard path.
 */
object CaptureProfile {
    fun usesZeroShutterLag(
        zslSupported: Boolean,
        flashEnabled: Boolean,
        rawEnabled: Boolean = false,
        manualControlsActive: Boolean = false,
    ): Boolean = zslSupported && !flashEnabled && !rawEnabled && !manualControlsActive
}
