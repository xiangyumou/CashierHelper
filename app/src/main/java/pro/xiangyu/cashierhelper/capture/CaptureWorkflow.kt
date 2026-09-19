package pro.xiangyu.cashierhelper.capture

import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.network.SourceDocumentUploader
import pro.xiangyu.cashierhelper.network.UploadResult
import pro.xiangyu.cashierhelper.storage.FailedImageStore

class CaptureWorkflow(
    private val screenshotSource: ScreenshotSource,
    private val uploader: SourceDocumentUploader,
    private val failedImageStore: FailedImageStore,
    private val onScreenshotCaptured: () -> Unit = {},
) {
    suspend fun execute(config: AppConfig): CaptureOutcome {
        val captureResult = screenshotSource.captureJpeg()
        val jpegBytes = captureResult.getOrElse { error ->
            return CaptureOutcome.CaptureFailed((error as? ScreenshotException)?.errorCode)
        }
        runCatching(onScreenshotCaptured)

        return when (val uploadResult = uploader.upload(config, jpegBytes)) {
            is UploadResult.Accepted -> CaptureOutcome.UploadAccepted(uploadResult.sourceDocumentId)
            is UploadResult.Failed -> CaptureOutcome.UploadFailed(
                reason = uploadResult.reason,
                savedLocation = try {
                    failedImageStore.save(jpegBytes)
                } catch (_: Exception) {
                    null
                },
            )
        }
    }
}
