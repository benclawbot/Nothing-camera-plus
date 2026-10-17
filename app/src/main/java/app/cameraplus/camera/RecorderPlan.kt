package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a slow-motion recording captures at, what the file is written at, and
 * how much longer that makes the clip.
 *
 * A phone that shoots at 240 fps but encodes 30 fps per second of output plays
 * the result back eight times slower than it happened. [slowdownFactor] is
 * exactly that ratio, which is also the number the UI shows as "0.3x" style
 * speed-up claims are derived from.
 */
data class SlowMotionPlan(
    val captureFps: Int,
    val playbackFps: Int,
    val slowdownFactor: Float,
) {
    /**
     * Playback duration of a clip that took [captureSeconds] of real time to
     * record — the number a caller shows while recording ("0:12 of 1:36").
     */
    fun playbackSeconds(captureSeconds: Float): Float {
        require(captureSeconds >= 0f && captureSeconds.isFinite()) {
            "captureSeconds must be finite and >= 0 (was $captureSeconds)"
        }
        return captureSeconds * slowdownFactor
    }
}

/**
 * What a time-lapse recording captures, and how fast it plays back.
 *
 * One frame is grabbed every [intervalSeconds] of real time and written as a
 * [playbackFps] fps video, so [expectedFrames] frames of output cover
 * `expectedFrames * intervalSeconds` seconds of reality and run
 * [speedUpFactor] times faster than that. For a 2 s interval at 30 fps
 * playback that is 60x — the same number Camera+ shows as its X15…X960 ladder.
 */
data class TimeLapsePlan(
    val intervalSeconds: Float,
    val captureFps: Float,
    val playbackFps: Int,
    val expectedFrames: Int,
    val speedUpFactor: Float,
) {
    /** Length of the file this will produce, in seconds. */
    val playbackSeconds: Float get() = expectedFrames / playbackFps.toFloat()

    /** Real time the file will cover, in seconds (rounded frames times interval). */
    val coveredRealSeconds: Float get() = expectedFrames * intervalSeconds
}

/**
 * One high-speed output size of a single camera, with the frame-rate brackets
 * the HAL offers for it — the raw material both slow motion and the interval
 * picker plan from.
 *
 * Brackets are the `Range<Integer>` values of
 * `StreamConfigurationMap.getHighSpeedVideoFpsRangesFor()`. A fixed-rate entry
 * comes back as `240..240`; some devices expose variable entries like
 * `120..240` instead, and those can serve any fps inside them.
 */
data class HighSpeedOption(
    val width: Int,
    val height: Int,
    val fpsRanges: List<IntRange>,
) {
    val area: Int get() = width * height

    /** True when some bracket covers [fps] (fixed *and* variable entries). */
    fun supports(fps: Int): Boolean = fpsRanges.any { it.last >= 1 && fps in it }

    /**
     * Bracket to request for [fps]: the one whose top end is closest to it (a
     * fixed `240..240` beats a wide `120..240` that merely contains it), and
     * the tightest of those. `null` when nothing covers [fps].
     */
    fun bestRange(fps: Int): IntRange? =
        fpsRanges.filter { it.last >= 1 && fps in it }
            .minWithOrNull(compareBy({ abs(it.last - fps) }, { it.last - it.first }))

    /** Fixed rates or range endpoints this size advertises, ascending and unique. */
    fun nominalRates(): List<Int> = fpsRanges
        .filter { it.first >= 1 && it.last >= it.first }
        .flatMap { listOf(it.first, it.last) }
        .distinct()
        .sorted()
}

/**
 * Android-free planning for the two modes CameraX cannot record: slow motion
 * (needs a constrained high-speed capture session) and time lapse (needs a
 * capture rate far below the video frame rate).
 *
 * Everything here is pure arithmetic over values the camera2 configuration map
 * reports, so it is JVM-testable and the device-facing class
 * ([Camera2Recorder]) is left with nothing but wiring.
 */
object RecorderPlan {

    /** Playback frame rate every plan assumes unless the caller says otherwise. */
    const val DEFAULT_PLAYBACK_FPS = 30

    /**
     * CameraX/Camera2 quality defaults expressed against this baseline, so a
     * high-speed bitrate can be derived from the one real-time recording
     * number rather than a made-up constant.
     */
    const val FHD_BASELINE_BITS_PER_SECOND = 17_000_000
    const val FHD_BASELINE_FPS = 30
    const val FHD_BASELINE_WIDTH = 1920
    const val FHD_BASELINE_HEIGHT = 1080

