package pro.xiangyu.cashierhelper.capture

interface ScreenshotSource {
    suspend fun captureJpeg(): Result<ByteArray>
}

class ScreenshotException(
    val errorCode: Int,
) : Exception("Screenshot failed with error code $errorCode")

