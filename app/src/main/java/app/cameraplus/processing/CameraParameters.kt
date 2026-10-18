package app.cameraplus.processing

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The capture parameters stamped on the optional watermark line.
 *
 * The original renders one EXIF line under its watermark
 * ("24MM F/1.9 1/50S ISO 123", `settings_watermark_preview_exif_info`); this
 * clean-room version formats the same information from the capture's own
 * metadata. Parts the device did not report are omitted, so a capture with
 * sparse metadata still gets a shorter, honest line instead of placeholder
 * numbers.
 *
 * [focalMm] is the 35 mm-equivalent focal length (the user-facing convention,
 * the same basis as the zoom labels), never the physical focal the EXIF tag
 * stores.
 */
data class CameraParameters(
    val focalMm: Float? = null,
    val aperture: Float? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
) {

    /** "24MM F/1.9 1/50S ISO 123", or null when nothing is known. */
    fun formatted(): String? {
        val parts = buildList {
            focalMm?.takeIf { it > 0f && it.isFinite() }?.let { add("${it.roundToInt()}MM") }
            aperture?.takeIf { it > 0f && it.isFinite() }?.let {
                add(String.format(Locale.US, "F/%.1f", it))
            }
            exposureSeconds?.takeIf { it > 0.0 && it.isFinite() }?.let { add(exposureLabel(it)) }
            iso?.takeIf { it > 0 }?.let { add("ISO $it") }
        }
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun exposureLabel(seconds: Double): String {
        if (seconds >= 1.0) {
            val rounded = (seconds * 10).roundToInt() / 10.0
            return if (rounded % 1.0 == 0.0) {
                "${rounded.toInt()}S"
            } else {
                String.format(Locale.US, "%.1fS", rounded)
            }
        }
        val inverse = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        return "1/${inverse}S"
    }
}
