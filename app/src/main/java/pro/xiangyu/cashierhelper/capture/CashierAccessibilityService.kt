package pro.xiangyu.cashierhelper.capture

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pro.xiangyu.cashierhelper.analysis.AnalysisPoller
import pro.xiangyu.cashierhelper.config.SecureConfigStore
import pro.xiangyu.cashierhelper.feedback.FeedbackNotifier
import pro.xiangyu.cashierhelper.network.CashierApiClient
import pro.xiangyu.cashierhelper.storage.MediaStoreFailedImageStore

class CashierAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val singleFlight = SingleFlightRunner()
    private lateinit var workflow: CaptureWorkflow
    private lateinit var configStore: SecureConfigStore
    private lateinit var notifier: FeedbackNotifier
    private lateinit var analysisPoller: AnalysisPoller

    private val accessibilityButtonCallback =
        object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                requestCapture()
            }
        }

    override fun onCreate() {
        super.onCreate()
        configStore = SecureConfigStore(this)
        notifier = FeedbackNotifier(this)
        val apiClient = CashierApiClient()
        analysisPoller = AnalysisPoller(apiClient)
        workflow = CaptureWorkflow(
            screenshotSource = AccessibilityScreenshotSource(this),
            uploader = apiClient,
            failedImageStore = MediaStoreFailedImageStore(this),
            onScreenshotCaptured = notifier::acknowledgeScreenshot,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        accessibilityButtonController.registerAccessibilityButtonCallback(
            accessibilityButtonCallback,
            Handler(Looper.getMainLooper()),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun detach() {
        runCatching {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback)
        }
        if (activeService?.get() === this) activeService = null
    }

    private fun requestCapture(): Boolean {
        val config = configStore.loadConfig()
        if (config == null) {
            notifier.report(CaptureOutcome.MissingConfiguration)
            return false
        }

        val started = singleFlight.tryLaunch(serviceScope) {
            val outcome = workflow.execute(config)
            notifier.report(outcome)
            if (outcome is CaptureOutcome.UploadAccepted) {
                val notificationId = notifier.nextAnalysisNotificationId()
                serviceScope.launch {
                    notifier.reportAnalysis(
                        analysisPoller.poll(config, outcome.sourceDocumentId),
                        notificationId,
                    )
                }
            }
        }
        if (!started) notifier.report(CaptureOutcome.Busy)
        return started
    }

    companion object {
        @Volatile
        private var activeService: WeakReference<CashierAccessibilityService>? = null

        fun triggerCapture(): Boolean = activeService?.get()?.requestCapture() ?: false
    }
}
