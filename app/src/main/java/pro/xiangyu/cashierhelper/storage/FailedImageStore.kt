package pro.xiangyu.cashierhelper.storage

interface FailedImageStore {
    suspend fun save(jpegBytes: ByteArray): String?
}

