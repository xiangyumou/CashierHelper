package pro.xiangyu.cashierhelper.config

import android.content.Context
import android.content.SharedPreferences

class SecureConfigStore(
    private val preferences: SharedPreferences,
    private val cipher: SecretCipher,
) : ConfigStore {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        cipher = AndroidKeystoreSecretCipher(),
    )

    override fun loadDraft(): ConfigDraft {
        val baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty()
        val apiKey = runCatching {
            val ciphertext = preferences.getString(KEY_API_CIPHERTEXT, null) ?: return@runCatching ""
            val iv = preferences.getString(KEY_API_IV, null) ?: return@runCatching ""
            cipher.decrypt(EncryptedSecret(ciphertext, iv))
        }.getOrDefault("")
        return ConfigDraft(baseUrl = baseUrl, apiKey = apiKey)
    }

    override fun loadConfig(): AppConfig? {
        val draft = loadDraft()
        val normalizedUrl = BaseUrlValidator.normalize(draft.baseUrl).getOrNull() ?: return null
        val apiKey = ApiKeyValidator.normalize(draft.apiKey).getOrNull() ?: return null
        return AppConfig(normalizedUrl, apiKey)
    }

    override fun save(baseUrl: String, apiKey: String): Result<AppConfig> = runCatching {
        val normalizedUrl = BaseUrlValidator.normalize(baseUrl).getOrThrow()
        val normalizedKey = ApiKeyValidator.normalize(apiKey).getOrThrow()
        val encrypted = cipher.encrypt(normalizedKey)

        check(
            preferences.edit()
                .putString(KEY_BASE_URL, normalizedUrl)
                .putString(KEY_API_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_API_IV, encrypted.initializationVector)
                .commit(),
        ) { "无法保存设置" }

        AppConfig(normalizedUrl, normalizedKey)
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_API_IV = "api_key_iv"
    }
}
