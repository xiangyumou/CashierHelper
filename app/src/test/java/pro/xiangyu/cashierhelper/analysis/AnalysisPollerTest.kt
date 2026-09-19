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
import pro.xiangyu.cashierhelper.network.RequestCoordinator
import pro.xiangyu.cashierhelper.network.SourceDocumentStatus
import pro.xiangyu.cashierhelper.network.SourceDocumentStatusClient
import pro.xiangyu.cashierhelper.network.StatusQueryFailure
import pro.xiangyu.cashierhelper.network.StatusQueryResult

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisPollerTest {
    private val config = AppConfig("https://cashier.example.com", "key")

    @Test
    fun `first and subsequent queries are five seconds apart`() = runTest {
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Status(SourceDocumentStatus.Processing),
                StatusQueryResult.Status(SourceDocumentStatus.Cancelled),
            ),
        )
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, client.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(1)
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
        advanceTimeBy(30_000)
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

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, client.calls)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(AnalysisPollResult.Finished(SourceDocumentStatus.Cancelled), result.await())
        assertEquals(3, client.calls)
    }

    @Test
    fun `server retry after is honored verbatim`() = runTest {
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Failed(
                    StatusQueryFailure.RATE_LIMITED,
                    retryable = true,
                    retryAfterMillis = 30_000,
                ),
                StatusQueryResult.Status(SourceDocumentStatus.Cancelled),
            ),
        )
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(AnalysisPollResult.Finished(SourceDocumentStatus.Cancelled), result.await())
        assertEquals(2, client.calls)
    }

    @Test
    fun `processing keeps polling until the one minute budget runs out`() = runTest {
        val client = FakeClient(mutableListOf())
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals(AnalysisPollResult.TimedOut, result.await())
        assertTrue("expected ~11 queries, got " + client.calls, client.calls in 11..12)
    }

    @Test
    fun `non retryable response is surfaced without a body`() = runTest {
        val client = FakeClient(
            mutableListOf(StatusQueryResult.Failed(StatusQueryFailure.INVALID_RESPONSE, false)),
        )
        val result = async { AnalysisPoller(client).poll(config, "doc-1") }

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            AnalysisPollResult.QueryFailed(StatusQueryFailure.INVALID_RESPONSE),
            result.await(),
        )
    }

    @Test
    fun `shared coordinator interval throttles queries below the poll interval`() = runTest {
        val coordinator = RequestCoordinator(
            minGetIntervalMillis = 12_000,
            clock = { testScheduler.currentTime },
        )
        val client = FakeClient(
            mutableListOf(
                StatusQueryResult.Status(SourceDocumentStatus.Processing),
                StatusQueryResult.Status(SourceDocumentStatus.Cancelled),
            ),
        )
        val result = async {
            AnalysisPoller(client, coordinator, "fp").poll(config, "doc-1")
        }

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, client.calls)
        advanceTimeBy(6_999)
        runCurrent()
        assertEquals("second query must wait for the shared gate", 1, client.calls)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(AnalysisPollResult.Finished(SourceDocumentStatus.Cancelled), result.await())
        assertEquals(2, client.calls)
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

        advanceTimeBy(5_000)
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
