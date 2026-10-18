package app.cameraplus.settings

import android.content.Context

/**
 * The gallery app the camera hands captures to; null means automatic.
 *
 * Automatic is the default and keeps the dynamic resolution chain in
 * `SystemGallery`; pinning a package just makes that one app the first try,
 * and a package that is later uninstalled silently falls back to automatic.
 */
class GalleryPreferenceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("gallery_options", Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() }

    fun save(packageName: String?) {
        prefs.edit().apply {
            if (packageName == null) remove(KEY_PACKAGE) else putString(KEY_PACKAGE, packageName)
        }.apply()
    }

    private companion object {
        const val KEY_PACKAGE = "preferred_package"
    }
}
