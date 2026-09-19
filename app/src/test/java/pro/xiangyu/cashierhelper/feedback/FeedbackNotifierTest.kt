package pro.xiangyu.cashierhelper.feedback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.capture.CaptureOutcome
import pro.xiangyu.cashierhelper.network.ConsumptionItem
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.network.UploadFailure

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class FeedbackNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun notifier(): FeedbackNotifier {
        FeedbackNotifier.createNotificationChannel(context)
        manager.cancelAll()
        return FeedbackNotifier(context)
    }

    private fun latestMessage(): String {
        val notifications = shadowOf(manager).allNotifications
        assertTrue("expected a posted notification", notifications.isNotEmpty())
        val notification = notifications.last()
        return notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    }

    @Test
    fun `invalid response never echoes the raw server body`() {
        notifier().reportAnalysis(
            AnalysisPollResult.QueryFailed(StatusQueryFailure.INVALID_RESPONSE),
            "task-1",
        )

        assertEquals("服务器响应格式不兼容", latestMessage())
    }

    @Test
    fun `invalid document uses a fixed message even with a server message`() {
        notifier().reportAnalysis(
            AnalysisPollResult.Finished(
                SourceDocumentStatus.Invalid("VALIDATION_FAILED", "这是一张退款截图"),
            ),
            "task-1",
        )

        assertEquals("该截图无法用于记账", latestMessage())
    }

    @Test
    fun `timed out query explains it can be continued`() {
        notifier().reportAnalysis(AnalysisPollResult.TimedOut, "task-1")

        assertEquals("一分钟内未取得结果，可在设置中继续查询", latestMessage())
    }

    @Test
    fun `completed analysis summarises without raw response`() {
        notifier().reportAnalysis(
            AnalysisPollResult.Finished(
                SourceDocumentStatus.Completed(
                    listOf(ConsumptionItem("10.20".toBigDecimal(), "CNY", "餐饮")),
                ),
            ),
            "task-1",
        )

        assertTrue(latestMessage().contains("餐饮"))
        assertTrue(latestMessage().contains("10.20"))
    }

    @Test
    fun `task notifications use the task id as tag`() {
        notifier().reportAnalysis(AnalysisPollResult.TimedOut, "task-42")

        val tagged = shadowOf(manager).getNotification("task-42", ANY_ID)
        assertTrue("notification must be tagged with the task id", tagged != null)
    }

    @Test
    fun `upload failures explain the reason and where the image went`() {
        notifier().report(
            CaptureOutcome.UploadFailed(
                UploadFailure.NETWORK_ERROR,
                "Pictures/CashierHelper/image.jpg",
            ),
        )

        val message = latestMessage()
        assertTrue(message.contains("网络连接失败或请求超时"))
        assertTrue(message.contains("Pictures/CashierHelper/image.jpg"))
    }

    @Test
    fun `invalid configuration is reported without credentials`() {
        notifier().report(CaptureOutcome.UploadFailed(UploadFailure.INVALID_CONFIGURATION, null))

        val message = latestMessage()
        assertEquals("连接配置无效，请重新保存；截图未能保存到相册", message)
        assertFalse(message.contains("Bearer"))
    }

    private companion object {
        /**
         * The notifier keeps a single fixed notification id; Robolectric needs
         * the exact value only to look the notification up by tag.
         */
        const val ANY_ID = 1
    }
}
