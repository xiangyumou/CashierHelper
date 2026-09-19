package pro.xiangyu.cashierhelper.network

import pro.xiangyu.cashierhelper.config.AppConfig

sealed interface UploadResult {
    data class Accepted(
        val sourceDocumentId: String,
    ) : UploadResult

    data class Failed(
        val reason: UploadFailure,
        val retryAfterMillis: Long? = null,
    ) : UploadResult
}

enum class UploadFailure {
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER_ERROR,
    REJECTED,
    INVALID_RESPONSE,
    NETWORK_ERROR,
    INVALID_CONFIGURATION,
}

interface SourceDocumentUploader {
    suspend fun upload(
        config: AppConfig,
        jpegBytes: ByteArray,
        idempotencyKey: String,
    ): UploadResult
}
