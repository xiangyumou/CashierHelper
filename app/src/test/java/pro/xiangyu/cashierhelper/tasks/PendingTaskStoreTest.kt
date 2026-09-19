package pro.xiangyu.cashierhelper.tasks

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.xiangyu.cashierhelper.config.BinarySecretCipher
import pro.xiangyu.cashierhelper.config.EncryptedBytes

@OptIn(ExperimentalCoroutinesApi::class)
class PendingTaskStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val jpeg = byteArrayOf(9, 8, 7, 6)

    @Test
    fun `saved task and image survive a reload`() = runTest {
        val store = newStore()
        val task = newTask("task-1")

        assertEquals(TaskSaveResult.Saved, store.saveNew(task, jpeg))

        val loaded = store.loadAndClean(now)
        assertEquals(listOf(task), loaded)
        assertArrayEquals(jpeg, store.readImage("task-1"))
        assertEquals(1, store.count())
        assertEquals(emptyList<String>(), tempFiles())
    }

    @Test
    fun `capacity blocks new tasks but keeps existing ones`() = runTest {
        val store = newStore(capacity = 2)
        assertEquals(TaskSaveResult.Saved, store.saveNew(newTask("a"), jpeg))
        assertEquals(TaskSaveResult.Saved, store.saveNew(newTask("b"), jpeg))

        assertEquals(TaskSaveResult.CapacityReached, store.saveNew(newTask("c"), jpeg))
        assertEquals(2, store.count())
    }

    @Test
    fun `terminal records are retained for seven days then cleaned`() = runTest {
        val store = newStore()
        store.saveNew(newTask("done", status = TaskStatus.COMPLETED, updatedAt = now), jpeg)

        assertEquals(1, store.loadAndClean(now + 6 * DAY).size)
        assertTrue(store.loadAndClean(now + 8 * DAY).isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun `unconfirmed tasks are never silently deleted`() = runTest {
        val store = newStore()
        store.saveNew(newTask("waiting", status = TaskStatus.SUBMIT_UNCONFIRMED), jpeg)

        val loaded = store.loadAndClean(now + 30 * DAY)

        assertEquals(1, loaded.size)
        assertEquals(TaskStatus.NEEDS_REVIEW, loaded.single().status)
    }

    @Test
    fun `expired private image becomes needs review and is deleted`() = runTest {
        val store = newStore()
        store.saveNew(newTask("waiting", status = TaskStatus.SUBMIT_UNCONFIRMED), jpeg)

        val loaded = store.loadAndClean(now + 25 * 60 * 60 * 1000)

        assertEquals(TaskStatus.NEEDS_REVIEW, loaded.single().status)
        assertNull(loaded.single().imageFileName)
        assertNull(store.readImage("waiting"))
        assertFalse(File(temporaryFolder.root, "waiting" + PendingTaskStore.IMAGE_SUFFIX).exists())
    }

    @Test
    fun `corrupt metadata is dropped instead of crashing`() = runTest {
        val store = newStore()
        store.saveNew(newTask("good"), jpeg)
        File(temporaryFolder.root, "broken" + PendingTaskStore.METADATA_SUFFIX)
            .writeText("not a task")

        val loaded = store.loadAndClean(now)

        assertEquals(listOf("good"), loaded.map { it.id })
        assertFalse(File(temporaryFolder.root, "broken" + PendingTaskStore.METADATA_SUFFIX).exists())
    }

    @Test
    fun `an unavailable cipher fails the save without writing metadata`() = runTest {
        val store = PendingTaskStore(
            directory = temporaryFolder.root,
            cipher = ThrowingCipher,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(TaskSaveResult.Failed, store.saveNew(newTask("a"), jpeg))
        assertTrue(store.loadAndClean(now).isEmpty())
    }

    @Test
    fun `delete removes both metadata and image`() = runTest {
        val store = newStore()
        store.saveNew(newTask("a"), jpeg)

        store.delete("a")

        assertTrue(store.loadAndClean(now).isEmpty())
        assertNull(store.readImage("a"))
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `metadata is written atomically with no temp files left behind`() = runTest {
        val store = newStore()
        store.saveNew(newTask("a"), jpeg)
        store.update(newTask("a").copy(status = TaskStatus.ACCEPTED, sourceDocumentId = "doc-1"))

        assertEquals(emptyList<String>(), tempFiles())
        assertEquals(TaskStatus.ACCEPTED, store.loadAndClean(now).single().status)
    }

    private fun tempFiles(): List<String> =
        temporaryFolder.root.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

    private fun TestScope.newStore(capacity: Int = PendingTaskStore.DEFAULT_CAPACITY) = PendingTaskStore(
        directory = temporaryFolder.root,
        cipher = FakeCipher,
        capacity = capacity,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun newTask(
        id: String,
        status: TaskStatus = TaskStatus.PENDING_UPLOAD,
        updatedAt: Long = now,
    ) = PendingTask(
        id = id,
        idempotencyKey = "key-" + id,
        configFingerprint = "fp",
        baseUrl = "https://cashier.example.com",
        createdAtMillis = updatedAt,
        updatedAtMillis = updatedAt,
        status = status,
        imageFileName = id + PendingTaskStore.IMAGE_SUFFIX,
    )

    private object FakeCipher : BinarySecretCipher {
        override fun encryptBytes(value: ByteArray) = EncryptedBytes(value, ByteArray(0))
        override fun decryptBytes(secret: EncryptedBytes) = secret.ciphertext
    }

    private object ThrowingCipher : BinarySecretCipher {
        override fun encryptBytes(value: ByteArray): EncryptedBytes = error("keystore unavailable")
        override fun decryptBytes(secret: EncryptedBytes): ByteArray = error("keystore unavailable")
    }

    private companion object {
        const val now = 1_700_000_000_000L
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
