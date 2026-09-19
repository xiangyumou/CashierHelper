package pro.xiangyu.cashierhelper.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestCoordinatorTest {
    @Test
    fun `queries on one connection are spaced by the shared interval`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        launch { coordinator.runGet("fp") { order += "a" } }
        runCurrent()
        launch { coordinator.runGet("fp") { order += "b" } }
        runCurrent()

        assertEquals(listOf("a"), order)
        advanceTimeBy(RequestCoordinator.DEFAULT_GET_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun `different connections do not share the query gate`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        launch { coordinator.runGet("a") { order += "a" } }
        launch { coordinator.runGet("b") { order += "b" } }
        runCurrent()

        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun `a rate limit cools down queries for the whole connection`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        coordinator.onRateLimited("fp", 20_000)
        launch { coordinator.runGet("fp") { order += "get" } }
        runCurrent()
        assertTrue(order.isEmpty())

        advanceTimeBy(19_999)
        runCurrent()
        assertTrue(order.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("get"), order)
    }

    @Test
    fun `a server cooldown is never shortened by a shorter one`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        coordinator.onRateLimited("fp", 30_000)
        coordinator.onRateLimited("fp", 1_000)
        launch { coordinator.runGet("fp") { order += "get" } }
        runCurrent()

        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(order.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("get"), order)
    }

    @Test
    fun `a missing retry after falls back to the default backoff`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        coordinator.onRateLimited("fp", null)
        launch { coordinator.runGet("fp") { order += "get" } }
        runCurrent()

        advanceTimeBy(RequestCoordinator.DEFAULT_BACKOFF_MILLIS - 1)
        runCurrent()
        assertTrue(order.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("get"), order)
    }

    @Test
    fun `an upload is not blocked behind a queued query`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        launch { coordinator.runGet("fp") { order += "first-get" } }
        runCurrent()
        launch { coordinator.runGet("fp") { order += "second-get" } }
        runCurrent()

        coordinator.runUpload("fp") { order += "upload" }
        assertEquals(listOf("first-get", "upload"), order)

        advanceTimeBy(RequestCoordinator.DEFAULT_GET_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(listOf("first-get", "upload", "second-get"), order)
    }

    @Test
    fun `an upload respects a shared cooldown`() = runTest {
        val coordinator = RequestCoordinator(clock = { testScheduler.currentTime })
        val order = mutableListOf<String>()

        coordinator.onRateLimited("fp", 10_000)
        launch { coordinator.runUpload("fp") { order += "upload" } }
        runCurrent()
        assertTrue(order.isEmpty())

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("upload"), order)
    }

    @Test
    fun `retry after seconds are parsed and never truncated`() {
        assertEquals(5_000L, RequestCoordinator.parseRetryAfter("5"))
        assertEquals(0L, RequestCoordinator.parseRetryAfter("0"))
        assertEquals(120_000L, RequestCoordinator.parseRetryAfter(" 120 "))
    }

    @Test
    fun `retry after http dates are parsed relative to now`() {
        val now = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val formatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        val future = formatter.format(Instant.ofEpochMilli(now + 30_000))
        assertEquals(30_000L, RequestCoordinator.parseRetryAfter(future, now))

        val past = formatter.format(Instant.ofEpochMilli(now - 30_000))
        assertEquals(0L, RequestCoordinator.parseRetryAfter(past, now))
    }

    @Test
    fun `malformed retry after values fall back to null`() {
        assertNull(RequestCoordinator.parseRetryAfter(null))
        assertNull(RequestCoordinator.parseRetryAfter(""))
        assertNull(RequestCoordinator.parseRetryAfter("   "))
        assertNull(RequestCoordinator.parseRetryAfter("soon"))
        assertNull(RequestCoordinator.parseRetryAfter("-3"))
    }
}
