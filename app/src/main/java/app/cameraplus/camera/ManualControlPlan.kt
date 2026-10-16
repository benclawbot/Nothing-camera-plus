package app.cameraplus.camera

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Manual capture intent for Expert mode (M5). `null` always means "leave it
 * to the camera", so a fresh instance is full auto.
 */
data class ManualControls(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDiopters: Float? = null,
) {
    fun isAuto(): Boolean = iso == null && exposureTimeNs == null && focusDiopters == null
}

/**
 * Maps UI gauges onto the camera's manual domains.
 *
 * The gauge is a fraction in `[0, 1]` where `0` is AUTO (null, the camera
 * decides) and `1` is the far end of the range the sensor reports. ISO and
 * exposure use a logarithmic curve so the low end is not crushed into one
 * step; focus is a linear fraction of the minimum focus distance (diopters),
 * matching how manual focus sliders behave.
 *
 * Pure math only, so it is JVM-testable; the session maps the values onto
 * Camera2 capture request keys.
 */
object ManualControlPlan {

    const val GAUGE_AUTO = 0f

    fun isoFor(fraction: Float, range: IntRange): Int? {
        val gauge = fraction.coerceIn(0f, 1f)
        if (gauge <= GAUGE_AUTO) return null
        val min = range.first.coerceAtLeast(1)
        val max = range.last.coerceAtLeast(min)
        val value = min * (max.toDouble() / min).pow(gauge.toDouble())
        return value.roundToLong().coerceIn(min.toLong(), max.toLong()).toInt()
    }

    fun exposureFor(fraction: Float, range: LongRange): Long? {
        val gauge = fraction.coerceIn(0f, 1f)
        if (gauge <= GAUGE_AUTO) return null
        val min = range.first.coerceAtLeast(1L)
        val max = range.last.coerceAtLeast(min)
        val value = min * (max.toDouble() / min.toDouble()).pow(gauge.toDouble())
        return value.roundToLong().coerceIn(min, max)
    }

    fun focusFor(fraction: Float, minimumFocusDistance: Float): Float? {
        val gauge = fraction.coerceIn(0f, 1f)
        if (gauge <= GAUGE_AUTO) return null
        if (minimumFocusDistance <= 0f) return null
        return gauge * minimumFocusDistance
    }
}
