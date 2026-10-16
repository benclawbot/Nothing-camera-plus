package app.cameraplus.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    @Test
    fun decodesQrFromAPlanarLuminanceBuffer() {
        val expected = "https://example.org/camera-plus"
        val matrix = QRCodeWriter().encode(expected, BarcodeFormat.QR_CODE, 240, 240)
        val luminance = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                luminance[y * matrix.width + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        assertEquals(expected, QrDecoder().decode(luminance, matrix.width, matrix.height))
    }

    @Test
    fun blankFramesDoNotProduceFalseCodes() {
        assertNull(QrDecoder().decode(ByteArray(80 * 60) { 0xFF.toByte() }, 80, 60))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadLuminanceDimensions() {
        QrDecoder().decode(byteArrayOf(0), width = 2, height = 2)
    }
}
