package pro.xiangyu.cashierhelper.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {
    fun isEnabled(context: Context): Boolean {
        if (
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) != 1
        ) {
            return false
        }

        val expected = ComponentName(context, CashierAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }
}

