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
    val whiteBalance: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    val colorTemperatureKelvin: Int? = null,
) {
    fun isAuto(): Boolean = iso == null && exposureTimeNs == null && focusDiopters == null &&
        whiteBalance == WhiteBalancePreset.AUTO && colorTemperatureKelvin == null
}

/** Camera2 AWB presets exposed by the original Expert mode. */
enum class WhiteBalancePreset(val label: String) {
    AUTO("AUTO"),
    INCANDESCENT("TUNGSTEN"),
    FLUORESCENT("FLUORESCENT"),
    DAYLIGHT("DAYLIGHT"),
    CLOUDY("CLOUDY"),
    TWILIGHT("TWILIGHT"),
    SHADE("SHADE"),
    CUSTOM("KELVIN"),
}

data class WhiteBalanceGains(val red: Float, val green: Float, val blue: Float)

/**
 * Approximate daylight-locus RGB gains for Camera2's manual colour correction.
 * This is a standards-based clean-room approximation, not the OEM's sensor
 * calibration. Gain range is bounded to values commonly accepted by Camera2.
 */
object WhiteBalancePlan {
    fun gainsForKelvin(kelvin: Int): WhiteBalanceGains {
        val temperature = kelvin.coerceIn(2_000, 10_000) / 100.0
        val red: Double
        val green: Double
        val blue: Double
        if (temperature <= 66.0) {
            red = 255.0
            green = 99.4708025861 * kotlin.math.ln(temperature) - 161.1195681661
            blue = if (temperature <= 19.0) 0.0 else {
                138.5177312231 * kotlin.math.ln(temperature - 10.0) - 305.0447927307
            }
        } else {
            red = 329.698727446 * (temperature - 60.0).pow(-0.1332047592)
            green = 288.1221695283 * (temperature - 60.0).pow(-0.0755148492)
            blue = 255.0
        }
        val r = red.coerceIn(1.0, 255.0)
        val g = green.coerceIn(1.0, 255.0)
        val b = blue.coerceIn(1.0, 255.0)
        return WhiteBalanceGains(
            red = (g / r).toFloat().coerceIn(0.25f, 8f),
            green = 1f,
            blue = (g / b).toFloat().coerceIn(0.25f, 8f),
        )
    }
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
