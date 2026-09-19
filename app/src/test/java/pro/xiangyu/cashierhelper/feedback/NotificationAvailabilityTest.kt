package pro.xiangyu.cashierhelper.feedback

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The three ways a notification can be invisible are independent: the runtime
 * permission, the app-level switch and the channel importance. Each must be
 * detected on its own so the settings screen can offer the right shortcut.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationAvailabilityTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        application.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        FeedbackNotifier.createNotificationChannel(application)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `a granted permission with an open channel is visible`() {
        val status = NotificationAvailability.check(application)

        assertTrue(status.permissionGranted)
        assertTrue(status.appEnabled)
        assertTrue(status.channelEnabled)
        assertTrue(status.isVisible)
    }

    @Test
    fun `a missing runtime permission hides notifications`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val status = NotificationAvailability.check(application)

        assertFalse(status.permissionGranted)
        assertFalse(NotificationAvailability.hasRuntimePermission(application))
        assertFalse(status.isVisible)
    }

    @Test
    fun `the app level switch is detected on its own`() {
        shadowOf(manager).setNotificationsEnabled(false)

        val status = NotificationAvailability.check(application)

        assertTrue(status.permissionGranted)
        assertFalse(status.appEnabled)
        assertTrue(status.channelEnabled)
        assertFalse(status.isVisible)
    }

    @Test
    fun `a disabled channel is detected on its own`() {
        manager.createNotificationChannel(
            NotificationChannel(
                FeedbackNotifier.CHANNEL_ID,
                "capture results",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        val status = NotificationAvailability.check(application)

        assertTrue(status.permissionGranted)
        assertTrue(status.appEnabled)
        assertFalse(status.channelEnabled)
        assertFalse(status.isVisible)
    }
}
