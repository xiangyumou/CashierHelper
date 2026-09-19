package pro.xiangyu.cashierhelper.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object BaseUrlValidator {
    fun normalize(rawValue: String): Result<String> = runCatching {
        val trimmed = rawValue.trim()
        require(trimmed.isNotEmpty()) { "请输入服务器地址" }

        // Use OkHttp's parser so validation matches the exact URL that the
        // request layer will resolve, instead of Java's more permissive URI.
        val url = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务器地址格式无效")
        require(url.isHttps) { "服务器地址必须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "服务器地址不能包含账号信息"
        }
        require(url.query == null) { "服务器地址不能包含查询参数" }
        require(url.fragment == null) { "服务器地址不能包含片段" }

        // HttpUrl already lowercases the host, drops an explicit default port
        // and keeps a legal base path. Only the trailing slash is trimmed so
        // path concatenation stays predictable.
        url.toString().trimEnd('/')
    }
}
