package pro.xiangyu.cashierhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlValidatorTest {
    @Test
    fun `normalizes https base url`() {
        assertEquals(
            "https://cashier.example.com/root",
            BaseUrlValidator.normalize("  HTTPS://Cashier.Example.com/root///  ").getOrThrow(),
        )
    }

    @Test
    fun `rejects cleartext url`() {
        val result = BaseUrlValidator.normalize("http://cashier.example.com")

        assertTrue(result.isFailure)
        assertEquals("服务器地址必须使用 HTTPS", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejects query and credentials`() {
        assertTrue(BaseUrlValidator.normalize("https://cashier.example.com?debug=1").isFailure)
        assertTrue(BaseUrlValidator.normalize("https://user@cashier.example.com").isFailure)
    }
}

