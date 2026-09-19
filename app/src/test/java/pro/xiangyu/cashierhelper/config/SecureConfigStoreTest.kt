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

    @Test
    fun `a legacy cleartext address is rejected on load without crashing`() {
        val store = SecureConfigStore(preferences, ReversingCipher)
        store.save("https://cashier.example.com", "sk_test_secret").getOrThrow()
        // Simulate a value an older build could have stored.
        preferences.edit().putString("base_url", "http://cashier.example.com").commit()

        assertNull(store.loadConfig())
        assertEquals("http://cashier.example.com", store.loadDraft().baseUrl)
    }

    @Test
    fun `a legacy key with whitespace is rejected on load without crashing`() {
        val store = SecureConfigStore(preferences, ReversingCipher)
        store.save("https://cashier.example.com", "sk_test_secret").getOrThrow()
        preferences.edit()
            .putString("api_key_ciphertext", ReversingCipher.encrypt("sk test").ciphertext)
            .commit()

        assertNull(store.loadConfig())
        assertEquals("sk test", store.loadDraft().apiKey)
    }

    @Test
    fun `an undecryptable key is treated as missing`() {
        preferences.edit()
            .putString("base_url", "https://cashier.example.com")
            .putString("api_key_ciphertext", "ciphertext")
            .putString("api_key_iv", "iv")
            .commit()

        val store = SecureConfigStore(preferences, ThrowingCipher)

        assertNull(store.loadConfig())
        assertEquals("", store.loadDraft().apiKey)
    }

    private object ReversingCipher : SecretCipher {
        override fun encrypt(value: String) = EncryptedSecret(value.reversed(), "test-iv")

        override fun decrypt(secret: EncryptedSecret): String = secret.ciphertext.reversed()
    }

    private object ThrowingCipher : SecretCipher {
        override fun encrypt(value: String): EncryptedSecret = error("keystore unavailable")

        override fun decrypt(secret: EncryptedSecret): String = error("keystore unavailable")
    }
}
