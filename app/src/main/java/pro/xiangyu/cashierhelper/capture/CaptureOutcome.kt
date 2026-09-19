package pro.xiangyu.cashierhelper.capture

import pro.xiangyu.cashierhelper.network.UploadFailure

sealed interface CaptureOutcome {
    data class UploadAccepted(
        val sourceDocumentId: String,
    ) : CaptureOutcome
    data object Busy : CaptureOutcome
    data object MissingConfiguration : CaptureOutcome
    data object ServiceUnavailable : CaptureOutcome

    data class CaptureFailed(
        val errorCode: Int?,
    ) : CaptureOutcome

    data class UploadFailed(
        val reason: UploadFailure,
        val savedLocation: String?,
    ) : CaptureOutcome
}
