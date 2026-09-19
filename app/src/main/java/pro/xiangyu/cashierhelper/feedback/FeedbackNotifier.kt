package pro.xiangyu.cashierhelper.feedback

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pro.xiangyu.cashierhelper.R
import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.analysis.ConsumptionSummaryFormatter
import pro.xiangyu.cashierhelper.capture.CaptureOutcome
import pro.xiangyu.cashierhelper.capture.ScreenshotErrorMapper
import pro.xiangyu.cashierhelper.network.UploadFailure
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.ui.SettingsActivity

class FeedbackNotifier(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun acknowledgeScreenshot() {
        vibrate(longArrayOf(0, 35))
    }

    fun report(outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.UploadAccepted -> show(
                title = "提交成功",
                message = "截图已提交处理",
                success = true,
                notificationId = SUBMISSION_NOTIFICATION_ID,
            )
            CaptureOutcome.Busy -> show(
                title = "正在处理",
                message = "上一张截图仍在提交",
                success = false,
                notificationId = SUBMISSION_NOTIFICATION_ID,
            )
            CaptureOutcome.MissingConfiguration -> show(
                title = "需要完成设置",
                message = "请先填写服务器地址和 API Key",
                success = false,
                notificationId = SUBMISSION_NOTIFICATION_ID,
            )
            CaptureOutcome.ServiceUnavailable -> show(
                title = "截图服务未连接",
                message = "请检查无障碍服务是否已开启",
                success = false,
                notificationId = SUBMISSION_NOTIFICATION_ID,
            )
            is CaptureOutcome.CaptureFailed -> show(
                title = "截图失败",
                message = ScreenshotErrorMapper.message(outcome.errorCode),
                success = false,
                notificationId = SUBMISSION_NOTIFICATION_ID,
            )
            is CaptureOutcome.UploadFailed -> {
                val savedMessage = if (outcome.savedLocation != null) {
                    "截图已保存到 ${outcome.savedLocation}"
                } else {
                    "截图未能保存到相册"
                }
                show(
                    title = "提交失败",
                    message = "${uploadFailureMessage(outcome.reason)}；$savedMessage",
                    success = false,
                    notificationId = SUBMISSION_NOTIFICATION_ID,
                )
            }
        }
    }

    fun reportAnalysis(result: AnalysisPollResult, notificationId: Int) {
        when (result) {
            is AnalysisPollResult.Finished -> when (val status = result.status) {
                is SourceDocumentStatus.Completed -> show(
                    "分析成功",
                    ConsumptionSummaryFormatter.format(status.items),
                    true,
                    notificationId,
                )
                is SourceDocumentStatus.Anomaly -> show(
                    "分析异常",
                    errorCodeMessage(status.errorCode),
                    false,
                    notificationId,
                )
                is SourceDocumentStatus.Failed -> show(
                    "分析失败",
                    errorCodeMessage(status.errorCode),
                    false,
                    notificationId,
                )
                SourceDocumentStatus.Cancelled -> show(
                    "分析已取消",
                    "服务器已取消本次分析",
                    false,
                    notificationId,
                )
                SourceDocumentStatus.Processing -> Unit
            }
            is AnalysisPollResult.QueryFailed -> show(
                "分析结果查询失败",
                queryFailureMessage(result),
                false,
                notificationId,
            )
            AnalysisPollResult.TimedOut -> show(
                "分析结果查询超时",
                "一分钟内未取得分析结果",
                false,
                notificationId,
            )
        }
    }

    fun nextAnalysisNotificationId(): Int = nextAnalysisNotificationId.getAndIncrement()

    @SuppressLint("MissingPermission")
    private fun show(title: String, message: String, success: Boolean, notificationId: Int) {
        vibrate(if (success) longArrayOf(0, 45, 45, 65) else longArrayOf(0, 90, 60, 90))
        if (!canPostNotifications()) {
            Toast.makeText(appContext, "$title：$message", Toast.LENGTH_LONG).show()
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
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
            Toast.makeText(appContext, "$title：$message", Toast.LENGTH_LONG).show()
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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
        UploadFailure.RATE_LIMITED -> "请求过于频繁"
        UploadFailure.SERVER_ERROR -> "服务器暂时不可用"
        UploadFailure.REJECTED -> "服务器拒绝了请求"
        UploadFailure.INVALID_RESPONSE -> "提交响应格式错误"
        UploadFailure.NETWORK_ERROR -> "网络连接失败或请求超时"
    }

    private fun errorCodeMessage(errorCode: String?): String =
        errorCode?.takeIf(String::isNotBlank)?.let { "错误码：$it" } ?: "服务器未提供错误码"

    private fun queryFailureMessage(result: AnalysisPollResult.QueryFailed): String {
        if (result.reason == StatusQueryFailure.INVALID_RESPONSE) {
            return result.responseBody?.takeIf(String::isNotEmpty) ?: "（空响应）"
        }
        val message = when (result.reason) {
            StatusQueryFailure.UNAUTHORIZED -> "API Key 无效"
            StatusQueryFailure.NOT_FOUND -> "找不到本次分析任务"
            StatusQueryFailure.INVALID_RESPONSE -> error("Handled above")
            StatusQueryFailure.REJECTED -> "服务器拒绝了查询请求"
            StatusQueryFailure.RATE_LIMITED -> "请求过于频繁"
            StatusQueryFailure.SERVER_ERROR -> "服务器暂时不可用"
            StatusQueryFailure.NETWORK_ERROR -> "网络连接失败或请求超时"
        }
        return message
    }

    companion object {
        private const val CHANNEL_ID = "capture_results"
        private const val SUBMISSION_NOTIFICATION_ID = 1001
        private val nextAnalysisNotificationId = java.util.concurrent.atomic.AtomicInteger(2000)

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
