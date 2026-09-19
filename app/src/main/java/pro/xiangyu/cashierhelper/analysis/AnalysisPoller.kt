package pro.xiangyu.cashierhelper.analysis

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.network.RequestCoordinator
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.SourceDocumentStatusClient
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.network.StatusQueryResult

sealed interface AnalysisPollResult {
    data class Finished(val status: SourceDocumentStatus) : AnalysisPollResult
    data class QueryFailed(val reason: StatusQueryFailure) : AnalysisPollResult
    /** The one-minute budget ran out; the task is retained and can be continued. */
    data object TimedOut : AnalysisPollResult
}

/**
 * Polls one source document until it reaches a terminal state, the server
 * requests a wait longer than the remaining budget, or the overall budget is
 * exhausted.
 *
 * The first and subsequent queries are spaced [pollIntervalMillis] apart. When
 * a shared [coordinator] is supplied, its gate is included in the budget and
 * its 429 cooldown is refreshed, so several tasks poll without exceeding
 * shared rate limits.
 */
class AnalysisPoller(
    private val client: SourceDocumentStatusClient,
    private val coordinator: RequestCoordinator? = null,
    private val fingerprint: String = "",
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val retryIntervalMillis: Long = DEFAULT_RETRY_INTERVAL_MILLIS,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun poll(config: AppConfig, sourceDocumentId: String): AnalysisPollResult =
        withTimeoutOrNull(timeoutMillis) {
            var nextDelayMillis = pollIntervalMillis
            while (true) {
                delay(nextDelayMillis)
                when (val result = query(config, sourceDocumentId)) {
                    is StatusQueryResult.Status -> {
                        nextDelayMillis = pollIntervalMillis
                        if (result.value !is SourceDocumentStatus.Processing) {
                            return@withTimeoutOrNull AnalysisPollResult.Finished(result.value)
                        }
                    }
                    is StatusQueryResult.Failed -> {
                        if (!result.retryable) {
                            return@withTimeoutOrNull AnalysisPollResult.QueryFailed(result.reason)
                        }
                        if (result.reason == StatusQueryFailure.RATE_LIMITED) {
                            coordinator?.onRateLimited(fingerprint, result.retryAfterMillis)
                        }
                        nextDelayMillis = result.retryAfterMillis?.takeIf { it > 0L }
                            ?: retryIntervalMillis
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            AnalysisPollResult.TimedOut
        } ?: AnalysisPollResult.TimedOut

    private suspend fun query(config: AppConfig, sourceDocumentId: String): StatusQueryResult {
        val activeCoordinator = coordinator
        return if (activeCoordinator == null) {
            client.query(config, sourceDocumentId)
        } else {
            activeCoordinator.runGet(fingerprint) { client.query(config, sourceDocumentId) }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_RETRY_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}
