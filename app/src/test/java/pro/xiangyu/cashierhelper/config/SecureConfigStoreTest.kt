package pro.xiangyu.cashierhelper.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureConfigStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences("secure-config-test", Context.MODE_PRIVATE)
    }

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `encrypts api key and restores configuration`() {
        val store = SecureConfigStore(preferences, ReversingCipher)

        val saved = store.save("https://cashier.example.com/", "sk_test_secret").getOrThrow()

        assertEquals("https://cashier.example.com", saved.baseUrl)
        assertEquals(saved, store.loadConfig())
        assertFalse(preferences.all.values.any { it == "sk_test_secret" })
    }

    @Test
    fun `requires complete valid configuration`() {
        val store = SecureConfigStore(preferences, ReversingCipher)

        assertNull(store.loadConfig())
        assertTrue(store.save("http://cashier.example.com", "key").isFailure)
        assertTrue(store.save("https://cashier.example.com", " ").isFailure)
    }

    private object ReversingCipher : SecretCipher {
        override fun encrypt(value: String) = EncryptedSecret(value.reversed(), "test-iv")

        override fun decrypt(secret: EncryptedSecret): String = secret.ciphertext.reversed()
    }
}
