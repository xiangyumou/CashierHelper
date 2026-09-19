package pro.xiangyu.cashierhelper.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pro.xiangyu.cashierhelper.CashierHelperApplication
import pro.xiangyu.cashierhelper.capture.AccessibilityStatus
import pro.xiangyu.cashierhelper.capture.CaptureTriggerResult
import pro.xiangyu.cashierhelper.capture.CashierAccessibilityService
import pro.xiangyu.cashierhelper.config.SecureConfigStore

/**
 * Transparent trigger entry point.
 *
 * After a cold start the accessibility service may not be connected yet even
 * though the permission is enabled, so this waits briefly for it. The wait is
 * tied to the activity lifecycle: leaving the screen cancels it and no delayed
 * screenshot is ever scheduled.
 */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressOpenTransition()

        val configured = SecureConfigStore(this).loadConfig() != null
        val accessibilityEnabled = AccessibilityStatus.isEnabled(this)
        if (!configured || !accessibilityEnabled) {
            openSettings()
            return
        }

        lifecycleScope.launch {
            awaitServiceConnection()
            val coordinator = (application as CashierHelperApplication).taskCoordinator
            val result = coordinator.requestCapture()
            if (result == CaptureTriggerResult.MissingConfiguration) {
                openSettings()
                return@launch
            }
            finishWithoutAnimation()
        }
    }

    private suspend fun awaitServiceConnection() {
        if (CashierAccessibilityService.isConnected()) return
        withTimeoutOrNull(SERVICE_WAIT_MILLIS) {
            while (!CashierAccessibilityService.isConnected()) {
                delay(SERVICE_POLL_MILLIS)
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        suppressCloseTransition()
    }

    @Suppress("DEPRECATION")
    private fun suppressOpenTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun suppressCloseTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        const val SERVICE_WAIT_MILLIS = 2_000L
        const val SERVICE_POLL_MILLIS = 50L
    }
}
