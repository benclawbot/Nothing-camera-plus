package app.cameraplus.camera

import kotlin.math.ceil

/**
 * Which way the user sweeps, and how that maps onto the stitched canvas.
 *
 * The stock panorama has a direction flip button because a sweep to the left
 * and a sweep to the right produce different canvases (and the stock app keeps
 * the panorama in the orientation the user started it, which is what this
 * mirror sign encodes here).
 *
 * Sign convention used by [PanoramaStitcher]: a frame's **canvas offset** is
 * where the frame lands relative to the first frame. Turning the camera to the
 * right moves scene content to the left inside the frame, so the new content
 * belongs to the *right* of the canvas and the offset is positive. Turning left
 * grows the canvas to the left, i.e. a negative offset. That is why the two
 * directions carry mirrored signs.
 */
enum class Direction(val canvasShiftSign: Int) {
    /** Camera turns left; each new frame sits further left on the canvas. */
    LEFT(-1),

    /** Camera turns right; each new frame sits further right on the canvas. */
    RIGHT(1);

    /** The other way round, for a direction toggle. */
    val opposite: Direction
        get() = if (this == LEFT) RIGHT else LEFT

    companion object {
        /**
         * The direction a measured canvas offset implies, or null for a zero
         * offset (no measurable movement: the user has not panned yet).
         */
        fun fromShift(canvasShift: Int): Direction? = when {
            canvasShift > 0 -> RIGHT
            canvasShift < 0 -> LEFT
            else -> null
        }
    }
}

/**
 * Sweep guidance: how many frames a panorama needs, how far the camera has to
 * turn between them, and how much horizontal movement that translates into.
 *
 * The maths is the textbook one. Consecutive frames must share an overlap, so
 * with a horizontal field of view `fov` and a fractional overlap `o` each step
 * advances the camera by
 *
 * ```
 * cadence = fov * (1 - o)
 * ```
 *
 * degrees, and `n` frames cover `fov + (n - 1) * cadence`. Solving that for a
 * target sweep gives [recommendedFrames]; [maxSweepDegrees] is the same
 * formula read the other way.
 *
 * Defaults are the compromise a handheld sweep wants: a 75 degree sweep is
 * what users mean by "panorama" (wide enough to be worth it, narrow enough to
 * keep the parallax low), and 30% overlap is comfortably more than the ~15%
 * the matcher in [PanoramaStitcher] can still align without help.
 *
 * Pure math, no Android types, so it stays JVM-testable.
 */
object PanoramaPlan {

    /** Degrees of sweep a plain panorama aims for. */
    const val DEFAULT_TARGET_SWEEP_DEGREES = 75f

    /** Fractional overlap between consecutive frames. */
    const val DEFAULT_OVERLAP = 0.3f

    /**
     * Hard cap on frames in one sweep. Twelve frames is what the stitcher is
     * budgeted for: the canvas is roughly `frames * cadence` wide, so every
     * extra frame costs canvas memory linearly.
     */
    const val MAX_FRAMES = 12

    /** Abs slack for `ceil`, so 3.0000001 does not become a fourth frame. */
    private const val EPSILON = 1e-4f

    /**
     * How many frames cover [targetSweepDegrees] at this field of view and
     * overlap.
     *
     * At least 2 (a single frame is a photo, not a sweep) and at most
     * [MAX_FRAMES].
     *
     * @throws IllegalArgumentException if the field of view is not positive,
     *   the overlap is outside `0 < o < 1`, or the target sweep is not
     *   positive. Those are caller mistakes — a panorama with a zero-degree
     *   field of view would divide by zero.
     */
    fun recommendedFrames(
        horizontalFovDegrees: Float,
        overlap: Float = DEFAULT_OVERLAP,
        targetSweepDegrees: Float = DEFAULT_TARGET_SWEEP_DEGREES,
    ): Int {
        require(horizontalFovDegrees > 0f) { "horizontal fov must be positive, was $horizontalFovDegrees" }
        require(overlap > 0f && overlap < 1f) { "overlap must be within (0, 1), was $overlap" }
        require(targetSweepDegrees > 0f) { "target sweep must be positive, was $targetSweepDegrees" }

        val cadence = captureCadenceDegrees(horizontalFovDegrees, overlap)
        // coverage(n) = fov + (n - 1) * cadence >= target
        val extraFrames = (targetSweepDegrees - horizontalFovDegrees) / cadence
        val frames = if (extraFrames <= 0f) 1 else ceil((extraFrames - EPSILON).toDouble()).toInt() + 1
        return frames.coerceIn(2, MAX_FRAMES)
    }

    /**
     * Degrees the camera must turn between two consecutive frames.
     *
     * Also the fraction of the frame width the scene is allowed to move
     * ([maxShiftFraction]); a slower cadence means more overlap, more CPU in
     * the matcher and a shorter panorama per frame.
     */
    fun captureCadenceDegrees(horizontalFovDegrees: Float, overlap: Float = DEFAULT_OVERLAP): Float {
        require(horizontalFovDegrees > 0f) { "horizontal fov must be positive, was $horizontalFovDegrees" }
        require(overlap > 0f && overlap < 1f) { "overlap must be within (0, 1), was $overlap" }
        return horizontalFovDegrees * (1f - overlap)
    }

    /**
     * Degrees of scene covered by [frames] frames: the first frame's full
     * field of view plus one cadence for every frame after it.
     *
     * One frame therefore covers exactly the field of view, and the count and
     * the coverage are inverse functions of each other for any `fov`/`overlap`
     * that [recommendedFrames] accepts.
     */
    fun maxSweepDegrees(
        frames: Int,
        horizontalFovDegrees: Float,
        overlap: Float = DEFAULT_OVERLAP,
    ): Float {
        require(frames >= 1) { "a sweep needs at least one frame, got $frames" }
        return horizontalFovDegrees + (frames - 1) * captureCadenceDegrees(horizontalFovDegrees, overlap)
    }

    /**
     * How much of the frame width the scene may move between two frames while
     * the overlap is still intact: `1 - overlap`.
     */
    fun maxShiftFraction(overlap: Float = DEFAULT_OVERLAP): Float {
        require(overlap > 0f && overlap < 1f) { "overlap must be within (0, 1), was $overlap" }
        return 1f - overlap
    }

    /**
     * The smallest believable movement between two frames, as a fraction of
     * the frame width: the maximum shift minus [tolerance] of slack.
     *
     * Below this the two frames are near-duplicates, which in a sweep means the
     * user stopped moving — a frame worth rejecting rather than stacking on
     * top of the previous one.
     */
    fun minShiftFraction(overlap: Float = DEFAULT_OVERLAP, tolerance: Float = 0.15f): Float {
        require(overlap > 0f && overlap < 1f) { "overlap must be within (0, 1), was $overlap" }
        require(tolerance >= 0f) { "tolerance must not be negative, was $tolerance" }
        return (1f - overlap - tolerance).coerceAtLeast(0f)
    }
}