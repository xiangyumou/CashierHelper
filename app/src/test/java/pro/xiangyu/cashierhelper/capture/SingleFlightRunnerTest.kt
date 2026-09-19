package pro.xiangyu.cashierhelper.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightRunnerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `rejects concurrent trigger and accepts next trigger after completion`() = runTest {
        val runner = SingleFlightRunner()
        val release = CompletableDeferred<Unit>()

        assertTrue(runner.tryLaunch(this) { release.await() })
        assertFalse(runner.tryLaunch(this) { })
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(runner.tryLaunch(this) { })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `background analysis does not keep submission single flight occupied`() = runTest {
        val runner = SingleFlightRunner()
        val analysisStarted = CompletableDeferred<Unit>()
        val finishAnalysis = CompletableDeferred<Unit>()

        assertTrue(
            runner.tryLaunch(this) {
                backgroundScope.launch {
                    analysisStarted.complete(Unit)
                    finishAnalysis.await()
                }
            },
        )
        runCurrent()
        analysisStarted.await()

        assertTrue(runner.tryLaunch(this) { })
        finishAnalysis.complete(Unit)
    }
}
