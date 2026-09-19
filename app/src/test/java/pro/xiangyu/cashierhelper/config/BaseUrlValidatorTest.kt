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

    @Test
    fun `rejects an invalid port`() {
        assertTrue(BaseUrlValidator.normalize("https://cashier.example.com:99999").isFailure)
        assertTrue(BaseUrlValidator.normalize("https://cashier.example.com:abc").isFailure)
        assertTrue(BaseUrlValidator.normalize("https://cashier.example.com:0").isFailure)
    }

    @Test
    fun `rejects a fragment`() {
        assertTrue(BaseUrlValidator.normalize("https://cashier.example.com#top").isFailure)
    }

    @Test
    fun `rejects blank and unparsable addresses`() {
        assertEquals("请输入服务器地址", BaseUrlValidator.normalize("   ").exceptionOrNull()?.message)
        assertTrue(BaseUrlValidator.normalize("cashier.example.com").isFailure)
        assertTrue(BaseUrlValidator.normalize("not a url").isFailure)
    }

    @Test
    fun `keeps a legal port and base path`() {
        assertEquals(
            "https://cashier.example.com:8443/api/v1",
            BaseUrlValidator.normalize("https://Cashier.Example.com:8443/api/v1/").getOrThrow(),
        )
    }
}
