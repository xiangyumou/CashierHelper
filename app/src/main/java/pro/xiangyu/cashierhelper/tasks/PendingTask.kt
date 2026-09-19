package pro.xiangyu.cashierhelper.tasks

import kotlinx.serialization.Serializable

/**
 * Lifecycle of a persisted screenshot job. The app only ever reaches a
 * terminal state from a confirmed server response; anything uncertain is kept
 * for explicit user action instead of being silently retried.
 */
@Serializable
enum class TaskStatus {
    /** Image saved locally, POST not yet accepted. */
    PENDING_UPLOAD,

    /** POST was attempted but the outcome is unknown. Never auto-retried. */
    SUBMIT_UNCONFIRMED,

    /** Server accepted the submission; analysis has not finished. */
    ACCEPTED,

    /** Analysis was paused (budget or server backoff) and can be continued. */
    QUERY_PAUSED,

    /** Connection changed since submission; the task must not be replayed. */
    CONFIG_PAUSED,

    /** Unconfirmed and the local image expired; requires manual reconciliation. */
    NEEDS_REVIEW,

    COMPLETED,
    INVALID,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == INVALID || this == FAILED || this == CANCELLED

    /** True when the app does not know whether the server accepted the POST. */
    val isUnconfirmed: Boolean
        get() = this == PENDING_UPLOAD || this == SUBMIT_UNCONFIRMED || this == NEEDS_REVIEW
}

@Serializable
data class PendingTask(
    val id: String,
    val idempotencyKey: String,
    val configFingerprint: String,
    val baseUrl: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val status: TaskStatus,
    val sourceDocumentId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val summary: String? = null,
    val imageFileName: String? = null,
) {
    val canRetryOriginal: Boolean
        get() = sourceDocumentId == null &&
            (status == TaskStatus.PENDING_UPLOAD ||
                status == TaskStatus.SUBMIT_UNCONFIRMED ||
                status == TaskStatus.NEEDS_REVIEW)

    val canContinueQuery: Boolean
        get() = sourceDocumentId != null &&
            (status == TaskStatus.QUERY_PAUSED || status == TaskStatus.ACCEPTED ||
                status == TaskStatus.CONFIG_PAUSED)
}
