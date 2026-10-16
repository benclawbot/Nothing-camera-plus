package app.cameraplus.settings

import android.content.Context

/**
 * Persists the processing intent.
 *
 * Values are stored as plain floats; [load] clamps them so corrupt or
 * out-of-range preferences can never crash the pipeline or the settings UI.
 */
class ProcessingSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("processing_settings", Context.MODE_PRIVATE)

    fun load(): ProcessingSettings = ProcessingSettings(
        sharpening = sanitizeUnitFloat(prefs.getFloat(KEY_SHARPENING, 0f)),
        noiseReduction = sanitizeUnitFloat(prefs.getFloat(KEY_NOISE_REDUCTION, 0f)),
        hdr = sanitizeUnitFloat(prefs.getFloat(KEY_HDR, 0f)),
        skinSmoothing = sanitizeUnitFloat(prefs.getFloat(KEY_SKIN_SMOOTHING, 0f)),
        filter = prefs.getString(KEY_FILTER, PhotoFilter.NATURAL.name)
            ?.let { value -> PhotoFilter.entries.firstOrNull { it.name == value } }
            ?: PhotoFilter.NATURAL,
    )

    fun save(settings: ProcessingSettings) {
        prefs.edit()
            .putFloat(KEY_SHARPENING, settings.sharpening)
            .putFloat(KEY_NOISE_REDUCTION, settings.noiseReduction)
            .putFloat(KEY_HDR, settings.hdr)
            .putFloat(KEY_SKIN_SMOOTHING, settings.skinSmoothing)
            .putString(KEY_FILTER, settings.filter.name)
            .apply()
    }

    private companion object {
        const val KEY_SHARPENING = "sharpening"
        const val KEY_NOISE_REDUCTION = "noise_reduction"
        const val KEY_HDR = "hdr"
        const val KEY_SKIN_SMOOTHING = "skin_smoothing"
        const val KEY_FILTER = "photo_filter"
    }
}

/** Clamp a persisted value into `[0, 1]`; non-finite values mean "neutral". */
internal fun sanitizeUnitFloat(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
