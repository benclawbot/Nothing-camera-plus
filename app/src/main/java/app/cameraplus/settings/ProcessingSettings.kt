package app.cameraplus.settings

/**
 * User-visible processing intent for a photo.
 *
 * Design goal (M3): no forced over-processing. [NATURAL] is a neutral intent
 * that leaves the pipeline as close to the sensor output as practical, and
 * each step can be dialed independently when the user wants more.
 *
 * Values are fractions in `[0, 1]`; `0` disables the step. This type is pure
 * data so it can be unit-tested on the JVM; the camera pipeline maps each
 * field onto available hardware or software processing when the feature
 * lands.
 */
data class ProcessingSettings(
    val sharpening: Float = 0f,
    val noiseReduction: Float = 0f,
    val hdr: Float = 0f,
    val skinSmoothing: Float = 0f,
    val filter: PhotoFilter = PhotoFilter.NATURAL,
) {
    init {
        require(sharpening in 0f..1f) { "sharpening must be between 0 and 1" }
        require(noiseReduction in 0f..1f) { "noiseReduction must be between 0 and 1" }
        require(hdr in 0f..1f) { "hdr must be between 0 and 1" }
        require(skinSmoothing in 0f..1f) { "skinSmoothing must be between 0 and 1" }
    }

    companion object {
        /** Neutral intent: don't add processing on top of what the device does. */
        val NATURAL = ProcessingSettings()
    }
}

/** Clean-room still filters; these do not include or imitate vendor LUT assets. */
enum class PhotoFilter(val label: String) {
    NATURAL("NATURAL"),
    MONOCHROME("B&W"),
    WARM("WARM"),
    COOL("COOL"),
    VIVID("VIVID"),
    VINTAGE("VINTAGE"),
}
