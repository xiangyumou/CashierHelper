package pro.xiangyu.cashierhelper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyValidatorTest {
    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("sk_test_123", ApiKeyValidator.normalize("  sk_test_123\n").getOrThrow())
    }

    @Test
    fun `rejects an empty key with a user facing message`() {
        val result = ApiKeyValidator.normalize("   ")

        assertTrue(result.isFailure)
        assertEquals("请输入 API Key", result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejects inner spaces and control characters`() {
        assertTrue(ApiKeyValidator.normalize("sk test").isFailure)
        assertTrue(ApiKeyValidator.normalize("sk\ttest").isFailure)
        assertTrue(ApiKeyValidator.normalize("sk\u0000test").isFailure)
    }

    @Test
    fun `rejects non ascii characters`() {
        assertTrue(ApiKeyValidator.normalize("密钥").isFailure)
        assertTrue(ApiKeyValidator.normalize("sk_test_ü").isFailure)
    }
}
