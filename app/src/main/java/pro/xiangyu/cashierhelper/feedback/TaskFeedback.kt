package pro.xiangyu.cashierhelper.feedback

import pro.xiangyu.cashierhelper.analysis.AnalysisPollResult
import pro.xiangyu.cashierhelper.capture.CaptureOutcome

/**
 * User-facing feedback surface used by the task coordinator.
 *
 * Keeping it as an interface lets the coordinator be exercised without an
 * Android notification stack, while [FeedbackNotifier] stays the only
 * production implementation.
 */
interface TaskFeedback {
    fun acknowledgeScreenshot()

    fun report(outcome: CaptureOutcome)

    fun reportPendingConfirmation()

    fun reportInvalidConfiguration()

    fun reportCapacityReached()

    fun reportImageTooLarge(savedLocation: String?)

    fun reportTask(text: String, success: Boolean)

    fun reportAnalysis(result: AnalysisPollResult, taskTag: String)
}
