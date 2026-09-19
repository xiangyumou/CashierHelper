package pro.xiangyu.cashierhelper.capture

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import pro.xiangyu.cashierhelper.ui.LauncherActivity

/**
 * Provides the only platform capability the app needs: taking a screenshot.
 *
 * The service no longer owns networking or analysis; it just reports when it is
 * connected and exposes a [ScreenshotSource]. All job lifecycle work lives in
 * the application-level task coordinator so tasks survive a service restart.
 */
class CashierAccessibilityService : AccessibilityService() {
    private val accessibilityButtonCallback =
        object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                startActivity(
                    Intent(this@CashierAccessibilityService, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                accessibilityButtonCallback,
                Handler(Looper.getMainLooper()),
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        runCatching {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(
                accessibilityButtonCallback,
            )
        }
        if (activeService?.get() === this) activeService = null
    }

    private fun newScreenshotSource(): ScreenshotSource = AccessibilityScreenshotSource(this)

    companion object {
        @Volatile
        private var activeService: WeakReference<CashierAccessibilityService>? = null

        fun isConnected(): Boolean = activeService?.get() != null

        /** Returns a fresh source while the service is connected, else null. */
        fun screenshotSource(): ScreenshotSource? =
            activeService?.get()?.newScreenshotSource()
    }
}
