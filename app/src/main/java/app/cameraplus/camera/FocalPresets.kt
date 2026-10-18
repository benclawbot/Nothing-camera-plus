package app.cameraplus.camera

import kotlin.math.abs

/**
 * Focal-length presets, the way the Nothing community asked for them: a default
 * focal of 24 / 28 / 35 mm, and a tap on the active zoom chip that steps
 * through them (28 mm, 35 mm, then back to the lens's own wide stop).
 *
 * Everything is expressed in 35 mm-equivalent millimetres; the conversion to a
 * displayed zoom ratio uses the reference lens's own equivalent focal, because
 * displayed 1x is that lens.
 */
object FocalPresets {

    /** Presets offered by the default-focal setting. */
    val MILLIMETRES = listOf(24, 28, 35)

    /**
     * The displayed zoom that lands on [targetMm], clamped to what the session
     * allows. Null when the camera has not reported a usable reference focal,
     * so a caller can say so rather than guessing.
     */
    fun displayedZoomFor(
        targetMm: Int,
        referenceEqFocalMm: Float,
        minZoom: Float,
        maxZoom: Float,
    ): Float? {
        if (targetMm <= 0) return null
        if (!referenceEqFocalMm.isFinite() || referenceEqFocalMm <= 0f) return null
        val ratio = targetMm / referenceEqFocalMm
        if (!ratio.isFinite() || ratio <= 0f) return null
        val low = if (minZoom.isFinite() && minZoom > 0f) minZoom else 1f
        val high = if (maxZoom.isFinite() && maxZoom > 0f) maxOf(maxZoom, low) else low
        return ratio.coerceIn(low, high)
    }

    /** Label shown by the default-focal setting for the lens's own wide stop. */
    const val AUTO_LABEL = "AUTO"

    /** 35 mm-equivalent millimetres as the settings sheet shows them. */
    fun labelFor(mm: Int): String = if (mm <= 0) AUTO_LABEL else "$mm MM"

    /** The setting's label back as millimetres; anything unknown means AUTO. */
    fun fromLabel(label: String): Int =
        MILLIMETRES.firstOrNull { labelFor(it) == label } ?: AUTO

    /**
     * Where a tap on the active zoom chip goes: 28 mm, then 35 mm, then back to
     * the lens's own wide stop ([AUTO]). A 24 mm displayed focal also steps to
     * 28 mm, because 24 mm is already the wide stop on most lenses.
     */
    const val AUTO = 0

    fun next(currentMm: Int): Int = when (currentMm) {
        MILLIMETRES[1] -> MILLIMETRES[2]
        MILLIMETRES[2] -> AUTO
        else -> MILLIMETRES[1]
    }

    /**
     * The preset a live equivalent focal is sitting on, or [AUTO] when it is
     * not within [toleranceMm] of one.
     */
    fun nearestPreset(liveMm: Float, toleranceMm: Float = 2f): Int {
        if (!liveMm.isFinite() || liveMm <= 0f) return AUTO
        val nearest = MILLIMETRES.minByOrNull { abs(it - liveMm) } ?: return AUTO
        return if (abs(nearest - liveMm) <= toleranceMm) nearest else AUTO
    }
}
