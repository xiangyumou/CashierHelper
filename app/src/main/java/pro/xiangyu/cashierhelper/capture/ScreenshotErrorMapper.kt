package pro.xiangyu.cashierhelper.capture

import android.accessibilityservice.AccessibilityService

object ScreenshotErrorMapper {
    fun message(errorCode: Int?): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面禁止截图"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "触发过快，请稍后再试"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "截图服务没有访问权限"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "当前屏幕不可用"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "当前窗口不可截图"
        else -> "未能截取当前屏幕"
    }
}

