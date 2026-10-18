package app.cameraplus.camera

import com.google.zxing.BarcodeFormat

/**
 * Barcode families offered by QR mode. The original scans QR only;
 * GrapheneOS Secure Camera is the model for letting the user widen the search.
 */
enum class BarcodeScanning(val label: String) {
    QR_ONLY("QR ONLY"),
    TWO_D("QR + 2D"),
    ALL("ALL CODES"),
}

/** Maps a scanning choice onto the ZXing formats to look for. */
object BarcodeFormats {

    val TWO_D_FORMATS = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.AZTEC,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.PDF_417,
    )

    val PRODUCT_FORMATS = listOf(
        BarcodeFormat.EAN_8,
        BarcodeFormat.EAN_13,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.CODE_128,
        BarcodeFormat.ITF,
        BarcodeFormat.CODABAR,
    )

    fun formatsFor(scanning: BarcodeScanning): List<BarcodeFormat> = when (scanning) {
        BarcodeScanning.QR_ONLY -> listOf(BarcodeFormat.QR_CODE)
        BarcodeScanning.TWO_D -> TWO_D_FORMATS
        BarcodeScanning.ALL -> TWO_D_FORMATS + PRODUCT_FORMATS
    }
}
