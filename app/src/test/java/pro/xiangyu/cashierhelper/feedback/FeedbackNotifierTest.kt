package pro.xiangyu.cashierhelper.feedback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.network.StatusQueryFailure

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class FeedbackNotifierTest {
    @Test
    fun `invalid response notification contains complete response body`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val body = """{"unexpected":{"nested":true}}"""
        val notificationId = 2001
        FeedbackNotifier.createNotificationChannel(context)

        FeedbackNotifier(context).reportAnalysis(
            AnalysisPollResult.QueryFailed(StatusQueryFailure.INVALID_RESPONSE, body),
            notificationId,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(notificationId)
        assertEquals(
            body,
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
    }
}
