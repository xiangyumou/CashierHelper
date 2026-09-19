package pro.xiangyu.cashierhelper.capture

import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class ScreenshotProcessor(
    private val maxWidth: Int = DEFAULT_MAX_WIDTH,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    fun encode(source: Bitmap): ByteArray {
        require(source.width > 0 && source.height > 0) { "Screenshot dimensions must be positive" }
        val outputBitmap = if (source.width > maxWidth) {
            val scaledHeight = (source.height * (maxWidth.toDouble() / source.width)).roundToInt()
            source.scale(maxWidth, scaledHeight, true)
        } else {
            source
        }

        return try {
            ByteArrayOutputStream().use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                    "Unable to encode screenshot as JPEG"
                }
                output.toByteArray()
            }
        } finally {
            if (outputBitmap !== source) outputBitmap.recycle()
        }
    }

    companion object {
        const val DEFAULT_MAX_WIDTH = 1080
        const val DEFAULT_JPEG_QUALITY = 85
    }
}
