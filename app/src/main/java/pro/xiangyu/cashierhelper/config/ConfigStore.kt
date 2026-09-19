package pro.xiangyu.cashierhelper.config

interface ConfigStore {
    fun loadDraft(): ConfigDraft
    fun loadConfig(): AppConfig?
    fun save(baseUrl: String, apiKey: String): Result<AppConfig>
}

