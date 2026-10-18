package app.cameraplus.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Barcode CameraX analyzer using the Y luma plane (no image conversion,
 * network request, or cloud service). It reports each code once until it has
 * disappeared for a short run of frames, then permits a subsequent scan.
 *
 * [scanning] picks the code families: QR only (the original's behaviour), the
 * matrix codes, or every format ZXing understands.
 */
class QrCodeAnalyzer(
    private val onResult: (String) -> Unit,
    scanning: BarcodeScanning = BarcodeScanning.QR_ONLY,
) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder(BarcodeFormats.formatsFor(scanning))
    private var lastResult: String? = null
    private var missingFrames = 0

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val yBuffer = plane.buffer.duplicate()
            val baseOffset = yBuffer.position()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val luminance = ByteArray(width * height)
            for (y in 0 until height) {
                val rowStart = y * rowStride
                val targetStart = y * width
                for (x in 0 until width) {
                    luminance[targetStart + x] = yBuffer.get(baseOffset + rowStart + x * pixelStride)
                }
            }
            val text = decoder.decode(luminance, width, height)?.takeIf { it.isNotBlank() }
            if (text == null) {
                missingFrames++
                if (missingFrames >= RESET_AFTER_MISSING_FRAMES) lastResult = null
            } else {
                missingFrames = 0
                if (text != lastResult) {
                    lastResult = text
                    onResult(text)
                }
            }
        } finally {
            image.close()
        }
    }

    private companion object {
        const val RESET_AFTER_MISSING_FRAMES = 12
    }
}
