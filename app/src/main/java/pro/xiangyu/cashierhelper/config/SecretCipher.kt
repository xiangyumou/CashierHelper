package pro.xiangyu.cashierhelper.config

data class EncryptedSecret(
    val ciphertext: String,
    val initializationVector: String,
)

data class EncryptedBytes(
    val ciphertext: ByteArray,
    val initializationVector: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is EncryptedBytes &&
        ciphertext.contentEquals(other.ciphertext) &&
        initializationVector.contentEquals(other.initializationVector)

    override fun hashCode(): Int =
        31 * ciphertext.contentHashCode() + initializationVector.contentHashCode()
}

interface SecretCipher {
    fun encrypt(value: String): EncryptedSecret
    fun decrypt(secret: EncryptedSecret): String
}

/**
 * AES-GCM codec for binary task payloads (persisted screenshots and metadata).
 * Kept separate from [SecretCipher] so existing string-credential fakes only
 * need to implement the credential path.
 */
interface BinarySecretCipher {
    fun encryptBytes(value: ByteArray): EncryptedBytes
    fun decryptBytes(secret: EncryptedBytes): ByteArray
}
