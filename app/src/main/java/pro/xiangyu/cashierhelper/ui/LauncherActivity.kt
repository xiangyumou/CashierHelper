package pro.xiangyu.cashierhelper.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import pro.xiangyu.cashierhelper.capture.AccessibilityStatus
import pro.xiangyu.cashierhelper.capture.CaptureOutcome
import pro.xiangyu.cashierhelper.capture.CashierAccessibilityService
import pro.xiangyu.cashierhelper.config.SecureConfigStore
import pro.xiangyu.cashierhelper.feedback.FeedbackNotifier

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressOpenTransition()

        val configured = SecureConfigStore(this).loadConfig() != null
        val accessibilityEnabled = AccessibilityStatus.isEnabled(this)
        if (!configured || !accessibilityEnabled) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finishWithoutAnimation()
            return
        }

        if (!CashierAccessibilityService.triggerCapture()) {
            FeedbackNotifier(this).report(CaptureOutcome.ServiceUnavailable)
        }
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
}
