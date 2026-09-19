package pro.xiangyu.cashierhelper.network

import java.io.IOException
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import pro.xiangyu.cashierhelper.config.ApiKeyValidator
import pro.xiangyu.cashierhelper.config.AppConfig
import pro.xiangyu.cashierhelper.config.BaseUrlValidator

/**
 * Talks to the Cashier v1 endpoints.
 *
 * Every request is executed on a background thread and the response body is
 * read, parsed and closed off the caller's thread. Coroutine cancellation
 * cancels the underlying [Call] for the whole request, including the response
 * body read, so a cancelled or timed-out caller never keeps a connection open.
 */
class CashierApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val queryTimeoutMillis: Long = DEFAULT_QUERY_TIMEOUT_MILLIS,
    private val uploadTimeoutMillis: Long = DEFAULT_UPLOAD_TIMEOUT_MILLIS,
    private val executor: Executor = defaultExecutor(),
) : CashierApi {

    override suspend fun upload(
        config: AppConfig,
        jpegBytes: ByteArray,
        idempotencyKey: String,
    ): UploadResult {
        val request = try {
            uploadRequest(config, jpegBytes, idempotencyKey)
        } catch (_: IllegalArgumentException) {
            return UploadResult.Failed(UploadFailure.INVALID_CONFIGURATION)
        }

        return try {
            runCall(request, uploadTimeoutMillis) { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 201 -> parseUploadResponse(body)
                    response.code == 401 || response.code == 403 ->
                        UploadResult.Failed(UploadFailure.UNAUTHORIZED)
                    response.code == 429 -> UploadResult.Failed(
                        UploadFailure.RATE_LIMITED,
                        RequestCoordinator.parseRetryAfter(response.header("Retry-After")),
                    )
                    response.code >= 500 -> UploadResult.Failed(UploadFailure.SERVER_ERROR)
                    else -> UploadResult.Failed(UploadFailure.REJECTED)
                }
            }
        } catch (_: IOException) {
            UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        } catch (_: Exception) {
            UploadResult.Failed(UploadFailure.NETWORK_ERROR)
        }
    }

    override suspend fun query(
        config: AppConfig,
        sourceDocumentId: String,
    ): StatusQueryResult {
        val request = try {
            queryRequest(config, sourceDocumentId)
        } catch (_: IllegalArgumentException) {
            return queryFailure(StatusQueryFailure.INVALID_CONFIGURATION, retryable = false)
        }

        return try {
            runCall(request, queryTimeoutMillis) { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 200 -> parseStatusResponse(body)
                    response.code == 401 || response.code == 403 ->
                        queryFailure(StatusQueryFailure.UNAUTHORIZED)
                    response.code == 404 -> queryFailure(StatusQueryFailure.NOT_FOUND)
                    response.code == 429 -> queryFailure(
                        StatusQueryFailure.RATE_LIMITED,
                        retryable = true,
                        retryAfterMillis = RequestCoordinator.parseRetryAfter(
                            response.header("Retry-After"),
                        ),
                    )
                    response.code >= 500 -> queryFailure(StatusQueryFailure.SERVER_ERROR, true)
                    else -> queryFailure(StatusQueryFailure.REJECTED)
                }
            }
        } catch (_: IOException) {
            queryFailure(StatusQueryFailure.NETWORK_ERROR, true)
        } catch (_: Exception) {
            queryFailure(StatusQueryFailure.NETWORK_ERROR, true)
        }
    }

    private fun uploadRequest(
        config: AppConfig,
        jpegBytes: ByteArray,
        idempotencyKey: String,
    ): Request {
        val key = normalizedKey(config)
        val url = endpoint(config, SOURCE_DOCUMENT_PATH)
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

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("Idempotency-Key", idempotencyKey)
            .post(requestBody)
            .build()
    }

    private fun queryRequest(config: AppConfig, sourceDocumentId: String): Request {
        val key = normalizedKey(config)
        val encodedId = URLEncoder.encode(sourceDocumentId, Charsets.UTF_8.name()).replace("+", "%20")
        val url = endpoint(config, SOURCE_DOCUMENT_PATH + "/" + encodedId)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + key)
            .get()
            .build()
    }

    private fun normalizedKey(config: AppConfig): String =
        ApiKeyValidator.normalize(config.apiKey).getOrElse {
            throw IllegalArgumentException("Invalid API key")
        }

    private fun endpoint(config: AppConfig, path: String): HttpUrl {
        val base = BaseUrlValidator.normalize(config.baseUrl).getOrElse {
            throw IllegalArgumentException("Invalid base url")
        }
        return (base + path).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid endpoint")
    }

    private suspend fun <T> runCall(
        request: Request,
        timeoutMillis: Long,
        parse: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        executor.execute {
            try {
                val result = call.execute().use { response -> parse(response) }
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
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
            "invalid" -> SourceDocumentStatus.Invalid(json.requireErrorCode(), json.errorMessage())
            "anomaly" -> SourceDocumentStatus.Anomaly(json.requireErrorCode())
            "failed" -> SourceDocumentStatus.Failed(json.requireErrorCode())
            "cancelled" -> SourceDocumentStatus.Cancelled
            else -> throw IllegalArgumentException("Missing or invalid status")
        }
        StatusQueryResult.Status(status)
    } catch (_: Exception) {
        queryFailure(StatusQueryFailure.INVALID_RESPONSE)
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

    private fun JsonObject.errorMessage(): String? =
        ((get("error") as? JsonObject)?.string("message"))?.takeIf(String::isNotBlank)

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.nullableString(name: String): String? = when (val value = get(name)) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Invalid " + name)
        else -> throw IllegalArgumentException("Invalid " + name)
    }

    private fun JsonObject.decimal(name: String): BigDecimal? =
        (get(name) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.toBigDecimalOrNull()

    private fun queryFailure(
        reason: StatusQueryFailure,
        retryable: Boolean = false,
        retryAfterMillis: Long? = null,
    ) = StatusQueryResult.Failed(reason, retryable, retryAfterMillis)

    companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val SOURCE_DOCUMENT_PATH = "/api/v1/source-documents"
        const val DEFAULT_QUERY_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_UPLOAD_TIMEOUT_MILLIS = 150_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        fun defaultExecutor(): Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "cashier-api").apply { isDaemon = true }
        }
    }
}
