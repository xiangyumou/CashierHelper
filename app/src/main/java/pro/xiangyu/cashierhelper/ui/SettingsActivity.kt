package pro.xiangyu.cashierhelper.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.xiangyu.cashierhelper.CashierHelperApplication
import pro.xiangyu.cashierhelper.capture.AccessibilityStatus
import pro.xiangyu.cashierhelper.capture.CashierAccessibilityService
import pro.xiangyu.cashierhelper.config.SecureConfigStore
import pro.xiangyu.cashierhelper.feedback.FeedbackNotifier
import pro.xiangyu.cashierhelper.feedback.NotificationAvailability
import pro.xiangyu.cashierhelper.tasks.PendingTask
import pro.xiangyu.cashierhelper.ui.theme.CashierHelperTheme

class SettingsActivity : ComponentActivity() {
    private val configStore by lazy { SecureConfigStore(this) }
    private val taskCoordinator by lazy { (application as CashierHelperApplication).taskCoordinator }

    private var accessibilityEnabled by mutableStateOf(false)
    private var serviceConnected by mutableStateOf(false)
    private var notificationStatus by mutableStateOf(
        NotificationAvailability.Status(
            permissionGranted = true,
            appEnabled = true,
            channelEnabled = true,
        ),
    )
    private var tasks by mutableStateOf<List<PendingTask>>(emptyList())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialDraft = configStore.loadDraft()
        setContent {
            CashierHelperTheme {
                SettingsScreen(
                    initialBaseUrl = initialDraft.baseUrl,
                    initialApiKey = initialDraft.apiKey,
                    accessibilityEnabled = accessibilityEnabled,
                    serviceConnected = serviceConnected,
                    notificationStatus = notificationStatus,
                    tasks = tasks,
                    onSave = { baseUrl, apiKey ->
                        configStore.save(baseUrl, apiKey).map { it.baseUrl }
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestNotifications = ::requestNotificationPermission,
                    onOpenAppNotificationSettings = ::openAppNotificationSettings,
                    onOpenChannelNotificationSettings = ::openChannelNotificationSettings,
                    onRetryOriginal = { id -> lifecycleScope.launch { taskCoordinator.retryOriginal(id) } },
                    onContinueQuery = { id -> lifecycleScope.launch { taskCoordinator.continueQuery(id) } },
                    onDeleteTask = { id -> lifecycleScope.launch { taskCoordinator.deleteTask(id) } },
                )
            }
        }
        lifecycleScope.launch {
            taskCoordinator.tasks.collect { tasks = it }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        accessibilityEnabled = AccessibilityStatus.isEnabled(this)
        serviceConnected = CashierAccessibilityService.isConnected()
        notificationStatus = NotificationAvailability.check(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    private fun openChannelNotificationSettings() {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, FeedbackNotifier.CHANNEL_ID)
        startActivity(intent)
    }
}
