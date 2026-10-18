package app.cameraplus.camera

import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeFormatsTest {

    @Test
    fun qrOnlyLooksForQrCodes() {
        assertEquals(
            listOf(BarcodeFormat.QR_CODE),
            BarcodeFormats.formatsFor(BarcodeScanning.QR_ONLY),
        )
    }

    @Test
    fun twoDLooksForTheMatrixCodesButNoProductCodes() {
        val formats = BarcodeFormats.formatsFor(BarcodeScanning.TWO_D)

        assertTrue(BarcodeFormat.QR_CODE in formats)
        assertTrue(BarcodeFormat.AZTEC in formats)
        assertTrue(BarcodeFormat.DATA_MATRIX in formats)
        assertTrue(BarcodeFormat.PDF_417 in formats)
        assertTrue(BarcodeFormat.EAN_13 !in formats)
        assertTrue(BarcodeFormat.CODE_128 !in formats)
    }

    @Test
    fun allCodesAddsTheProductAndIndustrialBarcodes() {
        val formats = BarcodeFormats.formatsFor(BarcodeScanning.ALL)

        assertTrue(BarcodeFormat.QR_CODE in formats)
        for (extra in BarcodeFormats.PRODUCT_FORMATS) {
            assertTrue(extra in formats)
        }
        assertEquals(
            BarcodeFormats.TWO_D_FORMATS.size + BarcodeFormats.PRODUCT_FORMATS.size,
            formats.size,
        )
    }

    @Test
    fun theChoicesAreOrderedFromNarrowToWide() {
        assertTrue(
            BarcodeFormats.formatsFor(BarcodeScanning.QR_ONLY).size <
                BarcodeFormats.formatsFor(BarcodeScanning.TWO_D).size,
        )
        assertTrue(
            BarcodeFormats.formatsFor(BarcodeScanning.TWO_D).size <
                BarcodeFormats.formatsFor(BarcodeScanning.ALL).size,
        )
    }
}
