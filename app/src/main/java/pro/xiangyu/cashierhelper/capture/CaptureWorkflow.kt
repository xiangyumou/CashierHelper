package pro.xiangyu.cashierhelper.capture

import kotlinx.coroutines.CancellationException
import pro.xiangyu.cashierhelper.storage.FailedImageStore

sealed interface CaptureAttempt {
    class Captured(val jpegBytes: ByteArray) : CaptureAttempt
    data class Failed(val errorCode: Int?) : CaptureAttempt
}

/**
 * Captures a screenshot with a clear exception boundary.
 *
 * Recoverable capture and storage errors become typed results instead of
 * escaping, coroutine cancellation is never swallowed, and system-level errors
 * such as [OutOfMemoryError] are allowed to propagate rather than being masked
 * as a normal failure.
 */
class CaptureWorkflow(
    private val failedImageStore: FailedImageStore,
    private val onScreenshotCaptured: () -> Unit = {},
) {
    suspend fun capture(source: ScreenshotSource): CaptureAttempt {
        val result = try {
            source.captureJpeg()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return CaptureAttempt.Failed((error as? ScreenshotException)?.errorCode)
        }

        val jpegBytes = result.getOrElse { error ->
            if (error is CancellationException) throw error
            return CaptureAttempt.Failed((error as? ScreenshotException)?.errorCode)
        }
        runCatching { onScreenshotCaptured() }
        return CaptureAttempt.Captured(jpegBytes)
    }

    /** Attempts to preserve a screenshot after a failed upload. */
    suspend fun saveFailedImage(jpegBytes: ByteArray): String? = try {
        failedImageStore.save(jpegBytes)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}
