package app.cameraplus.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.cameraplus.settings.ProcessingSettings
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Decodes a captured JPEG, applies [ProcessingSettings] through the pure
 * [ImageProcessor] math, and writes a re-encoded JPEG. Intended to run on a
 * background thread.
 *
 * Note: this decodes at full resolution, so very large sensors cost real
 * memory and time here; on-device tuning is pending device validation.
 */
object JpegProcessor {

    fun process(jpegBytes: ByteArray, settings: ProcessingSettings, out: OutputStream) {
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: throw IllegalArgumentException("could not decode the captured JPEG")
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        if (bitmap == null) {
            throw IllegalStateException("could not open the captured JPEG for editing")
        }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ImageProcessor.apply(pixels, bitmap.width, bitmap.height, settings)
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val encoded = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded)
        bitmap.recycle()
        if (!ok) {
            throw IllegalStateException("could not encode the processed JPEG")
        }
        out.write(encoded.toByteArray())
    }
}
