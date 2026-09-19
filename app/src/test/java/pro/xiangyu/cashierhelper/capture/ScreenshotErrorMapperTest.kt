package pro.xiangyu.cashierhelper.capture

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotErrorMapperTest {
    @Test
    fun `maps secure window and unknown errors`() {
        assertEquals(
            "当前页面禁止截图",
            ScreenshotErrorMapper.message(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW),
        )
        assertEquals("未能截取当前屏幕", ScreenshotErrorMapper.message(999))
    }
}

