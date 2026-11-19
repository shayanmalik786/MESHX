package com.meshx.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The black and white squares of a QR code. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

object QrCode {
    /** Returns null when the text does not fit into a QR code. */
    fun encode(text: String): QrMatrix? = try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // Asking for a 1 x 1 picture makes the writer return exactly one cell per module.
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
        val size = bits.width
        QrMatrix(size, BooleanArray(size * size) { bits.get(it % size, it / size) })
    } catch (e: Exception) {
        null
    }
}
