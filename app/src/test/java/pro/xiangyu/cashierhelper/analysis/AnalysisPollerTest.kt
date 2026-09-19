package pro.xiangyu.cashierhelper.analysis

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.SourceDocumentStatusClient
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.network.StatusQueryResult

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisPollerTest {
    private val config = AppConfig("https://cashier.example.com", "key")

    @Test
    fun `first query waits two seconds and terminal state stops polling`() = runTest {
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Status(SourceDocumentStatus.Processing),
                StatusQueryResult.Status(SourceDocumentStatus.Cancelled),
            ),
        )
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(0, client.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(AnalysisPollResult.Finished(SourceDocumentStatus.Cancelled), result.await())
        assertEquals(2, client.calls)
    }

    @Test
    fun `retryable failures continue and fatal failures stop`() = runTest {
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Failed(StatusQueryFailure.NETWORK_ERROR, true),
                StatusQueryResult.Failed(StatusQueryFailure.RATE_LIMITED, true),
                StatusQueryResult.Failed(StatusQueryFailure.NOT_FOUND, false),
            ),
        )

        val result = async { AnalysisPoller(client).poll(config, "doc-1") }
        advanceTimeBy(12_000)
        runCurrent()

        assertEquals(AnalysisPollResult.QueryFailed(StatusQueryFailure.NOT_FOUND), result.await())
        assertEquals(3, client.calls)
    }

    @Test
    fun `successful query resets retry backoff to normal interval`() = runTest {
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Failed(StatusQueryFailure.NETWORK_ERROR, true),
                StatusQueryResult.Status(SourceDocumentStatus.Processing),
                StatusQueryResult.Status(SourceDocumentStatus.Cancelled),
            ),
        )
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, client.calls)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, client.calls)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(AnalysisPollResult.Finished(SourceDocumentStatus.Cancelled), result.await())
        assertEquals(3, client.calls)
    }

    @Test
    fun `invalid response body reaches poll result`() = runTest {
        val body = """{"unexpected":true}"""
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Failed(StatusQueryFailure.INVALID_RESPONSE, false, body),
            ),
        )
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            AnalysisPollResult.QueryFailed(StatusQueryFailure.INVALID_RESPONSE, body),
            result.await(),
        )
    }

    @Test
    fun `processing state times out at sixty seconds`() = runTest {
        val client = FakeClient(mutableListOf())
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(AnalysisPollResult.TimedOut, result.await())
        assertEquals(29, client.calls)
    }

    @Test
    fun `multiple documents poll independently`() = runTest {
        val queriedIds = mutableListOf<String>()
        val client = object : SourceDocumentStatusClient {
            override suspend fun query(
                config: AppConfig,
                sourceDocumentId: String,
            ): StatusQueryResult {
                queriedIds += sourceDocumentId
                return StatusQueryResult.Status(SourceDocumentStatus.Cancelled)
            }
        }
        val poller = AnalysisPoller(client)
        val first = async { poller.poll(config, "doc-1") }
        val second = async { poller.poll(config, "doc-2") }

        advanceTimeBy(2_000)
        runCurrent()

        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(setOf("doc-1", "doc-2"), queriedIds.toSet())
    }

    private class FakeClient(
        private val results: MutableList<StatusQueryResult>,
    ) : SourceDocumentStatusClient {
        var calls = 0

        override suspend fun query(config: AppConfig, sourceDocumentId: String): StatusQueryResult {
            calls++
            return if (results.isEmpty()) {
                StatusQueryResult.Status(SourceDocumentStatus.Processing)
            } else {
                results.removeAt(0)
            }
        }
    }
}
