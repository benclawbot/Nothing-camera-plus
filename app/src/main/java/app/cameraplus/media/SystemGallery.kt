package app.cameraplus.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * Hands a just-captured item to the device's gallery app the way stock
 * cameras do, instead of showing an in-app interstitial first.
 *
 * Resolution order (first hit wins):
 *  1. The user's default handler for the AOSP camera review action.
 *  2. A system gallery app that accepts the review action.
 *  3. Any app that accepts the review action (that action is camera-specific,
 *     so its handlers are viewers by construction).
 *  4. Whatever the user has set as the plain viewer, or a gallery app, for
 *     viewing the item.
 *  5. The gallery app's home screen (when there is no item yet, or nothing
 *     claimed the review/view intents).
 *
 * The system chooser is never used: a chooser is exactly the "something
 * intermediate" this helper exists to avoid. Every step resolves to one
 * concrete external activity. It returns `false` if the device has no
 * gallery/viewer; callers should show an explanatory message rather than
 * opening an internal gallery.
 *
 * Apps are picked because the system says they are galleries / review
 * viewers, never by hardcoding vendor package names.
 *
 * When the user has chosen a gallery in the camera settings, that one package
 * is tried first ([preferredPackage]); if it is gone or cannot take the item,
 * the chain above runs unchanged.
 */
object SystemGallery {

    /** One installed app offered in the gallery picker. */
    data class GalleryApp(val packageName: String, val label: String)

    /**
     * Package to hand captures to for a picked picker label; null means
     * automatic (the chain in [open]). Labels come from [installedGalleries].
     */
    fun galleryPackageForLabel(apps: List<GalleryApp>, label: String): String? =
        apps.firstOrNull { it.label == label }?.packageName

    /**
     * AOSP camera review action; the interop action gallery apps accept for
     * "just captured this, show it".
     */
    private const val ACTION_REVIEW = "com.android.camera.action.REVIEW"

    /** Opens the gallery at [uri], or the gallery home when [uri] is null. */
    fun open(context: Context, uri: Uri?, preferredPackage: String? = null): Boolean {
        if (preferredPackage != null && openPreferred(context, uri, preferredPackage)) return true
        val galleries = queryHandlers(context, galleryHome())
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()

        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "*/*"

            val review = Intent(ACTION_REVIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            defaultHandler(context, review)?.let {
                if (launch(context, review, it)) return true
            }
            pick(queryHandlers(context, review), galleries, requireGallery = false)?.let {
                if (launch(context, review, it)) return true
            }

            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            defaultHandler(context, view)?.let {
                if (launch(context, view, it)) return true
            }
            // Plain VIEW is claimed by far more than galleries (editors, chat
            // apps, …), so only ever aim it at something that is also a
            // gallery app.
            pick(queryHandlers(context, view), galleries, requireGallery = true)?.let {
                if (launch(context, view, it)) return true
            }
        }

        val home = galleryHome()
        defaultHandler(context, home)?.let {
            if (launch(context, home, it)) return true
        }
        pick(queryHandlers(context, home), galleries, requireGallery = false)?.let {
            if (launch(context, home, it)) return true
        }
        return false
    }

    /**
     * Tries the user's chosen gallery explicitly: review, then plain view,
     * then the gallery home, always aimed at that one package. Returns false
     * when the package cannot take any of them, so the caller falls back to
     * the normal chain instead of failing the hand-off.
     */
    private fun openPreferred(context: Context, uri: Uri?, packageName: String): Boolean {
        val mime = uri?.let { context.contentResolver.getType(it) } ?: "*/*"
        val candidates = buildList {
            if (uri != null) {
                add(
                    Intent(ACTION_REVIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
            add(galleryHome())
        }
        candidates.forEach { intent ->
            val handler = queryHandlers(context, intent)
                .firstOrNull { it.activityInfo?.packageName == packageName }
                ?: return@forEach
            if (launch(context, intent, handler)) return true
        }
        return false
    }

    /**
     * Galleries installed right now, for the settings picker: every app the
     * system offers for the gallery home or for viewing images, deduplicated
     * and sorted by label. This app itself is never offered.
     */
    fun installedGalleries(context: Context): List<GalleryApp> {
        val packages = LinkedHashSet<String>()
        queryHandlers(context, galleryHome()).forEach { info ->
            info.activityInfo?.packageName?.let(packages::add)
        }
        queryHandlers(context, Intent(Intent.ACTION_VIEW).apply { type = "image/*" })
            .forEach { info -> info.activityInfo?.packageName?.let(packages::add) }

        val packageManager = context.packageManager
        return packages
            .filterNot { it == context.packageName }
            .mapNotNull { packageName ->
                runCatching {
                    val info = packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0),
                    )
                    GalleryApp(packageName, packageManager.getApplicationLabel(info).toString())
                }.getOrNull()
            }
            .filter { it.label.isNotBlank() }
            .distinctBy { it.label }
            .sortedBy { it.label.lowercase() }
    }

    private fun galleryHome(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)

    /** Every activity the system would offer for [intent], in its order. */
    private fun queryHandlers(context: Context, intent: Intent): List<ResolveInfo> =
        runCatching { context.packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())

    /**
     * The handler the system would use without asking: a user-set default.
     * A default-less implicit intent resolves to the system chooser instead,
     * which is not a handler.
     */
    private fun defaultHandler(context: Context, intent: Intent): ResolveInfo? {
        val resolved = context.packageManager.resolveActivity(intent, 0) ?: return null
        val info = resolved.activityInfo ?: return null
        val isChooser = info.packageName == "android" || info.name.contains("ResolverActivity")
        return if (isChooser) null else resolved
    }

    private fun pick(
        handlers: List<ResolveInfo>,
        galleries: Set<String>,
        requireGallery: Boolean,
    ): ResolveInfo? {
        handlers.firstOrNull { it.activityInfo?.packageName in galleries }?.let { return it }
        return if (requireGallery) null else handlers.firstOrNull()
    }

    private fun launch(context: Context, intent: Intent, target: ResolveInfo): Boolean {
        val info = target.activityInfo ?: return false
        val explicit = Intent(intent)
            .setComponent(ComponentName(info.packageName, info.name))
        return runCatching {
            context.startActivity(explicit)
            true
        }.getOrDefault(false)
    }
}
