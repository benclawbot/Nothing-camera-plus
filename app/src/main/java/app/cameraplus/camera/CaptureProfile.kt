package app.cameraplus.camera

/**
 * Capture-mode policy for the instant-shutter work (M2).
 *
 * Zero-shutter-lag captures come from the stream right before the tap, which
 * is what makes the shutter feel instant; flash and pre-captured frames don't
 * combine, so enabling flash takes the standard path.
 */
object CaptureProfile {
    fun usesZeroShutterLag(zslSupported: Boolean, flashEnabled: Boolean): Boolean =
        zslSupported && !flashEnabled
}
