package app.cameraplus.camera

/** What the session drives: stills, video, or stills with manual controls. */
enum class SessionMode { PHOTO, VIDEO, EXPERT, QR }

/**
 * What the bound camera can do, published after every bind so the UI can
 * gate zoom stops, RAW, stabilization and the manual control ranges instead
 * of guessing.
 */
data class SessionCapabilities(
    val zoomStops: List<Float> = listOf(1f),
    val minDisplayedZoom: Float = 1f,
    val maxDisplayedZoom: Float = 1f,
    val rawSupported: Boolean = false,
    val stabilization: StabilizationLevel = StabilizationLevel.NONE,
    val isoRange: IntRange? = null,
    val exposureRange: LongRange? = null,
    val minFocusDistance: Float? = null,
    val evRange: IntRange? = null,
    val evStep: Float = 1f / 3f,
    val supportedVideoFrameRates: List<VideoFrameRate> = listOf(VideoFrameRate.FPS_30),
    val maximumPhotoResolutionSupported: Boolean = false,
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
