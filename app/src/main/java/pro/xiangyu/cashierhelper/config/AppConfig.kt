package pro.xiangyu.cashierhelper.config

import java.security.MessageDigest

data class AppConfig(
    val baseUrl: String,
    val apiKey: String,
)

data class ConfigDraft(
    val baseUrl: String = "",
    val apiKey: String = "",
)

/**
 * Stable, non-reversible identity for a saved connection. Binds the normalized
 * base URL and the credential together so persisted tasks can detect that the
 * connection changed and must not be replayed against a different server.
 */
object ConfigFingerprint {
    fun of(config: AppConfig): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(config.baseUrl.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(config.apiKey.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
