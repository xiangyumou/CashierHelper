package pro.xiangyu.cashierhelper.capture

/**
 * Outcome of a capture trigger request. The unified entry point maps these to
 * immediate feedback so a screenshot is never captured before the app knows it
 * can actually handle it.
 */
sealed interface CaptureTriggerResult {
    data object Started : CaptureTriggerResult
    data object Busy : CaptureTriggerResult
    data object MissingConfiguration : CaptureTriggerResult
    data object ServiceUnavailable : CaptureTriggerResult
}
