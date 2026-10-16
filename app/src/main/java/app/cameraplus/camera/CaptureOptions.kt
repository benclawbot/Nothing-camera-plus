package app.cameraplus.camera

import java.util.Locale

/**
 * Self-timer policy (v1.1): the stock app offers off / 3s / 10s and shows the
 * countdown on the viewfinder while it runs.
 */
enum class SelfTimer(val label: String, val seconds: Int) {
    OFF("OFF", 0),
    THREE_SECONDS("3s", 3),
    TEN_SECONDS("10s", 10);

    fun next(): SelfTimer = entries[(ordinal + 1) % entries.size]

    val enabled: Boolean get() = seconds > 0
}

/** CameraX's still-capture flash policies exposed by the stock camera. */
enum class FlashChoice(val label: String) {
    OFF("OFF"),
    AUTO("AUTO"),
    ON("ON");

    fun next(): FlashChoice = entries[(ordinal + 1) % entries.size]
}

/** Crop ratios offered by the original camera, including tall-screen crops. */
enum class PhotoAspectRatio(
    val label: String,
    val numerator: Int,
    val denominator: Int,
) {
    SQUARE("1:1", 1, 1),
    FOUR_THREE("4:3", 4, 3),
    SIXTEEN_NINE("16:9", 16, 9),
    TWENTY_POINT_FIVE_NINE("20.5:9", 41, 18),
    TWENTY_NINE("20:9", 20, 9),
    NINETEEN_POINT_FIVE_NINE("19.5:9", 39, 18),
    NINETEEN_NINE("19:9", 19, 9),
    EIGHTEEN_NINE("18:9", 18, 9),
}

/** Standard CameraX selection vs the largest JPEG size the camera advertises. */
enum class PhotoResolution(val label: String) {
    STANDARD("12MP"),
    MAXIMUM("MAX");
}

/** User-facing video frame-rate requests; actual support comes from CameraInfo. */
enum class VideoFrameRate(val fps: Int, val label: String) {
    FPS_30(30, "30FPS"),
    FPS_60(60, "60FPS");

    fun next(supported: List<VideoFrameRate>): VideoFrameRate {
        val available = supported.ifEmpty { listOf(FPS_30) }
        val currentIndex = available.indexOf(this)
        return available[(currentIndex + 1).mod(available.size)]
    }
}

/**
 * Video quality policy (v1.1).
 *
 * The original offers 1080p30, 1080p60, 4K30 and 4K60. Which of those a
 * device can actually do is not knowable up front — it depends on what the
 * encoder and the camera accept — so the plan is built from the quality
 * levels CameraX reports as supported and the preference is clamped to the
 * nearest thing the hardware offers rather than failing.
 */
enum class VideoQuality(val label: String, val rank: Int) {
    HD("720P", 1),
    FHD("1080P", 2),
    UHD("4K", 3);

    fun next(): VideoQuality = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Nearest entry for a requested label, e.g. "4K" -> UHD. */
        fun fromLabel(label: String): VideoQuality? =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) }

        /**
         * Clamps [requested] to what [supported] allows, never going below HD.
         * Empty support falls back to FHD, matching the previous default.
         */
        fun resolve(requested: VideoQuality, supported: Set<VideoQuality>): VideoQuality {
            val usable = supported.ifEmpty { setOf(FHD) }
            val atOrBelow = entries.filter { it.rank <= requested.rank && it in usable }
            return atOrBelow.maxByOrNull { it.rank }
                ?: usable.minByOrNull { it.rank }
                ?: FHD
        }
    }
}

/** Duration formatting shared by the record timer and the self-timer. */
object CaptureTimer {
    fun format(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    /**
     * Seconds left for a self-timer started at [startedAtMillis]. Rounds *up*
     * so a timer started at t=0 reads 3, 2, 1 and reaches 0 only once the
     * full delay has genuinely elapsed.
     */
    fun remaining(seconds: Int, startedAtMillis: Long, nowMillis: Long): Int {
        if (seconds <= 0) return 0
        val elapsed = nowMillis - startedAtMillis
        val left = (seconds * 1000L - elapsed + 999L) / 1000L
        return left.coerceIn(0L, seconds.toLong()).toInt()
    }
}
