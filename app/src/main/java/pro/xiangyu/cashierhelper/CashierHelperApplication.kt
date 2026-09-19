package pro.xiangyu.cashierhelper

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pro.xiangyu.cashierhelper.capture.CaptureWorkflow
import pro.xiangyu.cashierhelper.capture.CashierAccessibilityService
import pro.xiangyu.cashierhelper.config.AndroidKeystoreSecretCipher
import pro.xiangyu.cashierhelper.config.SecureConfigStore
import pro.xiangyu.cashierhelper.feedback.FeedbackNotifier
import pro.xiangyu.cashierhelper.network.CashierApiClient
import pro.xiangyu.cashierhelper.network.RequestCoordinator
import pro.xiangyu.cashierhelper.storage.MediaStoreFailedImageStore
import pro.xiangyu.cashierhelper.tasks.PendingTaskStore
import pro.xiangyu.cashierhelper.tasks.TaskCoordinator

class CashierHelperApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var taskCoordinator: TaskCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        FeedbackNotifier.createNotificationChannel(this)
        instance = this

        val notifier = FeedbackNotifier(this)
        val configStore = SecureConfigStore(this)
        val api = CashierApiClient()
        val failedImageStore = MediaStoreFailedImageStore(this)
        val store = PendingTaskStore(
            directory = File(noBackupFilesDir, PENDING_TASK_DIRECTORY),
            cipher = AndroidKeystoreSecretCipher(),
        )

        taskCoordinator = TaskCoordinator(
            scope = appScope,
            configStore = configStore,
            api = api,
            store = store,
            coordinator = RequestCoordinator(),
            notifier = notifier,
            captureWorkflow = CaptureWorkflow(
                failedImageStore = failedImageStore,
                onScreenshotCaptured = notifier::acknowledgeScreenshot,
            ),
            screenshotSource = { CashierAccessibilityService.screenshotSource() },
        )
        appScope.launch { taskCoordinator.resume() }
    }

    companion object {
        private const val PENDING_TASK_DIRECTORY = "pending_tasks"

        @Volatile
        lateinit var instance: CashierHelperApplication
            private set
    }
}
