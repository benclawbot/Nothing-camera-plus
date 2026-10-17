package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Motion guidance for a live panorama sweep, fed by the gyroscope and the
 * accelerometer.
 *
 * The stock panorama drives its guide from the same two signals (see the
 * decompiled `GyroscopeRoundDetector` for the round-completion half of it);
 * this is the clean-room equivalent for a bounded sweep:
 *
 *  - **Which way to turn and how far you have turned.** The instantaneous
 *    rotation rate is projected onto the world "up" axis estimated from
 *    gravity, so the same math works whether the phone is held portrait or
 *    landscape. Integrating that gives the swept angle, which the UI can show
 *    as progress and use to auto-finish.
 *  - **How fast.** A smoothed absolute rate classifies the pan as stopped,
 *    slow, in the sweet spot, or too fast for the matcher to keep up with.
 *  - **How level.** The gravity direction is latched when the sweep starts,
 *    and any drift of the phone away from that pose (tilting up/down or
 *    rolling) is reported in degrees, so the UI can say "hold level" before
 *    the horizon bends.
 *
 * The class has no Android dependencies — sensors are pushed in as plain
 * numbers with their event timestamps, so the whole state machine runs in
 * plain JVM tests. Sign conventions: [yawDegrees] is the integral of
 * `dot(gyroscope, up)` in degrees, so (per the right-hand rule) turning the
 * camera *right* (clockwise seen from above) makes it *negative*. The caller
 * knows which sign advances its chosen sweep direction and passes it as
 * [forwardSign]; [forwardDegrees] is then non-negative for motion in that
 * direction.
 */
