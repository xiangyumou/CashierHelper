package pro.xiangyu.cashierhelper.tasks

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.xiangyu.cashierhelper.analysis.AnalysisPoller
import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.analysis.ConsumptionSummaryFormatter
import pro.xiangyu.cashierhelper.capture.CaptureAttempt
import pro.xiangyu.cashierhelper.capture.CaptureOutcome
import pro.xiangyu.cashierhelper.capture.CaptureTriggerResult
import pro.xiangyu.cashierhelper.capture.CaptureWorkflow
import pro.xiangyu.cashierhelper.capture.ScreenshotSource
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.config.ConfigFingerprint
import pro.xiangyu.cashierhelper.config.ConfigStore
import pro.xiangyu.cashierhelper.feedback.TaskFeedback
import pro.xiangyu.cashierhelper.network.CashierApi
import pro.xiangyu.cashierhelper.network.RequestCoordinator
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.UploadFailure
import pro.xiangyu.cashierhelper.network.UploadResult

/**
 * Application-level coordinator for screenshot jobs.
 *
 * It owns the lifecycle of a task independently of the accessibility service:
 * a screenshot is persisted (metadata plus encrypted JPEG) before the network
 * call is made, an accepted submission stores its server id before the private
 * image is dropped, and anything uncertain is retained for explicit user
 * action instead of being silently retried with a new idempotency key.
 */
