package pro.xiangyu.cashierhelper.analysis

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.SourceDocumentStatusClient
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.network.StatusQueryResult

sealed interface AnalysisPollResult {
    data class Finished(val status: SourceDocumentStatus) : AnalysisPollResult
    data class QueryFailed(
        val reason: StatusQueryFailure,
        val responseBody: String? = null,
    ) : AnalysisPollResult
    data object TimedOut : AnalysisPollResult
}

class AnalysisPoller(
    private val client: SourceDocumentStatusClient,
    private val pollIntervalMillis: Long = 2_000,
    private val retryIntervalMillis: Long = 5_000,
    private val timeoutMillis: Long = 60_000,
) {
    suspend fun poll(config: AppConfig, sourceDocumentId: String): AnalysisPollResult {
        return withTimeoutOrNull(timeoutMillis) {
            var nextDelayMillis = pollIntervalMillis
            while (true) {
                delay(nextDelayMillis)
                when (val result = client.query(config, sourceDocumentId)) {
                    is StatusQueryResult.Status -> {
                        nextDelayMillis = pollIntervalMillis
                        if (result.value !is SourceDocumentStatus.Processing) {
                            return@withTimeoutOrNull AnalysisPollResult.Finished(result.value)
                        }
                    }
                    is StatusQueryResult.Failed -> {
                        if (!result.retryable) {
                            return@withTimeoutOrNull AnalysisPollResult.QueryFailed(
                                result.reason,
                                result.responseBody,
                            )
                        }
                        nextDelayMillis = retryIntervalMillis
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            AnalysisPollResult.TimedOut
        } ?: AnalysisPollResult.TimedOut
    }
}