class PanoramaGuide(
    /**
     * Sign of the world-yaw rate that moves the sweep forward: `-1` when the
     * chosen direction is a clockwise turn (to the right), `+1` for
     * counter-clockwise (to the left).
     */
    private val forwardSign: Float,
    /** Angle, in degrees, that [GuideState.sweepProgress] calls complete. */
    private val targetSweepDegrees: Float = DEFAULT_TARGET_SWEEP_DEGREES,
) {

    init {
        require(forwardSign == 1f || forwardSign == -1f) { "forwardSign must be -1 or 1, was $forwardSign" }
        require(targetSweepDegrees > 0f) { "targetSweepDegrees must be positive, was $targetSweepDegrees" }
    }

    /** What the UI shows while the sweep runs. All angles in degrees. */
    data class GuideState(
        /** Signed swept angle since the sweep started; negative = turning right. */
        val yawDegrees: Float,
        /** Swept angle along the chosen direction, never negative. */
        val forwardDegrees: Float,
        /** [forwardDegrees] against the target, `0f..1f`. */
        val sweepProgress: Float,
        /** Smoothed absolute turn rate. */
        val speedDegreesPerSecond: Float,
        /** Deviation from the pose latched at the start, in degrees. */
        val tiltDegrees: Float,
        /** True while the deviation is inside [LEVEL_TOLERANCE_DEGREES]. */
        val level: Boolean,
        /** True once the phone has been near-still for [STILL_SECONDS]. */
        val still: Boolean,
        /** The one instruction worth showing right now. */
        val hint: Hint,
    )

    /** The single most useful thing to tell the user. */
    enum class Hint {
        /** Not enough motion data yet. */
        READY,

        /** Turning in the chosen direction at a workable speed. */
        PAN,

        /** Turning, but too fast for the matcher — the user should slow down. */
        TOO_FAST,

        /** Turning the wrong way for the chosen direction. */
        WRONG_WAY,

        /** The phone has tilted away from its starting pose. */
        HOLD_LEVEL,

        /** Nothing is moving: the sweep can be stopped. */
        STILL,
    }

    private var gravityX = Float.NaN
    private var gravityY = Float.NaN
    private var gravityZ = Float.NaN

    private var referenceX = Float.NaN
    private var referenceY = Float.NaN
    private var referenceZ = Float.NaN

    private var lastGyroNanos = 0L
    private var lastAccelNanos = 0L

    private var yawDegrees = 0f
    private var speed = 0f
    private var lastMotionNanos = 0L
    private var samples = 0

    /**
     * Integrates one gyroscope sample (rad/s, device axes).
     *
     * The first sample only arms the clock: without a previous timestamp there
     * is no interval to integrate over.
     */
    fun onGyro(x: Float, y: Float, z: Float, timestampNanos: Long) {
        samples++
        val previous = lastGyroNanos
        lastGyroNanos = timestampNanos
        if (previous == 0L || timestampNanos <= previous) return

        // Clamp the interval so a suspended sensor (screen off, callback
        // burst after a pause) cannot integrate a huge bogus angle.
        val dt = ((timestampNanos - previous) / 1e9).toFloat().coerceIn(0f, MAX_SAMPLE_SECONDS)

        // Accelerometer gravity points down, while the camera pan axis is
        // world-up. Negating the gravity projection also makes the right-turn
        // sign match the right-hand rule documented on [yawDegrees].
        val yawRate = if (hasGravity()) {
            val gx = gravityX
            val gy = gravityY
            val gz = gravityZ
            val norm = sqrt(gx * gx + gy * gy + gz * gz)
            if (norm > 0f) -(x * gx + y * gy + z * gz) / norm else 0f
        } else {
            // No accelerometer yet: fall back to the device y axis (the
            // portrait-hold "up"), which is what a portrait sweep turns about.
            y
        }

        yawDegrees += Math.toDegrees(yawRate.toDouble()).toFloat() * dt

        val rateDegrees = abs(Math.toDegrees(yawRate.toDouble()).toFloat())
        // Time-constant smoothing, sample-rate independent.
        val alpha = (dt / SPEED_SMOOTHING_SECONDS).coerceIn(0f, 1f)
        speed += (rateDegrees - speed) * alpha

        if (rateDegrees >= STILL_SPEED_DEGREES_PER_SECOND) lastMotionNanos = timestampNanos
        if (lastMotionNanos == 0L) lastMotionNanos = timestampNanos
    }

    /** Folds one accelerometer sample (m/s², device axes) into the gravity estimate. */
    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNanos: Long) {
        samples++
        val norm = sqrt(x * x + y * y + z * z)
        if (norm <= 0.1f) return
        val ux = x / norm
        val uy = y / norm
        val uz = z / norm

        if (!hasGravity()) {
            gravityX = ux
            gravityY = uy
            gravityZ = uz
            referenceX = ux
            referenceY = uy
            referenceZ = uz
            lastAccelNanos = timestampNanos
            return
        }

        val dt = if (lastAccelNanos == 0L || timestampNanos <= lastAccelNanos) {
            0f
        } else {
            ((timestampNanos - lastAccelNanos) / 1e9).toFloat().coerceIn(0f, MAX_SAMPLE_SECONDS)
        }
        lastAccelNanos = timestampNanos
        // Slow low-pass: the gravity direction should follow a deliberate
        // re-hold of the phone, not its hand-shake.
        val alpha = (dt / GRAVITY_SMOOTHING_SECONDS).coerceIn(0f, 1f)
        gravityX += (ux - gravityX) * alpha
        gravityY += (uy - gravityY) * alpha
        gravityZ += (uz - gravityZ) * alpha
        val gnorm = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        if (gnorm > 0f) {
            gravityX /= gnorm
            gravityY /= gnorm
            gravityZ /= gnorm
        }
    }

    /** Clears all state, e.g. at the start of a new sweep. */
    fun reset() {
        gravityX = Float.NaN
        gravityY = Float.NaN
        gravityZ = Float.NaN
        referenceX = Float.NaN
        referenceY = Float.NaN
        referenceZ = Float.NaN
        lastGyroNanos = 0L
        lastAccelNanos = 0L
        yawDegrees = 0f
        speed = 0f
        lastMotionNanos = 0L
        samples = 0
    }

    /** Snapshot for the UI. Safe before any sensor has reported. */
    fun state(): GuideState {
        val forward = (yawDegrees * forwardSign).coerceAtLeast(0f)
        val tilt = tiltDegrees()
        val level = tilt <= LEVEL_TOLERANCE_DEGREES
        val still = speed < STILL_SPEED_DEGREES_PER_SECOND &&
            lastMotionNanos != 0L &&
            lastGyroNanos - lastMotionNanos >= (STILL_SECONDS * 1e9).toLong()
        return GuideState(
            yawDegrees = yawDegrees,
            forwardDegrees = forward,
            sweepProgress = (forward / targetSweepDegrees).coerceIn(0f, 1f),
            speedDegreesPerSecond = speed,
            tiltDegrees = tilt,
            level = level,
            still = still && samples > 0,
            hint = hint(level, still),
        )
    }

    private fun hint(level: Boolean, still: Boolean): Hint {
        if (samples == 0) return Hint.READY
        val turningForward = yawDegrees * forwardSign > 0f
        return when {
            !level -> Hint.HOLD_LEVEL
            speed > FAST_SPEED_DEGREES_PER_SECOND -> Hint.TOO_FAST
            speed > SLOW_SPEED_DEGREES_PER_SECOND && !turningForward -> Hint.WRONG_WAY
            still -> Hint.STILL
            else -> Hint.PAN
        }
    }

    private fun tiltDegrees(): Float {
        if (!hasGravity() || referenceX.isNaN()) return 0f
        val dot = gravityX * referenceX + gravityY * referenceY + gravityZ * referenceZ
        return Math.toDegrees(acos(dot.coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private fun hasGravity(): Boolean = !gravityX.isNaN()

    companion object {
        /** Angle that counts as a complete sweep for the progress display. */
        const val DEFAULT_TARGET_SWEEP_DEGREES = 75f

        /** Beyond this turn rate the stitcher starts dropping frames. */
        const val FAST_SPEED_DEGREES_PER_SECOND = 100f

        /** Below this the user is not really panning. */
        const val SLOW_SPEED_DEGREES_PER_SECOND = 8f

        /** Near-still threshold and the dwell that makes it "stopped". */
        const val STILL_SPEED_DEGREES_PER_SECOND = 6f
        const val STILL_SECONDS = 1.2f

        /** Tilt beyond this counts as "not level". */
        const val LEVEL_TOLERANCE_DEGREES = 10f

        private const val SPEED_SMOOTHING_SECONDS = 0.15f
        private const val GRAVITY_SMOOTHING_SECONDS = 0.5f
        private const val MAX_SAMPLE_SECONDS = 0.1f
    }
}
