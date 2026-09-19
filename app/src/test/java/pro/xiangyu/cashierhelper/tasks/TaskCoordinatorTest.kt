package pro.xiangyu.cashierhelper.tasks

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.capture.CaptureOutcome
import pro.xiangyu.cashierhelper.capture.CaptureTriggerResult
import pro.xiangyu.cashierhelper.capture.CaptureWorkflow
import pro.xiangyu.cashierhelper.capture.ScreenshotException
import pro.xiangyu.cashierhelper.capture.ScreenshotSource
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.config.BinarySecretCipher
import pro.xiangyu.cashierhelper.config.ConfigDraft
import pro.xiangyu.cashierhelper.config.ConfigFingerprint
import pro.xiangyu.cashierhelper.config.ConfigStore
import pro.xiangyu.cashierhelper.config.EncryptedBytes
import pro.xiangyu.cashierhelper.feedback.TaskFeedback
import pro.xiangyu.cashierhelper.network.CashierApi
import pro.xiangyu.cashierhelper.network.RequestCoordinator
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.StatusQueryResult
import pro.xiangyu.cashierhelper.network.UploadFailure
import pro.xiangyu.cashierhelper.network.UploadResult
import pro.xiangyu.cashierhelper.storage.FailedImageStore

/**
 * Behavioural tests for the persisted screenshot task state machine: what is
 * sent, what is kept, and what is refused. The tests drive the coordinator
 * through the same entry points the UI uses and assert on the durable task
 * store rather than on internal state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepted upload stores the server id before dropping the image`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Accepted(DOC_ID)
        fixture.api.queryResults += StatusQueryResult.Status(completed())

        assertEquals(CaptureTriggerResult.Started, fixture.coordinator.requestCapture())
        advanceUntilIdle()

        val task = fixture.tasks().single()
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(DOC_ID, task.sourceDocumentId)
        assertNull(fixture.store.readImage(task.id))
        assertEquals(listOf(DOC_ID), fixture.api.queriedIds)
        assertEquals(1, fixture.api.uploads.size)
        assertEquals(3, fixture.api.uploads.single().bytes.size)
    }

    @Test
    fun `an unconfirmed upload keeps the image and is never auto-resubmitted`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.NETWORK_ERROR)

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        val task = fixture.tasks().single()
        assertEquals(TaskStatus.SUBMIT_UNCONFIRMED, task.status)
        assertNotNull(fixture.store.readImage(task.id))
        assertEquals(1, fixture.api.uploads.size)

        // A second trigger must not silently send another POST.
        assertEquals(CaptureTriggerResult.Busy, fixture.coordinator.requestCapture())
        advanceUntilIdle()

        assertEquals(1, fixture.api.uploads.size)
        assertEquals(1, fixture.feedback.pendingConfirmationCount)
    }

    @Test
    fun `a rejected upload stays unconfirmed for manual retry`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.REJECTED)

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        val task = fixture.tasks().single()
        assertEquals(TaskStatus.SUBMIT_UNCONFIRMED, task.status)
        assertEquals(UploadFailure.REJECTED.name, task.errorCode)
        assertNotNull(fixture.store.readImage(task.id))
    }

    @Test
    fun `retry reuses the original key and image`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()
        val task = fixture.tasks().single()
        val originalKey = task.idempotencyKey
        val originalBytes = fixture.store.readImage(task.id)

        fixture.api.uploadResults += UploadResult.Accepted(DOC_ID)
        fixture.api.queryResults += StatusQueryResult.Status(completed())
        assertTrue(fixture.coordinator.retryOriginal(task.id))
        advanceUntilIdle()

        assertEquals(2, fixture.api.uploads.size)
        assertEquals(originalKey, fixture.api.uploads[1].idempotencyKey)
        assertArrayEquals(originalBytes, fixture.api.uploads[1].bytes)
        assertEquals(TaskStatus.COMPLETED, fixture.tasks().single().status)
    }

    @Test
    fun `retry after the idempotency window asks for manual reconciliation`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()
        val task = fixture.tasks().single()

        fixture.nowMillis += 24 * 60 * 60 * 1000L

        assertFalse(fixture.coordinator.retryOriginal(task.id))
        advanceUntilIdle()

        assertEquals(1, fixture.api.uploads.size)
        assertTrue(fixture.feedback.taskMessages.any { it.contains("Cashier") })
    }

    @Test
    fun `a changed connection pauses retry instead of replaying`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()
        val task = fixture.tasks().single()

        fixture.configStore.config = fixture.otherConfig

        assertFalse(fixture.coordinator.retryOriginal(task.id))
        advanceUntilIdle()

        assertEquals(1, fixture.api.uploads.size)
        assertEquals(TaskStatus.CONFIG_PAUSED, fixture.tasks().single().status)
    }

    @Test
    fun `resume restarts the query for an accepted task`() = runTest {
        val fixture = newFixture()
        fixture.store.saveNew(
            fixture.pendingTask("t1", status = TaskStatus.ACCEPTED, sourceDocumentId = DOC_ID),
            fixture.screenshotBytes(),
        )
        fixture.api.queryResults += StatusQueryResult.Status(completed())

        fixture.coordinator.resume()
        advanceUntilIdle()

        assertEquals(listOf(DOC_ID), fixture.api.queriedIds)
        assertEquals(TaskStatus.COMPLETED, fixture.tasks().single().status)
        assertTrue(fixture.api.uploads.isEmpty())
    }

    @Test
    fun `resume never resubmits an unconfirmed task`() = runTest {
        val fixture = newFixture()
        fixture.store.saveNew(
            fixture.pendingTask("t1", status = TaskStatus.SUBMIT_UNCONFIRMED),
            fixture.screenshotBytes(),
        )

        fixture.coordinator.resume()
        advanceUntilIdle()

        assertTrue(fixture.api.uploads.isEmpty())
        assertTrue(fixture.api.queriedIds.isEmpty())
        assertEquals(TaskStatus.SUBMIT_UNCONFIRMED, fixture.tasks().single().status)
    }

    @Test
    fun `resume pauses a task whose connection changed`() = runTest {
        val fixture = newFixture()
        fixture.store.saveNew(
            fixture.pendingTask(
                "t1",
                status = TaskStatus.ACCEPTED,
                sourceDocumentId = DOC_ID,
                fingerprintOverride = "0000000000000000",
            ),
            fixture.screenshotBytes(),
        )

        fixture.coordinator.resume()
        advanceUntilIdle()

        assertTrue(fixture.api.queriedIds.isEmpty())
        assertEquals(TaskStatus.CONFIG_PAUSED, fixture.tasks().single().status)
    }

    @Test
    fun `a timed out query pauses the task and continue does not resubmit`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Accepted(DOC_ID)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        val paused = fixture.tasks().single()
        assertEquals(TaskStatus.QUERY_PAUSED, paused.status)
        assertTrue(fixture.feedback.analyses.any { it is AnalysisPollResult.TimedOut })

        fixture.api.queryResults += StatusQueryResult.Status(completed())
        assertTrue(fixture.coordinator.continueQuery(paused.id))
        advanceUntilIdle()

        assertEquals(TaskStatus.COMPLETED, fixture.tasks().single().status)
        assertEquals(1, fixture.api.uploads.size)
    }

    @Test
    fun `invalid status keeps the sanitized server message`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Accepted(DOC_ID)
        fixture.api.queryResults += StatusQueryResult.Status(
            SourceDocumentStatus.Invalid("VALIDATION_FAILED", "这是\u0000一张 退款截图"),
        )

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        val task = fixture.tasks().single()
        assertEquals(TaskStatus.INVALID, task.status)
        assertEquals("VALIDATION_FAILED", task.errorCode)
        assertEquals("这是 一张 退款截图", task.errorMessage)
    }

    @Test
    fun `an oversized screenshot is refused before upload`() = runTest {
        val fixture = newFixture()
        fixture.screenshotSource.result =
            Result.success(ByteArray(TaskCoordinator.MAX_UPLOAD_BYTES + 1))

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        assertTrue(fixture.api.uploads.isEmpty())
        assertTrue(fixture.tasks().isEmpty())
        assertEquals(1, fixture.feedback.imageTooLarge.size)
        assertEquals(1, fixture.failedImages.saved.size)
    }

    @Test
    fun `capacity reached refuses a new task before uploading`() = runTest {
        val fixture = newFixture(capacity = 1)
        fixture.store.saveNew(fixture.pendingTask("existing"), fixture.screenshotBytes())

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        assertEquals(1, fixture.feedback.capacityReachedCount)
        assertTrue(fixture.api.uploads.isEmpty())
        assertEquals(1, fixture.tasks().size)
    }

    @Test
    fun `a failed save is reported and never uploaded`() = runTest {
        val fixture = newFixture(cipher = ThrowingCipher)

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        assertTrue(fixture.api.uploads.isEmpty())
        assertTrue(fixture.feedback.taskMessages.any { it.contains("任务保存失败") })
        assertEquals(1, fixture.failedImages.saved.size)
    }

    @Test
    fun `a failed screenshot is reported and nothing is uploaded`() = runTest {
        val fixture = newFixture()
        fixture.screenshotSource.result = Result.failure(ScreenshotException(-2))

        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        assertTrue(fixture.api.uploads.isEmpty())
        assertEquals(listOf<CaptureOutcome>(CaptureOutcome.CaptureFailed(-2)), fixture.feedback.outcomes)
    }

    @Test
    fun `capture is refused when the screenshot service is not connected`() = runTest {
        val fixture = newFixture()
        fixture.serviceAvailable = false

        assertEquals(CaptureTriggerResult.ServiceUnavailable, fixture.coordinator.requestCapture())
        advanceUntilIdle()

        assertEquals(0, fixture.screenshotSource.captureCount)
        assertTrue(fixture.api.uploads.isEmpty())
        assertEquals(
            listOf<CaptureOutcome>(CaptureOutcome.ServiceUnavailable),
            fixture.feedback.outcomes,
        )
    }

    @Test
    fun `a missing or unusable configuration is reported before capturing`() = runTest {
        val fixture = newFixture()
        fixture.configStore.config = null

        assertEquals(CaptureTriggerResult.MissingConfiguration, fixture.coordinator.requestCapture())
        assertEquals(0, fixture.screenshotSource.captureCount)
        assertEquals(
            listOf<CaptureOutcome>(CaptureOutcome.MissingConfiguration),
            fixture.feedback.outcomes,
        )

        fixture.configStore.draft = ConfigDraft("https://cashier.example.com", "key")
        assertEquals(CaptureTriggerResult.MissingConfiguration, fixture.coordinator.requestCapture())
        assertEquals(1, fixture.feedback.invalidConfigurationCount)
    }

    @Test
    fun `deleting a task removes only the local record`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()
        val task = fixture.tasks().single()

        fixture.coordinator.deleteTask(task.id)

        assertTrue(fixture.tasks().isEmpty())
        assertNull(fixture.store.readImage(task.id))
    }

    @Test
    fun `a query cannot be continued without a server id`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults += UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()

        assertFalse(fixture.coordinator.continueQuery(fixture.tasks().single().id))
    }

    @Test
    fun `a rate limited upload applies the shared cooldown to the retry`() = runTest {
        val fixture = newFixture()
        fixture.api.uploadResults +=
            UploadResult.Failed(UploadFailure.RATE_LIMITED, retryAfterMillis = 30_000L)
        fixture.coordinator.requestCapture()
        advanceUntilIdle()
        val task = fixture.tasks().single()
        val refusedAt = testScheduler.currentTime

        fixture.api.uploadResults += UploadResult.Accepted(DOC_ID)
        fixture.api.queryResults += StatusQueryResult.Status(completed())
        assertTrue(fixture.coordinator.retryOriginal(task.id))
        advanceUntilIdle()

        assertTrue(testScheduler.currentTime - refusedAt >= 30_000L)
        assertEquals(2, fixture.api.uploads.size)
        assertEquals(TaskStatus.COMPLETED, fixture.tasks().single().status)
    }

    private fun TestScope.newFixture(
        capacity: Int = PendingTaskStore.DEFAULT_CAPACITY,
        cipher: BinarySecretCipher = FakeCipher,
    ) = Fixture(this, temporaryFolder.newFolder(), capacity, cipher)

    private class Fixture(
        scope: TestScope,
        root: File,
        capacity: Int,
        cipher: BinarySecretCipher,
    ) {
        val appConfig = AppConfig(baseUrl = "https://cashier.example.com", apiKey = "secret-key")
        val otherConfig = AppConfig(baseUrl = "https://other.example.com", apiKey = "secret-key")
        private val fingerprint = ConfigFingerprint.of(appConfig)
        val api = FakeApi()
        val feedback = RecordingFeedback()
        val failedImages = RecordingImageStore()
        val configStore = FakeConfigStore().apply { config = appConfig }
        val screenshotSource = FakeScreenshotSource()
        var serviceAvailable = true
        var nowMillis = BASE_TIME

        val store = PendingTaskStore(
            directory = root,
            cipher = cipher,
            capacity = capacity,
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
        )

        // A detached scope keeps the coordinator's jobs out of the test
        // coroutine while still running on the shared virtual-time scheduler.
        val taskScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + Job())

        val coordinator = TaskCoordinator(
            scope = taskScope,
            configStore = configStore,
            api = api,
            store = store,
            coordinator = RequestCoordinator(clock = { scope.testScheduler.currentTime }),
            notifier = feedback,
            captureWorkflow = CaptureWorkflow(failedImageStore = failedImages),
            screenshotSource = { if (serviceAvailable) screenshotSource else null },
            now = { nowMillis },
        )

        fun screenshotBytes(size: Int = 3) = ByteArray(size) { it.toByte() }

        fun pendingTask(
            id: String,
            status: TaskStatus = TaskStatus.PENDING_UPLOAD,
            sourceDocumentId: String? = null,
            fingerprintOverride: String? = null,
            hasImage: Boolean = true,
        ) = PendingTask(
            id = id,
            idempotencyKey = "key-" + id,
            configFingerprint = fingerprintOverride ?: fingerprint,
            baseUrl = appConfig.baseUrl,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            status = status,
            sourceDocumentId = sourceDocumentId,
            imageFileName = if (hasImage) id + PendingTaskStore.IMAGE_SUFFIX else null,
        )

        suspend fun tasks(): List<PendingTask> = store.loadAndClean(nowMillis)
    }

    private class FakeApi : CashierApi {
        val uploads = mutableListOf<UploadCall>()
        val uploadResults = ArrayDeque<UploadResult>()
        val queriedIds = mutableListOf<String>()
        val queryResults = ArrayDeque<StatusQueryResult>()

        override suspend fun upload(
            config: AppConfig,
            jpegBytes: ByteArray,
            idempotencyKey: String,
        ): UploadResult {
            uploads += UploadCall(idempotencyKey, jpegBytes)
            return uploadResults.removeFirstOrNull()
                ?: UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        }

        override suspend fun query(
            config: AppConfig,
            sourceDocumentId: String,
        ): StatusQueryResult {
            queriedIds += sourceDocumentId
            return queryResults.removeFirstOrNull()
                ?: StatusQueryResult.Status(SourceDocumentStatus.Processing)
        }
    }

    private data class UploadCall(val idempotencyKey: String, val bytes: ByteArray)

    private class FakeConfigStore : ConfigStore {
        var config: AppConfig? = null
        var draft: ConfigDraft = ConfigDraft()

        override fun loadDraft(): ConfigDraft = draft

        override fun loadConfig(): AppConfig? = config

        override fun save(baseUrl: String, apiKey: String): Result<AppConfig> =
            Result.success(AppConfig(baseUrl, apiKey))
    }

    private class FakeScreenshotSource : ScreenshotSource {
        var result: Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3))
        var captureCount = 0
            private set

        override suspend fun captureJpeg(): Result<ByteArray> {
            captureCount++
            return result
        }
    }

    private class RecordingImageStore : FailedImageStore {
        val saved = mutableListOf<ByteArray>()
        var location: String? = "Pictures/CashierHelper"

        override suspend fun save(jpegBytes: ByteArray): String? {
            saved += jpegBytes
            return location
        }
    }

    private class RecordingFeedback : TaskFeedback {
        val outcomes = mutableListOf<CaptureOutcome>()
        val analyses = mutableListOf<AnalysisPollResult>()
        val imageTooLarge = mutableListOf<String?>()
        val taskMessages = mutableListOf<String>()
        var pendingConfirmationCount = 0
        var invalidConfigurationCount = 0
        var capacityReachedCount = 0

        override fun acknowledgeScreenshot() = Unit

        override fun report(outcome: CaptureOutcome) {
            outcomes += outcome
        }

        override fun reportPendingConfirmation() {
            pendingConfirmationCount++
        }

        override fun reportInvalidConfiguration() {
            invalidConfigurationCount++
        }

        override fun reportCapacityReached() {
            capacityReachedCount++
        }

        override fun reportImageTooLarge(savedLocation: String?) {
            imageTooLarge += savedLocation
        }

        override fun reportTask(text: String, success: Boolean) {
            taskMessages += text
        }

        override fun reportAnalysis(result: AnalysisPollResult, taskTag: String) {
            analyses += result
        }
    }

    private object FakeCipher : BinarySecretCipher {
        override fun encryptBytes(value: ByteArray) = EncryptedBytes(value, ByteArray(0))
        override fun decryptBytes(secret: EncryptedBytes) = secret.ciphertext
    }

    private object ThrowingCipher : BinarySecretCipher {
        override fun encryptBytes(value: ByteArray): EncryptedBytes = error("keystore unavailable")
        override fun decryptBytes(secret: EncryptedBytes): ByteArray = error("keystore unavailable")
    }

    private companion object {
        const val DOC_ID = "doc-1"
        const val BASE_TIME = 1_700_000_000_000L

        fun completed() = SourceDocumentStatus.Completed(emptyList())
    }
}
