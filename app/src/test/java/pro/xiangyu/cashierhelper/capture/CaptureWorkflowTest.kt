package pro.xiangyu.cashierhelper.capture

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.network.SourceDocumentUploader
import pro.xiangyu.cashierhelper.network.UploadFailure
import pro.xiangyu.cashierhelper.network.UploadResult
import pro.xiangyu.cashierhelper.storage.FailedImageStore

class CaptureWorkflowTest {
    private val config = AppConfig("https://cashier.example.com", "key")
    private val jpeg = byteArrayOf(1, 2, 3)

    @Test
    fun `successful upload does not save screenshot`() = runTest {
        val store = RecordingImageStore()
        var captureAcknowledgements = 0
        val workflow = CaptureWorkflow(
            screenshotSource = FakeScreenshotSource(Result.success(jpeg)),
            uploader = FakeUploader(UploadResult.Accepted("doc-1")),
            failedImageStore = store,
            onScreenshotCaptured = { captureAcknowledgements++ },
        )

        assertEquals(CaptureOutcome.UploadAccepted("doc-1"), workflow.execute(config))
        assertNull(store.savedBytes)
        assertEquals(1, captureAcknowledgements)
    }

    @Test
    fun `failed upload saves captured jpeg`() = runTest {
        val store = RecordingImageStore("Pictures/CashierHelper/image.jpg")
        val workflow = CaptureWorkflow(
            screenshotSource = FakeScreenshotSource(Result.success(jpeg)),
            uploader = FakeUploader(UploadResult.Failed(UploadFailure.NETWORK_ERROR)),
            failedImageStore = store,
        )

        assertEquals(
            CaptureOutcome.UploadFailed(
                UploadFailure.NETWORK_ERROR,
                "Pictures/CashierHelper/image.jpg",
            ),
            workflow.execute(config),
        )
        assertTrue(store.savedBytes!!.contentEquals(jpeg))
    }

    @Test
    fun `capture failure neither uploads nor saves`() = runTest {
        val uploader = RecordingUploader()
        val store = RecordingImageStore()
        val workflow = CaptureWorkflow(
            screenshotSource = FakeScreenshotSource(Result.failure(ScreenshotException(7))),
            uploader = uploader,
            failedImageStore = store,
        )

        assertEquals(CaptureOutcome.CaptureFailed(7), workflow.execute(config))
        assertFalse(uploader.called)
        assertNull(store.savedBytes)
    }

    @Test
    fun `failed image store does not interrupt failure result`() = runTest {
        val workflow = CaptureWorkflow(
            screenshotSource = FakeScreenshotSource(Result.success(jpeg)),
            uploader = FakeUploader(UploadResult.Failed(UploadFailure.SERVER_ERROR)),
            failedImageStore = ThrowingImageStore(),
        )

        assertEquals(
            CaptureOutcome.UploadFailed(UploadFailure.SERVER_ERROR, null),
            workflow.execute(config),
        )
    }

    private class FakeScreenshotSource(
        private val result: Result<ByteArray>,
    ) : ScreenshotSource {
        override suspend fun captureJpeg() = result
    }

    private class FakeUploader(
        private val result: UploadResult,
    ) : SourceDocumentUploader {
        override suspend fun upload(config: AppConfig, jpegBytes: ByteArray) = result
    }

    private class RecordingUploader : SourceDocumentUploader {
        var called = false
        override suspend fun upload(config: AppConfig, jpegBytes: ByteArray): UploadResult {
            called = true
            return UploadResult.Accepted("doc-1")
        }
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