    /** Stock mode exposes 4K120 on some products; use it only when HAL lists it. */
    const val MAX_SLOW_MOTION_WIDTH = 3840
    const val MAX_SLOW_MOTION_HEIGHT = 2160

    /**
     * Ceiling on the predicted frame count. A 0.5 s interval over a day would
     * otherwise overflow the arithmetic that produced it; the real clip is
     * bounded by how long anyone holds the shutter down anyway.
     */
    const val MAX_EXPECTED_FRAMES = 1_000_000

    /**
     * Slow motion plan for capturing at [captureFps] and encoding at
     * [playbackFps].
     *
     * @throws IllegalArgumentException if either rate is below 1, or the
     *   resulting slowdown is not positive — a plan that would produce a file
     *   of zero or negative length is a programming error, not a runtime
     *   condition, so it throws rather than clamping silently.
     */
    fun slowMotionPlan(captureFps: Int, playbackFps: Int = DEFAULT_PLAYBACK_FPS): SlowMotionPlan {
        require(captureFps >= 1) { "captureFps must be >= 1 (was $captureFps)" }
        require(playbackFps >= 1) { "playbackFps must be >= 1 (was $playbackFps)" }
        val slowdown = captureFps.toFloat() / playbackFps.toFloat()
        require(slowdown > 0f && slowdown.isFinite()) {
            "slowdown must be positive and finite (was $slowdown for $captureFps/$playbackFps)"
        }
        return SlowMotionPlan(
            captureFps = captureFps,
            playbackFps = playbackFps,
            slowdownFactor = slowdown,
        )
    }

    /**
     * Time-lapse plan: one frame every [intervalSeconds] of real time, written
     * at [playbackFps], for a clip that is supposed to cover [realSeconds].
     *
     * [TimeLapsePlan.expectedFrames] is rounded to a whole frame (the encoder
     * cannot write 33.3 of them) and clamped: at least one frame whenever any
     * real time was asked for, and never more than [MAX_EXPECTED_FRAMES].
     *
     * @throws IllegalArgumentException on a zero, negative or non-finite
     *   interval, a playback rate below 1, or negative real time — a zero
     *   interval would divide by zero when the capture rate is derived.
     */
    fun timeLapsePlan(
        intervalSeconds: Float,
        playbackFps: Int = DEFAULT_PLAYBACK_FPS,
        realSeconds: Float,
    ): TimeLapsePlan {
        require(intervalSeconds.isFinite() && intervalSeconds > 0f) {
            "intervalSeconds must be finite and > 0 (was $intervalSeconds)"
        }
        require(playbackFps >= 1) { "playbackFps must be >= 1 (was $playbackFps)" }
        require(realSeconds.isFinite() && realSeconds >= 0f) {
            "realSeconds must be finite and >= 0 (was $realSeconds)"
        }
        val captureFps = 1f / intervalSeconds
        val rawFrames = realSeconds / intervalSeconds
        val rounded = rawFrames.roundToInt().coerceIn(0, MAX_EXPECTED_FRAMES)
        // A clip shorter than one interval still yields one frame; the
        // encoder has nothing else to write.
        val frames = if (realSeconds > 0f) rounded.coerceAtLeast(1) else 0
        return TimeLapsePlan(
            intervalSeconds = intervalSeconds,
            captureFps = captureFps,
            playbackFps = playbackFps,
            expectedFrames = frames,
            speedUpFactor = intervalSeconds * playbackFps,
        )
    }

    /**
     * Capture rates offered by the high-speed ranges of **one** resolution,
     * ascending and without repeats.
     *
     * Fixed brackets contribute their rate; variable brackets contribute
     * both endpoints (`120..240` makes 120 and 240 menu entries). Other rates
     * inside a range are available to plans but are not shown by this picker.
     * Rates at or below normal 30 fps playback are omitted: they are not slow
     * motion and would produce a normal-speed clip in this mode.
     * Degenerate/empty brackets (possible from a
     * misbehaving HAL, `1..0`) are dropped rather than reported as 0 fps.
     */
    fun supportedSlowMotionRates(ranges: List<IntRange>): List<Int> =
        ranges.filter { it.first >= 1 && it.last >= it.first }
            .flatMap { listOf(it.first, it.last) }
            .filter { it > DEFAULT_PLAYBACK_FPS }
            .distinct()
            .sorted()

