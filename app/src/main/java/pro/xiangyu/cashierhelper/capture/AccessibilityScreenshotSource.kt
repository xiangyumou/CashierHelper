package pro.xiangyu.cashierhelper.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AccessibilityScreenshotSource(
    private val service: AccessibilityService,
    private val processor: ScreenshotProcessor = ScreenshotProcessor(),
) : ScreenshotSource {
    override suspend fun captureJpeg(): Result<ByteArray> {
        val bitmap = captureBitmap().getOrElse { return Result.failure(it) }
        return try {
            withContext(Dispatchers.Default) {
                runCatching { processor.encode(bitmap) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun captureBitmap(): Result<Bitmap> =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val result = copyToSoftwareBitmap(screenshot)
                    if (continuation.isActive) {
                        continuation.resume(result)
                    } else {
                        result.getOrNull()?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(ScreenshotException(errorCode)))
                    }
                }
            }

            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
        }

    private fun copyToSoftwareBitmap(
        screenshot: AccessibilityService.ScreenshotResult,
    ): Result<Bitmap> = runCatching {
        val hardwareBuffer = screenshot.hardwareBuffer
        try {
            val wrapped = checkNotNull(
                Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace),
            ) { "Unable to read screenshot buffer" }
            try {
                checkNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false)) {
                    "Unable to copy screenshot buffer"
                }
            } finally {
                wrapped.recycle()
            }
        } finally {
            hardwareBuffer.close()
        }
    }
}
