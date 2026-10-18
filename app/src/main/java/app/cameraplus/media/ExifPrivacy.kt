package app.cameraplus.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Privacy pass over a saved still (GrapheneOS Secure Camera's "strip metadata"
 * and Open Camera's custom Exif fields).
 *
 * Tag *names* are kept as the standard Exif strings rather than the library's
 * constants, so the list is plain data that can be unit tested without an
 * Android runtime. Everything here is best effort: a camera or a HAL that
 * refuses the rewrite must never cost the user a shot.
 */
object ExifPrivacy {

    /** Where the photo was taken and with what: removed when stripping is on. */
    val STRIP_TAG_NAMES = listOf(
        // Position, heading, altitude, speed and their offsets.
        "GPSLatitude",
        "GPSLatitudeRef",
        "GPSLongitude",
        "GPSLongitudeRef",
        "GPSAltitude",
        "GPSAltitudeRef",
        "GPSTimeStamp",
        "GPSDateStamp",
        "GPSProcessingMethod",
        "GPSAreaInformation",
        "GPSSpeed",
        "GPSSpeedRef",
        "GPSImgDirection",
        "GPSImgDirectionRef",
        "GPSDestBearing",
        "GPSDestBearingRef",
        // Device and lens identity, firmware and owner.
        "Make",
        "Model",
        "Software",
        "LensMake",
        "LensModel",
        "BodySerialNumber",
        "LensSerialNumber",
        "CameraOwnerName",
        "ImageUniqueID",
    )

    /** Tags the app writes when the user fills the author fields in. */
    const val TAG_ARTIST = "Artist"
    const val TAG_COPYRIGHT = "Copyright"

    /**
     * Strips [STRIP_TAG_NAMES] when [strip] is set and writes [author] /
     * [copyright] when they are non-blank. Leaves orientation and capture time
     * alone in both cases, because they describe the shot rather than the
     * person holding the phone. Returns false when the file could not be
     * revisited.
     */
    fun apply(
        resolver: ContentResolver,
        uri: Uri,
        strip: Boolean,
        author: String?,
        copyright: String?,
    ): Boolean {
        val artist = author?.takeIf { it.isNotBlank() }
        val rights = copyright?.takeIf { it.isNotBlank() }
        if (!strip && artist == null && rights == null) return false
        return runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                if (strip) {
                    STRIP_TAG_NAMES.forEach { tag -> exif.setAttribute(tag, null) }
                }
                exif.setAttribute(TAG_ARTIST, artist)
                exif.setAttribute(TAG_COPYRIGHT, rights)
                exif.saveAttributes()
            }
            true
        }.getOrDefault(false)
    }

    /** Convenience for callers that already hold a [Context]. */
    fun apply(
        context: Context,
        uri: Uri,
        strip: Boolean,
        author: String?,
        copyright: String?,
    ): Boolean = apply(context.contentResolver, uri, strip, author, copyright)
}
