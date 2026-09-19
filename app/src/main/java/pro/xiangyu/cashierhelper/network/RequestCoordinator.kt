package pro.xiangyu.cashierhelper.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-wide scheduler shared by every request that targets the same
 * server and credential.
 *
 * GET queries are spaced at least one interval apart and handed out in arrival
 * order so multiple documents rotate fairly instead of one task monopolizing
 * the window. Uploads are never blocked behind a queued GET, but both request
 * kinds honor a shared cooldown after the server answers with 429.
 */
class RequestCoordinator(
    private val minGetIntervalMillis: Long = DEFAULT_GET_INTERVAL_MILLIS,
    private val defaultBackoffMillis: Long = DEFAULT_BACKOFF_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, ConfigState>()

    private class ConfigState {
        var nextGetAt: Long = 0L
        var cooldownUntil: Long = 0L
    }

    /** Reserves the next shared GET slot for [fingerprint], then runs [block]. */
    suspend fun <T> runGet(fingerprint: String, block: suspend () -> T): T {
        val reservedAt = mutex.withLock {
            val state = stateFor(fingerprint)
            val now = clock()
            val at = maxOf(now, state.nextGetAt, state.cooldownUntil)
            state.nextGetAt = at + minGetIntervalMillis
            at
        }
        awaitGate(fingerprint, reservedAt)
        return block()
    }

    /** Runs an upload. Uploads skip the GET interval but honor shared cooldown. */
    suspend fun <T> runUpload(fingerprint: String, block: suspend () -> T): T {
        awaitGate(fingerprint, 0L)
        return block()
    }

    /**
     * Applies a shared cooldown after a 429. A valid server value is respected
     * as-is; only a missing value falls back to the default backoff. The
     * cooldown never shortens an existing one.
     */
    suspend fun onRateLimited(fingerprint: String, retryAfterMillis: Long?) {
        val cooldown = retryAfterMillis?.coerceAtLeast(0L) ?: defaultBackoffMillis
        mutex.withLock {
            val state = stateFor(fingerprint)
            state.cooldownUntil = maxOf(state.cooldownUntil, clock() + cooldown)
        }
    }

    private suspend fun awaitGate(fingerprint: String, reservedAt: Long) {
        while (true) {
            val now = clock()
            val gate = mutex.withLock {
                maxOf(reservedAt, stateFor(fingerprint).cooldownUntil)
            }
            if (gate <= now) return
            delayFn(maxOf(1L, gate - now))
        }
    }

    private fun stateFor(fingerprint: String): ConfigState =
        states.getOrPut(fingerprint) { ConfigState() }

    companion object {
        const val DEFAULT_GET_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_BACKOFF_MILLIS = 5_000L

        /**
         * Parses a Retry-After header (delay-seconds or an HTTP date). Returns
         * null when the value is absent or malformed so callers can fall back
         * to the default interval.
         */
        fun parseRetryAfter(header: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
            val value = header?.trim().orEmpty()
            if (value.isEmpty()) return null
            value.toLongOrNull()?.let { seconds ->
                return if (seconds >= 0L) seconds * 1000L else null
            }
            val epochMillis = parseHttpDate(value) ?: return null
            return (epochMillis - nowMillis).coerceAtLeast(0L)
        }

        private fun parseHttpDate(value: String): Long? = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
