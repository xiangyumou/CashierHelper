package pro.xiangyu.cashierhelper.config

data class EncryptedSecret(
    val ciphertext: String,
    val initializationVector: String,
)

interface SecretCipher {
    fun encrypt(value: String): EncryptedSecret
    fun decrypt(secret: EncryptedSecret): String
}

