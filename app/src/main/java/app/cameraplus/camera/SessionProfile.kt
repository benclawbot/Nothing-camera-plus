package app.cameraplus.camera

/** What the session drives: stills, video, manual stills, QR analysis, or a panorama YUV stream. */
enum class SessionMode { PHOTO, VIDEO, EXPERT, QR, PANORAMA }

/**
 * What the bound camera can do, published after every bind so the UI can
 * gate zoom stops, RAW, stabilization and the manual control ranges instead
 * of guessing.
 */
data class SessionCapabilities(
    val zoomStops: List<Float> = listOf(1f),
    /** Physical focal-length stops exposed by the public camera API. */
    val opticalZoomStops: List<Float> = listOf(1f),
    /**
     * 35 mm-equivalent focal at each zoom stop, aligned with [zoomStops]
     * (0 = unknown). Zoom chip labels show these instead of bare ratios.
     */
    val zoomStopFocalMm: List<Float> = emptyList(),
    /** 35 mm-equivalent focal of the 1x lens, for the continuous zoom readout. */
    val referenceEqFocalMm: Float = 0f,
    val minDisplayedZoom: Float = 1f,
    val maxDisplayedZoom: Float = 1f,
    val rawSupported: Boolean = false,
    /** Anti-banding modes the camera advertises (always at least AUTO). */
    val availableAntiBanding: List<AntiBanding> = listOf(AntiBanding.AUTO),
    /** Still-processing quality levels this camera can be asked for. */
    val availableStillQuality: List<StillQuality> = listOf(StillQuality.FAST),
    val stabilization: StabilizationLevel = StabilizationLevel.NONE,
    val isoRange: IntRange? = null,
    val exposureRange: LongRange? = null,
    val minFocusDistance: Float? = null,
    val evRange: IntRange? = null,
    val evStep: Float = 1f / 3f,
    val supportedVideoFrameRates: List<VideoFrameRate> = listOf(VideoFrameRate.FPS_30),
    val maximumPhotoResolutionSupported: Boolean = false,
    /** CameraX reports JPEG_R / Ultra HDR as an ImageCapture output format. */
    val ultraHdrPhotoSupported: Boolean = false,
    val portraitExtensionAvailable: Boolean = false,
    val nightExtensionAvailable: Boolean = false,
    val hdrExtensionAvailable: Boolean = false,
    val faceRetouchExtensionAvailable: Boolean = false,
    val extensionActive: Boolean = false,
    val macroFocusSupported: Boolean = false,
    val opticalStabilizationSupported: Boolean = false,
    val videoSnapshotSupported: Boolean = false,
    val videoNoiseReductionSupported: Boolean = false,
)

/** On-screen subject-tracking reticle size; also the AF/AE metering area. */
const val SUBJECT_TRACKING_BOX_DP = 76f
