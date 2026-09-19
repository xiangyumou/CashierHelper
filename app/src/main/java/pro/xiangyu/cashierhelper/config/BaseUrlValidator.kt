package pro.xiangyu.cashierhelper.config

import java.net.URI

object BaseUrlValidator {
    fun normalize(rawValue: String): Result<String> = runCatching {
        val trimmed = rawValue.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "请输入服务器地址" }

        val uri = URI(trimmed)
        require(uri.scheme.equals("https", ignoreCase = true)) { "服务器地址必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "服务器地址缺少有效域名" }
        require(uri.userInfo == null) { "服务器地址不能包含账号信息" }
        require(uri.query == null && uri.fragment == null) { "服务器地址不能包含查询参数或片段" }

        URI(
            "https",
            null,
            uri.host.lowercase(),
            uri.port,
            uri.path.takeUnless { it.isNullOrBlank() || it == "/" },
            null,
            null,
        ).toASCIIString().trimEnd('/')
    }
}