    /**
     * Interval (seconds between frames) whose nearest supported fps bracket
     * contains [targetFps] — i.e. the interval closest to what the caller
     * asked for that the camera can actually deliver.
     *
     * Ties go to the faster bracket: when two brackets are equally close, the
     * one that yields *more* frames is the closer match to a time-lapse intent.
     * Returns `null` when [fpsRanges] is empty, when [targetFps] is below 1
     * (which would mean an interval of 60 s or longer), or when no bracket
     * brackets the target.
     */
    fun nextTimeLapseInterval(fpsRanges: List<IntRange>, targetFps: Int): Float? {
        if (targetFps < 1) return null
        val usable = fpsRanges.filter { it.first >= 1 && it.last >= it.first }
        if (usable.isEmpty()) return null
        val matching = usable.filter { targetFps in it }
        val best = if (matching.isNotEmpty()) {
            // A variable bracket that contains the requested capture rate is
            // preferable to choosing a nearby fixed-rate mode.
            matching.minWithOrNull(
                compareBy({ it.last - it.first }, { abs(midpoint(it) - targetFps) }, { -it.last }),
            )
        } else {
            // When the target sits between two discrete brackets, choose the
            // nearest endpoint; a tie goes to the faster bracket. Values
            // outside the camera's entire reported range remain unsupported.
            val low = usable.minOf { it.first }
            val high = usable.maxOf { it.last }
            if (targetFps !in low..high) return null
            usable.minWithOrNull(
                compareBy<IntRange>(
                    { minOf(abs(it.first - targetFps), abs(it.last - targetFps)) },
                    { -it.last },
                ),
            )
        } ?: return null
        return 1f / best.last
    }

    /**
     * Bracket to put in `CONTROL_AE_TARGET_FPS_RANGE` for a capture rate of
     * [targetFps] — the normal (non high-speed) path, used by time lapse.
     *
     * Unlike [nextTimeLapseInterval] this always answers when there is any
     * bracket at all: a time-lapse rate is normally far below every range the
     * HAL reports (1 frame per 4 s is 0.25 fps, ranges bottom out around 7),
     * and the camera is driven at its slowest bracket in that case. So the
     * nearest bracket wins, measured by midpoint.
     */
    fun nearestFpsRange(fpsRanges: List<IntRange>, targetFps: Float): IntRange? {
        val usable = fpsRanges.filter { it.first >= 1 && it.last >= it.first }
        if (usable.isEmpty()) return null
        return usable.minWithOrNull(
            compareBy({ abs(midpoint(it) - targetFps) }, { it.first }),
        )
    }

    /**
     * High-speed size to record [captureFps] at: the largest one that fits
     * inside [maxWidth] x [maxHeight] *and* advertises a bracket covering the
     * rate. Ties on area go to the size whose bracket sits closest to the
     * request. `null` when the device offers no high-speed mode for that rate
     * at that size — the caller has nothing to record and should say so.
     */
    fun selectSlowMotionSize(
        options: List<HighSpeedOption>,
        captureFps: Int,
        maxWidth: Int = MAX_SLOW_MOTION_WIDTH,
        maxHeight: Int = MAX_SLOW_MOTION_HEIGHT,
    ): HighSpeedOption? {
        require(captureFps >= 1) { "captureFps must be >= 1 (was $captureFps)" }
        return options
            .filter { it.width in 1..maxWidth && it.height in 1..maxHeight && it.supports(captureFps) }
            .maxWithOrNull(
                compareBy<HighSpeedOption>({ it.area })
                    .thenBy({ -abs((it.bestRange(captureFps)?.last ?: captureFps) - captureFps) })
                    .thenByDescending { it.width }
                    .thenByDescending { it.height },
            )
    }

    /**
     * Encoding bitrate for [width] x [height] at [fps], scaled off the real-time
     * 1080p30 baseline (about 17 Mbit/s) by pixel count and frame rate.
     *
     * High-speed streams need it: 1080p240 lands around 136 Mbit/s, roughly
     * eight times the baseline, because the encoder is fed eight times as many
     * frames in the same amount of wall-clock playback.
     *
     * @throws IllegalArgumentException if any dimension or rate is below 1.
     */
    fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        require(width >= 1 && height >= 1) { "width/height must be >= 1 (were ${width}x$height)" }
        require(fps >= 1) { "fps must be >= 1 (was $fps)" }
        val baselinePixels = FHD_BASELINE_WIDTH.toFloat() * FHD_BASELINE_HEIGHT.toFloat()
        val scale = (width.toFloat() * height.toFloat()) / baselinePixels * (fps / FHD_BASELINE_FPS.toFloat())
        return (FHD_BASELINE_BITS_PER_SECOND * scale).roundToInt().coerceAtLeast(100_000)
    }

    /** Mid fps of a bracket; the stand-in "nominal rate" of a variable range. */
    private fun midpoint(range: IntRange): Float = (range.first + range.last) / 2f
}
