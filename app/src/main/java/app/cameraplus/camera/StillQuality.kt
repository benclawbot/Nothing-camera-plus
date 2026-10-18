package app.cameraplus.camera

/**
 * Still-capture processing policy (v1.3 image-quality pass).
 *
 * Camera2 lets a still request ask the HAL for the expensive, full-readout
 * versions of its processing stages — high-quality noise reduction, edge
 * enhancement, shading correction, colour-aberration correction, hot-pixel
 * correction and distortion correction. They are available as best-effort
 * Camera2 hints on a full still capture, unlike the pre-captured frames in a
 * zero-shutter-lag stream (which comes from the preview stream and uses its
 * real-time stages instead).
 *
 * So the choice is a real trade: [FAST] keeps the instant shutter and the
 * platform's own defaults, [BALANCED] asks for the zero-shutter-lag-tuned
 * noise-reduction and edge modes while keeping the instant shutter, and [HIGH]
 * asks for every high-quality stage on a full readout, which means the capture
 * mode has to leave zero-shutter-lag.
 *
 * Only stages the camera advertises are ever requested (see the session's
 * gating), and the HAL may still treat a request as a hint.
 */
enum class StillQuality(val label: String) {
    FAST("FAST"),
    BALANCED("BALANCED"),
    HIGH("HIGH"),
}

/** One Camera2 processing stage this policy can ask for. */
enum class StillFeature(val label: String) {
    NOISE_REDUCTION("noise reduction"),
    EDGE("edge enhancement"),
    SHADING("shading"),
    ABERRATION("aberration correction"),
    HOT_PIXEL("hot-pixel correction"),
    DISTORTION("distortion correction"),
}

/** What to ask a [StillFeature] for. */
enum class StillMode {
    /** Send nothing; the platform's own default stays in force. */
    PLATFORM_DEFAULT,

    /** The mode tuned for frames that came out of the preview stream. */
    ZERO_SHUTTER_LAG,

    /** The full-readout mode, which needs the standard capture path. */
    HIGH_QUALITY,
}

/** Per-stage requests for one [StillQuality], pure data so it is testable. */
data class StillQualityPlan(
    val noiseReduction: StillMode = StillMode.PLATFORM_DEFAULT,
    val edge: StillMode = StillMode.PLATFORM_DEFAULT,
    val shading: StillMode = StillMode.PLATFORM_DEFAULT,
    val aberration: StillMode = StillMode.PLATFORM_DEFAULT,
    val hotPixel: StillMode = StillMode.PLATFORM_DEFAULT,
    val distortion: StillMode = StillMode.PLATFORM_DEFAULT,
) {

    /** The stages to request, in a stable order, with platform defaults left out. */
    val requested: List<Pair<StillFeature, StillMode>>
        get() = listOf(
            StillFeature.NOISE_REDUCTION to noiseReduction,
            StillFeature.EDGE to edge,
            StillFeature.SHADING to shading,
            StillFeature.ABERRATION to aberration,
            StillFeature.HOT_PIXEL to hotPixel,
            StillFeature.DISTORTION to distortion,
        ).filter { (_, mode) -> mode != StillMode.PLATFORM_DEFAULT }

    /**
     * True when this plan asks for processing that cannot come from the
     * pre-captured preview stream, so the session must leave zero-shutter-lag.
     */
    val blocksZeroShutterLag: Boolean
        get() = requested.any { (_, mode) -> mode == StillMode.HIGH_QUALITY }

    companion object {
        /** Nothing requested: the platform's own defaults, instant shutter. */
        val NONE = StillQualityPlan()

        fun forQuality(quality: StillQuality): StillQualityPlan = when (quality) {
            StillQuality.FAST -> NONE
            StillQuality.BALANCED -> StillQualityPlan(
                noiseReduction = StillMode.ZERO_SHUTTER_LAG,
                edge = StillMode.ZERO_SHUTTER_LAG,
            )
            StillQuality.HIGH -> StillQualityPlan(
                noiseReduction = StillMode.HIGH_QUALITY,
                edge = StillMode.HIGH_QUALITY,
                shading = StillMode.HIGH_QUALITY,
                aberration = StillMode.HIGH_QUALITY,
                hotPixel = StillMode.HIGH_QUALITY,
                distortion = StillMode.HIGH_QUALITY,
            )
        }
    }
}

/**
 * Still-quality choices worth offering: BALANCED/HIGH only mean something
 * when the camera advertises their respective modes, so they are hidden
 * rather than shown as no-ops.
 */
object StillQualitySupport {
    fun available(
        reportsZeroShutterLagModes: Boolean,
        reportsHighQualityModes: Boolean,
    ): List<StillQuality> = buildList {
        add(StillQuality.FAST)
        if (reportsZeroShutterLagModes) add(StillQuality.BALANCED)
        if (reportsHighQualityModes) add(StillQuality.HIGH)
    }
}
