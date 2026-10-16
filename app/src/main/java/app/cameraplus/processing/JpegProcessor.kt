package app.cameraplus.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.cameraplus.settings.ProcessingSettings
import java.io.OutputStream

/**
 * Decodes a captured JPEG, applies [ProcessingSettings] through the pure
 * [ImageProcessor] math, and writes a re-encoded JPEG. Intended to run on a
 * background thread.
 *
 * Memory matters here: a 50 MP frame decoded to ARGB_8888 is 200 MB, and the
 * earlier version of this file made it far worse by copying the bitmap and
 * buffering a second encoded JPEG in memory. The bitmap is decoded mutable
 * (no copy), size is checked *before* full decode, and compression streams
 * directly to the output.
 *
 * Processing is best-effort by design: [process] returns false instead of
 * throwing when the frame is too large or the device refuses, so the caller
 * can save the untouched JPEG rather than losing the shot.
 */
object JpegProcessor {

    /** Above this pixel count the frame is saved unprocessed rather than risking an OOM. */
    private const val MAX_PIXELS = 8_000_000L

    private const val JPEG_QUALITY = 95

    /**
     * @return true when the frame was processed and written, false when the
     *         caller should write the original bytes instead.
     */
    fun process(jpegBytes: ByteArray, settings: ProcessingSettings, out: OutputStream): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_PIXELS
        ) {
            return false
        }
        val bitmap = try {
            decode(jpegBytes)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width.toLong() * height.toLong() > MAX_PIXELS) return false

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            ImageProcessor.apply(pixels, width, height, settings)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        } catch (t: Throwable) {
            // OutOfMemoryError included: the shot matters more than the look.
            false
        } finally {
            bitmap.recycle()
        }
    }

    /** Mutable ARGB decode so the pixels can be edited in place. */
    private fun decode(jpegBytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
    }
}
