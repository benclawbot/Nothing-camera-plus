package app.cameraplus.camera

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Pure math for the horizontal level indicator (M5). Device attitude comes in
 * as the gravity vector; the screen roll is the angle of gravity around the
 * viewing axis.
 */
object LevelMath {

    /** Roll in degrees: 0 when the device holds level in portrait. */
    fun rollDegrees(gravityX: Float, gravityY: Float): Float =
        Math.toDegrees(atan2(gravityX.toDouble(), -gravityY.toDouble())).toFloat()

    /** Within [tolerance] degrees of horizontal counts as level. */
    fun isLevel(rollDegrees: Float, tolerance: Float = 0.7f): Boolean =
        abs(rollDegrees) <= tolerance

    /**
     * The level is only meaningful while the camera is held roughly upright;
     * pointed at the sky or the floor, gravity no longer describes roll.
     */
    fun isPlausibleShootingAttitude(gravityZ: Float): Boolean =
        abs(gravityZ) < 5f
}
