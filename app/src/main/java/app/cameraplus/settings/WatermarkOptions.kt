package app.cameraplus.settings

/** User-selected clean-room watermark fields for saved still photos. */
data class WatermarkOptions(
    val enabled: Boolean = false,
    val includeDate: Boolean = true,
    val includeTime: Boolean = true,
    val includeLocation: Boolean = false,
    val authorName: String = "",
)
