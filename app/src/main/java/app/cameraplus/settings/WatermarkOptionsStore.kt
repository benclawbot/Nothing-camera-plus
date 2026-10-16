package app.cameraplus.settings

import android.content.Context

/** Persists the opt-in watermark fields separately from image processing. */
class WatermarkOptionsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("watermark_options", Context.MODE_PRIVATE)

    fun load(): WatermarkOptions = WatermarkOptions(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        includeDate = prefs.getBoolean(KEY_DATE, true),
        includeTime = prefs.getBoolean(KEY_TIME, true),
        includeLocation = prefs.getBoolean(KEY_LOCATION, false),
        authorName = prefs.getString(KEY_AUTHOR, "").orEmpty().take(MAX_AUTHOR_LENGTH),
    )

    fun save(options: WatermarkOptions) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, options.enabled)
            .putBoolean(KEY_DATE, options.includeDate)
            .putBoolean(KEY_TIME, options.includeTime)
            .putBoolean(KEY_LOCATION, options.includeLocation)
            .putString(KEY_AUTHOR, options.authorName.take(MAX_AUTHOR_LENGTH))
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_DATE = "date"
        const val KEY_TIME = "time"
        const val KEY_LOCATION = "location"
        const val KEY_AUTHOR = "author"
        const val MAX_AUTHOR_LENGTH = 32
    }
}
