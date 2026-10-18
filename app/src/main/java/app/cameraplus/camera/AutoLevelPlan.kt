package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pure policy for optional horizon correction at capture time. */
object AutoLevelPlan {
    const val MAX_CORRECTION_DEGREES = 8f
    private const val MAX_SENSOR_TILT_DEGREES = 15f

    /**
     * Correction to apply after the capture has been oriented upright. Large
     * roll readings are treated as a device-rotation change, not a small
     * horizon error; limiting the correction also keeps the crop reasonable.
     */
    fun correctionDegrees(screenRollDegrees: Float?): Float {
        val roll = screenRollDegrees ?: return 0f
        if (!roll.isFinite() || abs(roll) > MAX_SENSOR_TILT_DEGREES) return 0f
        return (-roll).coerceIn(-MAX_CORRECTION_DEGREES, MAX_CORRECTION_DEGREES)
    }

    /** Uniform scale that fills the original bounds after rotation. */
    fun fillScale(width: Int, height: Int, rotationDegrees: Float): Float {
        require(width > 0 && height > 0) { "image must not be empty" }
        val radians = Math.toRadians(abs(rotationDegrees.toDouble()))
        val c = cos(radians)
        val s = sin(radians)
        val safeWidth = (width * c - height * s).coerceAtLeast(1.0)
        val safeHeight = (height * c - width * s).coerceAtLeast(1.0)
        return maxOf(width / safeWidth, height / safeHeight).toFloat().coerceAtLeast(1f)
    }
}
