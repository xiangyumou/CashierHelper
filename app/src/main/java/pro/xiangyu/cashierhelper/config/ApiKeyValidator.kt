package pro.xiangyu.cashierhelper.config

/**
 * Validates the Cashier service credential before it is stored or used. The
 * credential travels in an HTTP header, so anything other than printable
 * ASCII without spaces (or control characters) is rejected early instead of
 * producing a silently broken request.
 */
object ApiKeyValidator {
    fun normalize(rawValue: String): Result<String> = runCatching {
        val trimmed = rawValue.trim()
        require(trimmed.isNotEmpty()) { "请输入 API Key" }
        require(trimmed.all { it.code in MIN_PRINTABLE_ASCII..MAX_PRINTABLE_ASCII }) {
            "API Key 只能包含可打印 ASCII 字符，且不能包含空格"
        }
        trimmed
    }

    private const val MIN_PRINTABLE_ASCII = 0x21
    private const val MAX_PRINTABLE_ASCII = 0x7E
}
