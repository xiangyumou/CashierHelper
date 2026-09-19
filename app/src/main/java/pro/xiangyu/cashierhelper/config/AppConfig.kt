package pro.xiangyu.cashierhelper.config

data class AppConfig(
    val baseUrl: String,
    val apiKey: String,
)

data class ConfigDraft(
    val baseUrl: String = "",
    val apiKey: String = "",
)