class TaskCoordinator(
    private val scope: CoroutineScope,
    private val configStore: ConfigStore,
    private val api: CashierApi,
    private val store: PendingTaskStore,
    private val coordinator: RequestCoordinator,
    private val notifier: TaskFeedback,
    private val captureWorkflow: CaptureWorkflow,
    private val screenshotSource: () -> ScreenshotSource?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stateLock = Mutex()
    private val captureLock = Mutex()
    private val submitting = mutableSetOf<String>()
    private val jobs = mutableMapOf<String, Job>()
    private val _tasks = MutableStateFlow<List<PendingTask>>(emptyList())

    val tasks: StateFlow<List<PendingTask>> = _tasks.asStateFlow()

    /**
     * Unified trigger entry point. It checks configuration, service state and
     * any pending confirmation before a screenshot is even taken, and it is
     * the only place that emits immediate feedback for those conditions.
     */
    suspend fun requestCapture(): CaptureTriggerResult {
        if (screenshotSource() == null) {
            notifier.report(CaptureOutcome.ServiceUnavailable)
            return CaptureTriggerResult.ServiceUnavailable
        }

        val config = configStore.loadConfig()
        if (config == null) {
            val draft = configStore.loadDraft()
            if (draft.baseUrl.isBlank() && draft.apiKey.isBlank()) {
                notifier.report(CaptureOutcome.MissingConfiguration)
            } else {
                notifier.reportInvalidConfiguration()
            }
            return CaptureTriggerResult.MissingConfiguration
        }

        if (_tasks.value.any { it.status.isUnconfirmed }) {
            notifier.reportPendingConfirmation()
            return CaptureTriggerResult.Busy
        }

        if (!captureLock.tryLock()) {
            notifier.report(CaptureOutcome.Busy)
            return CaptureTriggerResult.Busy
        }
        scope.launch {
            try {
                runCapture(config)
            } finally {
                captureLock.unlock()
            }
        }
        return CaptureTriggerResult.Started
    }

    /**
     * Restores persisted work on the next app run. Only submissions with a
     * known server id are resumed; unconfirmed uploads wait for the user.
     */
    suspend fun resume() {
        val config = configStore.loadConfig()
        val loaded = store.loadAndClean(now())
        _tasks.value = loaded

        loaded.forEach { task ->
            if (task.status.isTerminal || task.status == TaskStatus.NEEDS_REVIEW) return@forEach
            val sourceDocumentId = task.sourceDocumentId ?: return@forEach
            val active = config
            if (active == null || ConfigFingerprint.of(active) != task.configFingerprint) {
                if (task.status != TaskStatus.CONFIG_PAUSED) {
                    store.update(task.copy(status = TaskStatus.CONFIG_PAUSED, updatedAtMillis = now()))
                }
                return@forEach
            }
            startQuery(task.copy(status = TaskStatus.ACCEPTED), active, sourceDocumentId)
        }
        reload()
    }

    /** Re-runs the original submission, reusing the original image and key. */
    suspend fun retryOriginal(id: String): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (!task.canRetryOriginal) return false

        if (now() - task.createdAtMillis > MAX_UPLOAD_AGE_MILLIS) {
            notifier.reportTask("请先在 Cashier 核对记录", false)
            return false
        }

        val config = configStore.loadConfig()
        if (config == null || ConfigFingerprint.of(config) != task.configFingerprint) {
            store.update(task.copy(status = TaskStatus.CONFIG_PAUSED, updatedAtMillis = now()))
            reload()
            notifier.reportTask("连接配置已变化，无法重试原任务", false)
            return false
        }

        val bytes = store.readImage(id)
        if (bytes == null) {
            store.update(task.copy(status = TaskStatus.NEEDS_REVIEW, updatedAtMillis = now()))
            reload()
            notifier.reportTask("原截图不可用，请人工核对", false)
            return false
        }

        val pending = task.copy(status = TaskStatus.PENDING_UPLOAD, updatedAtMillis = now())
        store.update(pending)
        reload()
        submit(pending, config, bytes)
        return true
    }

    /** Starts a fresh one-minute query window for an already-accepted task. */
    suspend fun continueQuery(id: String): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        val sourceDocumentId = task.sourceDocumentId ?: return false
        cancelJob(id)

        val config = configStore.loadConfig()
        if (config == null || ConfigFingerprint.of(config) != task.configFingerprint) {
            store.update(task.copy(status = TaskStatus.CONFIG_PAUSED, updatedAtMillis = now()))
            reload()
            notifier.reportTask("连接配置已变化，无法继续查询", false)
            return false
        }

        val resumed = task.copy(status = TaskStatus.ACCEPTED, updatedAtMillis = now())
        store.update(resumed)
        reload()
        startQuery(resumed, config, sourceDocumentId)
        return true
    }

    /** Removes only the local record; it never claims to undo a submission. */
    suspend fun deleteTask(id: String) {
        cancelJob(id)
        store.delete(id)
        reload()
    }

    private suspend fun runCapture(config: AppConfig) {
        val source = screenshotSource() ?: run {
            notifier.report(CaptureOutcome.ServiceUnavailable)
            return
        }

        val bytes = when (val attempt = captureWorkflow.capture(source)) {
            is CaptureAttempt.Captured -> attempt.jpegBytes
            is CaptureAttempt.Failed -> {
                notifier.report(CaptureOutcome.CaptureFailed(attempt.errorCode))
                return
            }
        }

        if (bytes.size > MAX_UPLOAD_BYTES) {
            val saved = captureWorkflow.saveFailedImage(bytes)
            notifier.reportImageTooLarge(saved)
            return
        }

        val id = UUID.randomUUID().toString()
        val task = PendingTask(
            id = id,
            idempotencyKey = UUID.randomUUID().toString(),
            configFingerprint = ConfigFingerprint.of(config),
            baseUrl = config.baseUrl,
            createdAtMillis = now(),
            updatedAtMillis = now(),
            status = TaskStatus.PENDING_UPLOAD,
            imageFileName = id + PendingTaskStore.IMAGE_SUFFIX,
        )

        when (store.saveNew(task, bytes)) {
            TaskSaveResult.Saved -> {
                reload()
                submit(task, config, bytes)
            }
            TaskSaveResult.CapacityReached -> notifier.reportCapacityReached()
            TaskSaveResult.Failed -> {
                val saved = captureWorkflow.saveFailedImage(bytes)
                notifier.reportTask(
                    "任务保存失败" + (saved?.let { "，截图已保存到 $it" } ?: ""),
                    false,
                )
            }
        }
    }

    private suspend fun submit(task: PendingTask, config: AppConfig, bytes: ByteArray) {
        val claimed = stateLock.withLock {
            if (submitting.contains(task.id)) false else {
                submitting.add(task.id)
                true
            }
        }
        if (!claimed) return

        try {
            val result = coordinator.runUpload(task.configFingerprint) {
                api.upload(config, bytes, task.idempotencyKey)
            }
            when (result) {
                is UploadResult.Accepted -> {
                    val accepted = task.copy(
                        sourceDocumentId = result.sourceDocumentId,
                        status = TaskStatus.ACCEPTED,
                        updatedAtMillis = now(),
                    )
                    // Persist the server id before dropping the private image so
                    // a crash in between never loses the ability to keep querying.
                    store.update(accepted)
                    store.deleteImage(accepted.id)
                    reload()
                    notifier.report(CaptureOutcome.UploadAccepted(result.sourceDocumentId))
                    startQuery(accepted, config, result.sourceDocumentId)
                }
                is UploadResult.Failed -> handleUploadFailure(task, bytes, result)
            }
        } finally {
            stateLock.withLock { submitting.remove(task.id) }
        }
    }

    private suspend fun handleUploadFailure(
        task: PendingTask,
        bytes: ByteArray,
        failure: UploadResult.Failed,
    ) {
        if (failure.reason == UploadFailure.RATE_LIMITED) {
            coordinator.onRateLimited(task.configFingerprint, failure.retryAfterMillis)
        }
        // The POST result is uncertain: keep the image and never auto-resend.
        store.update(
            task.copy(
                status = TaskStatus.SUBMIT_UNCONFIRMED,
                errorCode = failure.reason.name,
                updatedAtMillis = now(),
            ),
        )
        reload()
        val saved = captureWorkflow.saveFailedImage(bytes)
        notifier.report(CaptureOutcome.UploadFailed(failure.reason, saved))
    }

    private suspend fun startQuery(
        task: PendingTask,
        config: AppConfig,
        sourceDocumentId: String,
    ) {
        val alreadyRunning = stateLock.withLock { jobs[task.id]?.isActive == true }
        if (alreadyRunning) return
        val job = scope.launch {
            try {
                runQuery(task, config, sourceDocumentId)
            } finally {
                stateLock.withLock { jobs.remove(task.id) }
            }
        }
        stateLock.withLock { jobs[task.id] = job }
    }

    private suspend fun runQuery(
        task: PendingTask,
        config: AppConfig,
        sourceDocumentId: String,
    ) {
        val poller = AnalysisPoller(api, coordinator, task.configFingerprint)
        when (val result = poller.poll(config, sourceDocumentId)) {
            is AnalysisPollResult.Finished -> applyStatus(task, result.status)
            is AnalysisPollResult.QueryFailed -> {
                // The poller already refreshed the shared cooldown on a 429.
                store.update(task.copy(status = TaskStatus.QUERY_PAUSED, updatedAtMillis = now()))
                reload()
                notifier.reportAnalysis(result, task.id)
            }
            AnalysisPollResult.TimedOut -> {
                store.update(task.copy(status = TaskStatus.QUERY_PAUSED, updatedAtMillis = now()))
                reload()
                notifier.reportAnalysis(result, task.id)
            }
        }
    }

    private suspend fun applyStatus(task: PendingTask, status: SourceDocumentStatus) {
        val updated = when (status) {
            is SourceDocumentStatus.Completed -> task.copy(
                status = TaskStatus.COMPLETED,
                summary = ConsumptionSummaryFormatter.format(status.items),
                errorCode = null,
                errorMessage = null,
                updatedAtMillis = now(),
            )
            is SourceDocumentStatus.Invalid -> task.copy(
                status = TaskStatus.INVALID,
                errorCode = status.errorCode,
                errorMessage = sanitizeServerMessage(status.message),
                updatedAtMillis = now(),
            )
            is SourceDocumentStatus.Anomaly -> task.copy(
                status = TaskStatus.INVALID,
                errorCode = status.errorCode,
                updatedAtMillis = now(),
            )
            is SourceDocumentStatus.Failed -> task.copy(
                status = TaskStatus.FAILED,
                errorCode = status.errorCode,
                updatedAtMillis = now(),
            )
            SourceDocumentStatus.Cancelled ->
                task.copy(status = TaskStatus.CANCELLED, updatedAtMillis = now())
            SourceDocumentStatus.Processing ->
                task.copy(status = TaskStatus.ACCEPTED, updatedAtMillis = now())
        }
        store.update(updated)
        reload()
        notifier.reportAnalysis(AnalysisPollResult.Finished(status), task.id)
    }

    private suspend fun cancelJob(id: String) {
        val job = stateLock.withLock { jobs.remove(id) }
        job?.cancel()
    }

    private suspend fun reload() {
        _tasks.value = store.loadAndClean(now())
    }

    companion object {
        /** The backend keeps idempotency records for 24h; retry within 23h. */
        const val MAX_UPLOAD_AGE_MILLIS = 23L * 60 * 60 * 1000

        /** The backend rejects decoded images larger than 3 MiB. */
        const val MAX_UPLOAD_BYTES = 3 * 1024 * 1024

        /**
         * Server-provided messages are rendered in the UI, so control
         * characters are stripped and the text is bounded before storage.
         */
        fun sanitizeServerMessage(raw: String?): String? = raw
            ?.map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }
            ?.joinToString("")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_MESSAGE_LENGTH)
            ?.takeIf(String::isNotEmpty)

        private const val MAX_MESSAGE_LENGTH = 200
    }
}
