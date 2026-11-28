package app.limenet.desktop

import com.github.sarxos.webcam.Webcam
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.EnumMap

/** Reads a QR code from the default webcam; camera access stays local to the desktop. */
object QrScanner {
    suspend fun scan(): String = withContext(Dispatchers.IO) {
        val camera = Webcam.getDefault() ?: error("Камера не найдена")
        val reader = MultiFormatReader().apply {
            setHints(EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
                put(DecodeHintType.TRY_HARDER, true)
            })
        }
        try {
            if (!camera.open()) error("Не удалось открыть камеру")
            while (currentCoroutineContext().isActive) {
                val frame = camera.image
                if (frame != null) {
                    try {
                        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(frame)))
                        reader.decodeWithState(bitmap).text?.trim()?.takeIf(String::isNotEmpty)?.let { return@withContext it }
                    } catch (_: ReaderException) {
                        // Continue until a QR code is visible.
                    } finally {
                        reader.reset()
                    }
                }
                delay(180)
            }
            error("Сканирование остановлено")
        } finally {
            if (camera.isOpen) camera.close()
        }
    }
}
