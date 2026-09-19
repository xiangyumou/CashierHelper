package pro.xiangyu.cashierhelper.feedback

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Single source of truth for whether a posted notification would actually be
 * visible. Runtime permission, the app-level notification switch and the
 * capture-results channel are independent, so all three are checked.
 */
object NotificationAvailability {
    data class Status(
        val permissionGranted: Boolean,
        val appEnabled: Boolean,
        val channelEnabled: Boolean,
    ) {
        val isVisible: Boolean get() = permissionGranted && appEnabled && channelEnabled
    }

    fun check(context: Context): Status {
        val appContext = context.applicationContext
        return Status(
            permissionGranted = hasRuntimePermission(appContext),
            appEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            channelEnabled = isChannelEnabled(appContext),
        )
    }

    fun hasRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun isChannelEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(FeedbackNotifier.CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
