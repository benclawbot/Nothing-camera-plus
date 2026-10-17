package app.cameraplus.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Hands a just-captured item to the device's gallery app the way stock
 * cameras do, instead of showing an in-app interstitial first.
 *
 * Order of attempts (first one that resolves wins):
 *  1. The AOSP camera review action, pinned to the system's designated
 *     gallery app (`CATEGORY_APP_GALLERY`), data = the newest capture.
 *  2. The same review action, unpinned, but only when it resolves to a
 *     single app (never the chooser).
 *  3. Plain `ACTION_VIEW`, again only when unambiguous.
 *  4. The gallery app's home screen (no item).
 *
 * Returns `false` when nothing could take any of those, so the caller can
 * keep its own viewer as the last resort.
 */
object SystemGallery {

    /**
     * AOSP camera review action; the interop action gallery apps accept for
     * "just captured this, show it" (see Android's Camera app / CDD flows).
     */
    private const val ACTION_REVIEW = "com.android.camera.action.REVIEW"

    /**
     * Opens the gallery for [uri] (or the gallery home when it is null).
     * Must be called from an activity context.
     */
    fun open(context: Context, uri: Uri?): Boolean {
        val galleryPackage = resolveGalleryPackage(context)
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            val review = reviewIntent(uri, mime)
            if (galleryPackage != null && resolvesToPackage(context, review, galleryPackage)) {
                return start(context, review.setPackage(galleryPackage))
            }
            if (resolvesDirectly(context, review)) {
                return start(context, review)
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (resolvesDirectly(context, view)) {
                return start(context, view)
            }
        }
        if (galleryPackage != null) {
            val home = galleryHomeIntent().setPackage(galleryPackage)
            if (resolvesToPackage(context, home, galleryPackage)) {
                return start(context, home)
            }
        }
        return false
    }

    private fun reviewIntent(uri: Uri, mime: String): Intent =
        Intent(ACTION_REVIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun galleryHomeIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)

    /** The system's designated gallery app, or null when it has none. */
    private fun resolveGalleryPackage(context: Context): String? =
        runCatching {
            context.packageManager.resolveActivity(
                galleryHomeIntent(),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()

    private fun resolvesToPackage(context: Context, intent: Intent, packageName: String): Boolean =
        runCatching {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName == packageName
        }.getOrDefault(false)

    /**
     * True only when the intent hits one real app; a default-less implicit
     * intent resolves to the system chooser instead, which is exactly the
     * "something intermediate" this helper exists to avoid.
     */
    private fun resolvesDirectly(context: Context, intent: Intent): Boolean =
        runCatching {
            val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo
            info != null &&
                info.packageName != "android" &&
                !info.name.contains("ResolverActivity")
        }.getOrDefault(false)

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
