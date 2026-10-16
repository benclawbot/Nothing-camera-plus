package app.cameraplus.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import app.cameraplus.settings.ProcessingSettings
import app.cameraplus.settings.WatermarkOptions
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

    /** Pixel math uses full-size integer buffers, so keep its ceiling conservative. */
    private const val MAX_COLOR_PROCESSING_PIXELS = 8_000_000L

    /** A watermark-only Canvas pass needs just the bitmap, so it can cover 12 MP JPEGs. */
    private const val MAX_BITMAP_ONLY_PIXELS = 24_000_000L
    private const val MAX_BITMAP_MIRROR_PIXELS = 12_000_000L

    private const val JPEG_QUALITY = 95

    /**
     * @return true when the frame was processed and written, false when the
     *         caller should write the original bytes instead.
     */
    fun process(
        jpegBytes: ByteArray,
        settings: ProcessingSettings,
        out: OutputStream,
        mirrorHorizontal: Boolean = false,
        watermark: WatermarkOptions = WatermarkOptions(),
        location: Location? = null,
    ): Boolean {
        val requiresColorPixels = settings != ProcessingSettings.NATURAL
        val maxPixels = when {
            requiresColorPixels -> MAX_COLOR_PROCESSING_PIXELS
            mirrorHorizontal -> MAX_BITMAP_MIRROR_PIXELS
            else -> MAX_BITMAP_ONLY_PIXELS
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > maxPixels
        ) {
            return false
        }
        var bitmap = try {
            decode(jpegBytes)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return false
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width.toLong() * height.toLong() > maxPixels) return false

            if (requiresColorPixels) {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                ImageProcessor.apply(pixels, width, height, settings)
                if (mirrorHorizontal) ImageProcessor.mirrorHorizontally(pixels, width, height)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            } else if (mirrorHorizontal) {
                val mirrored = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    width,
                    height,
                    Matrix().apply { postScale(-1f, 1f) },
                    true,
                )
                if (mirrored !== bitmap) {
                    bitmap.recycle()
                    bitmap = mirrored
                }
            }
            WatermarkRenderer.draw(bitmap, watermark, location)

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
