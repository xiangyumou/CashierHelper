package pro.xiangyu.cashierhelper.capture

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SingleFlightRunner {
    private val running = AtomicBoolean(false)

    fun tryLaunch(scope: CoroutineScope, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch {
            try {
                block()
            } finally {
                running.set(false)
            }
        }
        return true
    }
}

