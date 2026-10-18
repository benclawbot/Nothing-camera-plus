package app.cameraplus.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Android-free QR luminance decoder, shared by CameraX and JVM tests. */
class QrDecoder(formats: List<BarcodeFormat> = listOf(BarcodeFormat.QR_CODE)) {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to formats,
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && luminance.size >= width * height)
        return try {
            val source = PlanarYUVLuminanceSource(
                luminance,
                width,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
