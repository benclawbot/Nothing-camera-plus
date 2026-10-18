package app.cameraplus.settings

/** User-selected clean-room watermark fields for saved still photos. */
data class WatermarkOptions(
    val enabled: Boolean = false,
    val includeDate: Boolean = true,
    val includeTime: Boolean = true,
    val includeLocation: Boolean = false,
    /** The original's "Camera parameters" line: "24MM F/1.9 1/50S ISO 123". */
    val includeCameraParameters: Boolean = true,
    val authorName: String = "",
)
