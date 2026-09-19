package pro.xiangyu.cashierhelper.network

import pro.xiangyu.cashierhelper.config.AppConfig
import java.math.BigDecimal

data class ConsumptionItem(
    val amount: BigDecimal,
    val currency: String?,
    val category: String?,
)

sealed interface SourceDocumentStatus {
    data object Processing : SourceDocumentStatus
    data class Completed(val items: List<ConsumptionItem>) : SourceDocumentStatus
    data class Anomaly(val errorCode: String?) : SourceDocumentStatus
    data class Failed(val errorCode: String?) : SourceDocumentStatus
    data object Cancelled : SourceDocumentStatus
}

enum class StatusQueryFailure {
    UNAUTHORIZED,
    NOT_FOUND,
    INVALID_RESPONSE,
    REJECTED,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
}

sealed interface StatusQueryResult {
    data class Status(val value: SourceDocumentStatus) : StatusQueryResult
    data class Failed(
        val reason: StatusQueryFailure,
        val retryable: Boolean,
        val responseBody: String? = null,
    ) : StatusQueryResult
}

interface SourceDocumentStatusClient {
    suspend fun query(config: AppConfig, sourceDocumentId: String): StatusQueryResult
}
