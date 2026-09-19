package pro.xiangyu.cashierhelper.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import pro.xiangyu.cashierhelper.config.AppConfig
import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class CashierApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val queryTimeoutMillis: Long = DEFAULT_QUERY_TIMEOUT_MILLIS,
) : SourceDocumentUploader, SourceDocumentStatusClient {
    override suspend fun upload(config: AppConfig, jpegBytes: ByteArray): UploadResult =
        withContext(Dispatchers.IO) {
            val requestBody = buildJsonObject {
                put("images", buildJsonArray {
                    add(buildJsonObject {
                        put("data", Base64.getEncoder().encodeToString(jpegBytes))
                        put("mimeType", JPEG_MIME_TYPE)
                    })
                })
            }
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("${config.baseUrl}$SOURCE_DOCUMENT_PATH")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.code == 201 -> parseUploadResponse(body)
                        response.code == 401 || response.code == 403 ->
                            UploadResult.Failed(UploadFailure.UNAUTHORIZED)
                        response.code == 429 -> UploadResult.Failed(UploadFailure.RATE_LIMITED)
                        response.code >= 500 -> UploadResult.Failed(UploadFailure.SERVER_ERROR)
                        else -> UploadResult.Failed(UploadFailure.REJECTED)
                    }
                }
            } catch (_: IOException) {
                UploadResult.Failed(UploadFailure.NETWORK_ERROR)
            }
        }

    override suspend fun query(
        config: AppConfig,
        sourceDocumentId: String,
    ): StatusQueryResult {
        val encodedId = URLEncoder.encode(sourceDocumentId, Charsets.UTF_8.name()).replace("+", "%20")
        val request = Request.Builder()
            .url("${config.baseUrl}$SOURCE_DOCUMENT_PATH/$encodedId")
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        val call = client.newCall(request).apply {
            timeout().timeout(queryTimeoutMillis, TimeUnit.MILLISECONDS)
        }

        return try {
            call.await().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 200 -> parseStatusResponse(body)
                    response.code == 401 || response.code == 403 ->
                        queryFailure(StatusQueryFailure.UNAUTHORIZED)
                    response.code == 404 -> queryFailure(StatusQueryFailure.NOT_FOUND)
                    response.code == 429 -> queryFailure(StatusQueryFailure.RATE_LIMITED, true)
                    response.code >= 500 -> queryFailure(StatusQueryFailure.SERVER_ERROR, true)
                    else -> queryFailure(StatusQueryFailure.REJECTED)
                }
            }
        } catch (_: IOException) {
            queryFailure(StatusQueryFailure.NETWORK_ERROR, true)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            }
        })
    }

    private fun parseUploadResponse(body: String): UploadResult = runCatching {
        val json = Json.parseToJsonElement(body).jsonObject
        val id = json.string("sourceDocumentId")?.takeIf(String::isNotBlank)
        val state = json.string("revisionState")
        if (id != null && state == "processing") UploadResult.Accepted(id)
        else UploadResult.Failed(UploadFailure.INVALID_RESPONSE)
    }.getOrElse { UploadResult.Failed(UploadFailure.INVALID_RESPONSE) }

    private fun parseStatusResponse(body: String): StatusQueryResult = try {
        val json = Json.parseToJsonElement(body).jsonObject
        val status = when (json.string("status")) {
            "processing" -> SourceDocumentStatus.Processing
            "completed" -> SourceDocumentStatus.Completed(parseItems(json))
            "anomaly" -> SourceDocumentStatus.Anomaly(json.requireErrorCode())
            "failed" -> SourceDocumentStatus.Failed(json.requireErrorCode())
            "cancelled" -> SourceDocumentStatus.Cancelled
            else -> throw IllegalArgumentException("Missing or invalid status")
        }
        StatusQueryResult.Status(status)
    } catch (_: Exception) {
        queryFailure(StatusQueryFailure.INVALID_RESPONSE, responseBody = body)
    }

    private fun parseItems(root: JsonObject): List<ConsumptionItem> {
        val result = root["result"] as? JsonObject
            ?: throw IllegalArgumentException("Missing result")
        val array = result["entries"] as? JsonArray
            ?: throw IllegalArgumentException("Missing entries")
        return array.map { parseItem(it.jsonObject) }
    }

    private fun parseItem(item: JsonObject): ConsumptionItem {
        val amount = item.decimal("amount")
            ?: throw IllegalArgumentException("Missing item amount")
        return ConsumptionItem(
            amount,
            item.nullableString("currency"),
            item.nullableString("category"),
        )
    }

    private fun JsonObject.requireErrorCode(): String =
        ((get("error") as? JsonObject)?.string("code"))
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing error code")

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.nullableString(name: String): String? = when (val value = get(name)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Invalid $name")
        else -> throw IllegalArgumentException("Invalid $name")
    }

    private fun JsonObject.decimal(name: String): BigDecimal? =
        (get(name) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.toBigDecimalOrNull()

    private fun queryFailure(
        reason: StatusQueryFailure,
        retryable: Boolean = false,
        responseBody: String? = null,
    ) = StatusQueryResult.Failed(reason, retryable, responseBody)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val SOURCE_DOCUMENT_PATH = "/api/v1/source-documents"
        const val DEFAULT_QUERY_TIMEOUT_MILLIS = 5_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
