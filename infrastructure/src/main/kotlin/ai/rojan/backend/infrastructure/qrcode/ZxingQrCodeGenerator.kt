package ai.rojan.backend.infrastructure.qrcode

import ai.rojan.backend.application.port.QrCodeGeneratorPort
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/** [QrCodeGeneratorPort] implementation - the only place the ZXing dependency is used, kept out of the application module entirely. */
@Component
class ZxingQrCodeGenerator : QrCodeGeneratorPort {

    override fun generatePng(content: String, sizePx: Int): ByteArray {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val output = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", output)
        return output.toByteArray()
    }
}
