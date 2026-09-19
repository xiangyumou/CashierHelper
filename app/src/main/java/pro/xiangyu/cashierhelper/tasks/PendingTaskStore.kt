package pro.xiangyu.cashierhelper.tasks

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import pro.xiangyu.cashierhelper.config.BinarySecretCipher
import pro.xiangyu.cashierhelper.config.EncryptedBytes

sealed interface TaskSaveResult {
    data object Saved : TaskSaveResult
    data object CapacityReached : TaskSaveResult
    data object Failed : TaskSaveResult
}

/**
 * Durable store for pending screenshots.
 *
 * Both the metadata and the raw JPEG are encrypted with an Android Keystore
 * AES-GCM key before they touch disk, written to a private no-backup directory,
 * and replaced atomically so a crash can never leave a half-written task.
 */
class PendingTaskStore(
    private val directory: File,
    private val cipher: BinarySecretCipher,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val terminalRetentionMillis: Long = DEFAULT_TERMINAL_RETENTION_MILLIS,
    private val imageRetentionMillis: Long = DEFAULT_IMAGE_RETENTION_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    suspend fun loadAndClean(nowMillis: Long): List<PendingTask> = withContext(ioDispatcher) {
        mutex.withLock {
            directory.mkdirs()
            val loaded = readAll()
            val retained = mutableListOf<PendingTask>()
            loaded.forEach { task ->
                when {
                    task.status.isTerminal &&
                        nowMillis - task.updatedAtMillis > terminalRetentionMillis -> {
                        deleteFiles(task.id)
                    }
                    task.hasExpiredImage(nowMillis) -> {
                        deleteImageFile(task.id)
                        val expired = task.copy(
                            status = TaskStatus.NEEDS_REVIEW,
                            imageFileName = null,
                            updatedAtMillis = nowMillis,
                        )
                        if (writeMetadata(expired)) retained += expired else retained += task
                    }
                    else -> retained += task
                }
            }
            retained.sortedByDescending { it.createdAtMillis }
        }
    }

    suspend fun saveNew(task: PendingTask, imageBytes: ByteArray): TaskSaveResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                directory.mkdirs()
                if (readAll().count { !it.status.isTerminal } >= capacity) {
                    return@withLock TaskSaveResult.CapacityReached
                }
                if (!writeImage(task.id, imageBytes)) return@withLock TaskSaveResult.Failed
                if (!writeMetadata(task)) {
                    deleteImageFile(task.id)
                    return@withLock TaskSaveResult.Failed
                }
                TaskSaveResult.Saved
            }
        }

    suspend fun update(task: PendingTask): Boolean = withContext(ioDispatcher) {
        mutex.withLock { writeMetadata(task) }
    }

    suspend fun readImage(taskId: String): ByteArray? = withContext(ioDispatcher) {
        mutex.withLock { readImageFile(taskId) }
    }

    suspend fun deleteImage(taskId: String) = withContext(ioDispatcher) {
        mutex.withLock { deleteImageFile(taskId) }
    }

    suspend fun delete(taskId: String) = withContext(ioDispatcher) {
        mutex.withLock { deleteFiles(taskId) }
    }

    suspend fun count(): Int = withContext(ioDispatcher) {
        mutex.withLock { readAll().size }
    }

    private fun readAll(): List<PendingTask> {
        val files = directory.listFiles { file -> file.name.endsWith(METADATA_SUFFIX) }
            ?: return emptyList()
        return files.mapNotNull { file ->
            val task = runCatching { decodeTask(readFile(file)) }.getOrNull()
            if (task == null) {
                // Corrupt or undecryptable metadata: drop it instead of crashing.
                val id = file.name.removeSuffix(METADATA_SUFFIX)
                deleteFiles(id)
            }
            task
        }
    }

    private fun readImageFile(taskId: String): ByteArray? {
        val file = imageFile(taskId)
        if (!file.exists()) return null
        return runCatching { decodeBytes(file.readBytes()) }.getOrNull()
    }

    private fun writeImage(taskId: String, imageBytes: ByteArray): Boolean =
        runCatching { atomicWrite(imageFile(taskId), encodeBytes(imageBytes)) }.isSuccess

    private fun writeMetadata(task: PendingTask): Boolean = runCatching {
        atomicWrite(metadataFile(task.id), encodeTask(task))
    }.isSuccess

    private fun deleteImageFile(taskId: String) {
        runCatching { imageFile(taskId).delete() }
    }

    private fun deleteFiles(taskId: String) {
        deleteImageFile(taskId)
        runCatching { metadataFile(taskId).delete() }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        temp.writeBytes(bytes)
        runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encodeTask(task: PendingTask): ByteArray =
        encodeBytes(json.encodeToString(PendingTask.serializer(), task).toByteArray(Charsets.UTF_8))

    private fun decodeTask(bytes: ByteArray): PendingTask =
        json.decodeFromString(PendingTask.serializer(), decodeBytes(bytes).toString(Charsets.UTF_8))

    private fun encodeBytes(value: ByteArray): ByteArray {
        val encrypted = cipher.encryptBytes(value)
        val iv = encrypted.initializationVector
        require(iv.size <= MAX_IV_LENGTH) { "IV too large" }
        return ByteArray(1 + iv.size + encrypted.ciphertext.size).also { out ->
            out[0] = iv.size.toByte()
            iv.copyInto(out, 1)
            encrypted.ciphertext.copyInto(out, 1 + iv.size)
        }
    }

    private fun decodeBytes(value: ByteArray): ByteArray {
        require(value.isNotEmpty()) { "Empty task file" }
        val ivLength = value[0].toInt()
        require(ivLength in 0..MAX_IV_LENGTH && value.size >= 1 + ivLength) { "Invalid task file" }
        val iv = value.copyOfRange(1, 1 + ivLength)
        val ciphertext = value.copyOfRange(1 + ivLength, value.size)
        return cipher.decryptBytes(EncryptedBytes(ciphertext, iv))
    }

    private fun readFile(file: File): ByteArray = file.readBytes()

    private fun metadataFile(taskId: String) = File(directory, taskId + METADATA_SUFFIX)

    private fun imageFile(taskId: String) = File(directory, taskId + IMAGE_SUFFIX)

    private fun PendingTask.hasExpiredImage(nowMillis: Long): Boolean =
        imageFileName != null &&
            status.isUnconfirmed &&
            nowMillis - createdAtMillis > imageRetentionMillis

    companion object {
        const val METADATA_SUFFIX = ".task"
        const val IMAGE_SUFFIX = ".jpg"
        const val DEFAULT_CAPACITY = 100
        const val DEFAULT_TERMINAL_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val DEFAULT_IMAGE_RETENTION_MILLIS = 24L * 60 * 60 * 1000
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_IV_LENGTH = 32
    }
}
