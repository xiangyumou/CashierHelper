package pro.xiangyu.cashierhelper.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotProcessorTest {
    @Test
    fun `downscales wide screenshot to 1080 preserving aspect ratio`() {
        val source = Bitmap.createBitmap(1440, 3120, Bitmap.Config.ARGB_8888)

        val bytes = ScreenshotProcessor().encode(source)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertTrue(bytes.isNotEmpty())
        assertEquals(1080, decoded.width)
        assertEquals(2340, decoded.height)
        source.recycle()
        decoded.recycle()
    }

    @Test
    fun `keeps screenshot width below limit`() {
        val source = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)

        val bytes = ScreenshotProcessor().encode(source)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertEquals(720, decoded.width)
        assertEquals(1280, decoded.height)
        source.recycle()
        decoded.recycle()
    }
}
