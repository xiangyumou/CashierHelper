package pro.xiangyu.cashierhelper.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.xiangyu.cashierhelper.storage.FailedImageStore

class CaptureWorkflowTest {
    private val jpeg = byteArrayOf(1, 2, 3)

    @Test
    fun `successful capture returns bytes and acknowledges`() = runTest {
        var acknowledgements = 0
        val workflow = CaptureWorkflow(
            failedImageStore = RecordingImageStore(),
            onScreenshotCaptured = { acknowledgements++ },
        )

        val attempt = workflow.capture(FakeScreenshotSource(Result.success(jpeg)))

        assertTrue(attempt is CaptureAttempt.Captured)
        assertTrue((attempt as CaptureAttempt.Captured).jpegBytes.contentEquals(jpeg))
        assertEquals(1, acknowledgements)
    }

    @Test
    fun `capture failure is reported as typed result`() = runTest {
        val workflow = CaptureWorkflow(failedImageStore = RecordingImageStore())

        val attempt = workflow.capture(FakeScreenshotSource(Result.failure(ScreenshotException(7))))

        assertEquals(CaptureAttempt.Failed(7), attempt)
    }

    @Test
    fun `capture failure without error code is still typed`() = runTest {
        val workflow = CaptureWorkflow(failedImageStore = RecordingImageStore())

        val attempt = workflow.capture(FakeScreenshotSource(Result.failure(IllegalStateException("x"))))

        assertEquals(CaptureAttempt.Failed(null), attempt)
    }

    @Test
    fun `thrown capture exception does not escape`() = runTest {
        val workflow = CaptureWorkflow(failedImageStore = RecordingImageStore())

        val attempt = workflow.capture(ThrowingScreenshotSource())

        assertEquals(CaptureAttempt.Failed(3), attempt)
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        val workflow = CaptureWorkflow(failedImageStore = RecordingImageStore())

        var thrown = false
        try {
            workflow.capture(CancellingScreenshotSource())
        } catch (_: CancellationException) {
            thrown = true
        }

        assertTrue(thrown)
    }

    @Test
    fun `failed image store errors do not escape`() = runTest {
        val workflow = CaptureWorkflow(failedImageStore = ThrowingImageStore())

        assertNull(workflow.saveFailedImage(jpeg))
    }

    @Test
    fun `failed image is saved when the store works`() = runTest {
        val workflow = CaptureWorkflow(failedImageStore = RecordingImageStore("Pictures/CashierHelper/x.jpg"))

        assertEquals("Pictures/CashierHelper/x.jpg", workflow.saveFailedImage(jpeg))
    }

    private class FakeScreenshotSource(
        private val result: Result<ByteArray>,
    ) : ScreenshotSource {
        override suspend fun captureJpeg() = result
    }

    private class ThrowingScreenshotSource : ScreenshotSource {
        override suspend fun captureJpeg(): Result<ByteArray> = throw ScreenshotException(3)
    }

    private class CancellingScreenshotSource : ScreenshotSource {
        override suspend fun captureJpeg(): Result<ByteArray> = throw CancellationException("cancelled")
    }

    private class RecordingImageStore(
        private val location: String? = null,
    ) : FailedImageStore {
        var savedBytes: ByteArray? = null
        override suspend fun save(jpegBytes: ByteArray): String? {
            savedBytes = jpegBytes
            return location
        }
    }

    private class ThrowingImageStore : FailedImageStore {
        override suspend fun save(jpegBytes: ByteArray): String? = error("store unavailable")
    }
}
