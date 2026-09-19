package pro.xiangyu.cashierhelper.feedback

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pro.xiangyu.cashierhelper.R
import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.analysis.ConsumptionSummaryFormatter
import pro.xiangyu.cashierhelper.capture.CaptureOutcome
import pro.xiangyu.cashierhelper.capture.ScreenshotErrorMapper
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.network.UploadFailure
import pro.xiangyu.cashierhelper.ui.SettingsActivity

/**
 * User-facing feedback. Notifications carry no raw server response, credential
 * or screenshot; when notifications are not visible the same message falls back
 * to a Toast so the caller still learns what happened.
 */
class FeedbackNotifier(
    context: Context,
) : TaskFeedback {
    private val appContext = context.applicationContext

    override fun acknowledgeScreenshot() {
        vibrate(longArrayOf(0, 35))
    }

    override fun report(outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.UploadAccepted -> show(
                title = "提交成功",
                message = "截图已提交处理",
                success = true,
                tag = SUBMISSION_TAG,
            )
            CaptureOutcome.Busy -> show(
                title = "正在处理",
                message = "上一张截图仍在提交",
                success = false,
                tag = SUBMISSION_TAG,
            )
            CaptureOutcome.MissingConfiguration -> show(
                title = "需要完成设置",
                message = "请先填写服务器地址和 API Key",
                success = false,
                tag = SUBMISSION_TAG,
            )
            CaptureOutcome.ServiceUnavailable -> show(
                title = "截图服务未连接",
                message = "请检查无障碍服务是否已开启",
                success = false,
                tag = SUBMISSION_TAG,
            )
            is CaptureOutcome.CaptureFailed -> show(
                title = "截图失败",
                message = ScreenshotErrorMapper.message(outcome.errorCode),
                success = false,
                tag = SUBMISSION_TAG,
            )
            is CaptureOutcome.UploadFailed -> {
                val savedMessage = if (outcome.savedLocation != null) {
                    "截图已保存到 " + outcome.savedLocation
                } else {
                    "截图未能保存到相册"
                }
                show(
                    title = "提交失败",
                    message = uploadFailureMessage(outcome.reason) + "；" + savedMessage,
                    success = false,
                    tag = SUBMISSION_TAG,
                )
            }
        }
    }

    /** Immediate feedback for a new trigger that cannot start right now. */
    override fun reportPendingConfirmation() {
        show(
            title = "有任务待确认",
            message = text(R.string.message_pending_confirmation),
            success = false,
            tag = SUBMISSION_TAG,
        )
    }

    override fun reportInvalidConfiguration() {
        show(
            title = "连接配置无效",
            message = text(R.string.message_invalid_configuration),
            success = false,
            tag = SUBMISSION_TAG,
        )
    }

    override fun reportCapacityReached() {
        show(
            title = "任务已满",
            message = text(R.string.message_capacity_reached),
            success = false,
            tag = SUBMISSION_TAG,
        )
    }

    /** The screenshot exceeded the server's decoded-image limit. */
    override fun reportImageTooLarge(savedLocation: String?) {
        val saved = if (savedLocation != null) "，已保存到 " + savedLocation else "，且未能保存到相册"
        show(
            title = "截图过大",
            message = text(R.string.message_image_too_large) + saved,
            success = false,
            tag = SUBMISSION_TAG,
        )
    }

    override fun reportTask(text: String, success: Boolean) {
        show(
            title = if (success) "已受理" else "无法重试",
            message = text,
            success = success,
            tag = SUBMISSION_TAG,
        )
    }

    /** Notifies the final analysis result using a stable per-task tag. */
    override fun reportAnalysis(result: AnalysisPollResult, taskTag: String) {
        when (result) {
            is AnalysisPollResult.Finished -> when (val status = result.status) {
                is SourceDocumentStatus.Completed -> show(
                    "分析成功",
                    ConsumptionSummaryFormatter.format(status.items),
                    true,
                    taskTag,
                )
                is SourceDocumentStatus.Invalid -> show(
                    "该截图无法用于记账",
                    text(R.string.message_document_invalid),
                    false,
                    taskTag,
                )
                is SourceDocumentStatus.Anomaly -> show(
                    "该截图无法用于记账",
                    text(R.string.message_document_invalid),
                    false,
                    taskTag,
                )
                is SourceDocumentStatus.Failed -> show(
                    "分析失败",
                    errorCodeMessage(status.errorCode),
                    false,
                    taskTag,
                )
                SourceDocumentStatus.Cancelled -> show(
                    "分析已取消",
                    "服务器已取消本次分析",
                    false,
                    taskTag,
                )
                SourceDocumentStatus.Processing -> Unit
            }
            is AnalysisPollResult.QueryFailed -> show(
                "分析结果查询失败",
                queryFailureMessage(result.reason),
                false,
                taskTag,
            )
            AnalysisPollResult.TimedOut -> show(
                "分析结果查询暂停",
                text(R.string.message_query_paused),
                false,
                taskTag,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun show(title: String, message: String, success: Boolean, tag: String) {
        vibrate(if (success) longArrayOf(0, 45, 45, 65) else longArrayOf(0, 90, 60, 90))
        if (!NotificationAvailability.check(appContext).isVisible) {
            Toast.makeText(appContext, title + "：" + message, Toast.LENGTH_LONG).show()
            return
        }

        val settingsIntent = Intent(appContext, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_upload_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Toast.makeText(appContext, title + "：" + message, Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun uploadFailureMessage(failure: UploadFailure): String = when (failure) {
        UploadFailure.UNAUTHORIZED -> "API Key 无效"
        UploadFailure.RATE_LIMITED -> "请求过于频繁，请稍后重试"
        UploadFailure.SERVER_ERROR -> "服务器暂时不可用"
        UploadFailure.REJECTED -> "服务器拒绝了请求"
        UploadFailure.INVALID_RESPONSE -> text(R.string.message_invalid_response)
        UploadFailure.NETWORK_ERROR -> "网络连接失败或请求超时"
        UploadFailure.INVALID_CONFIGURATION -> text(R.string.message_invalid_configuration)
    }

    private fun text(resourceId: Int): String = appContext.getString(resourceId)

    private fun errorCodeMessage(errorCode: String?): String =
        errorCode?.takeIf(String::isNotBlank)?.let { "错误码：" + it } ?: "服务器未提供错误码"

    private fun queryFailureMessage(reason: StatusQueryFailure): String = when (reason) {
        StatusQueryFailure.UNAUTHORIZED -> "API Key 无效"
        StatusQueryFailure.NOT_FOUND -> "找不到本次分析任务"
        StatusQueryFailure.INVALID_RESPONSE -> text(R.string.message_invalid_response)
        StatusQueryFailure.REJECTED -> "服务器拒绝了查询请求"
        StatusQueryFailure.RATE_LIMITED -> "请求过于频繁"
        StatusQueryFailure.SERVER_ERROR -> "服务器暂时不可用"
        StatusQueryFailure.NETWORK_ERROR -> "网络连接失败或请求超时"
        StatusQueryFailure.INVALID_CONFIGURATION -> text(R.string.message_invalid_configuration)
    }

    companion object {
        const val CHANNEL_ID = "capture_results"
        private const val NOTIFICATION_ID = 1
        private const val SUBMISSION_TAG = "capture_submission"

        fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    enableVibration(false)
                },
            )
        }
    }
}
