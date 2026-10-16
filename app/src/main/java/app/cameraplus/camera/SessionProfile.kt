package app.cameraplus.camera

/** What the session drives: stills, video, or stills with manual controls. */
enum class SessionMode { PHOTO, VIDEO, EXPERT }

/**
 * What the bound camera can do, published after every bind so the UI can
 * gate zoom stops, RAW, stabilization and the manual control ranges instead
 * of guessing.
 */
data class SessionCapabilities(
    val zoomStops: List<Float> = listOf(1f),
    val rawSupported: Boolean = false,
    val stabilization: StabilizationLevel = StabilizationLevel.NONE,
    val isoRange: IntRange? = null,
    val exposureRange: LongRange? = null,
    val minFocusDistance: Float? = null,
)
