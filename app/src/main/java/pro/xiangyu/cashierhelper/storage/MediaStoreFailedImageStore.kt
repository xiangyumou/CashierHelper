package pro.xiangyu.cashierhelper.storage

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MediaStoreFailedImageStore(
    context: Context,
) : FailedImageStore {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun save(jpegBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val filename = "cashier-helper-${FILE_TIME_FORMAT.format(LocalDateTime.now())}.jpg"
        val relativePath = "Pictures/CashierHelper"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        var uri: android.net.Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            resolver.openOutputStream(uri, "w")?.use { it.write(jpegBytes) }
                ?: error("Unable to open MediaStore output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            "$relativePath/$filename"
        } catch (_: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    private companion object {
        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
