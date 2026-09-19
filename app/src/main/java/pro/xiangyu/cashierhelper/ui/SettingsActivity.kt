package pro.xiangyu.cashierhelper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import pro.xiangyu.cashierhelper.capture.AccessibilityStatus
import pro.xiangyu.cashierhelper.config.SecureConfigStore
import pro.xiangyu.cashierhelper.ui.theme.CashierHelperTheme

class SettingsActivity : ComponentActivity() {
    private val configStore by lazy { SecureConfigStore(this) }
    private var accessibilityEnabled by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionState()
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
                    notificationsEnabled = notificationsEnabled,
                    onSave = { baseUrl, apiKey ->
                        configStore.save(baseUrl, apiKey).map { it.baseUrl }
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestNotifications = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        accessibilityEnabled = AccessibilityStatus.isEnabled(this)
        notificationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

