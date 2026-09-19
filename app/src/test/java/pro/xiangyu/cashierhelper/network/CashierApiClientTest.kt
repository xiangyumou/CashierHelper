package pro.xiangyu.cashierhelper.network

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.xiangyu.cashierhelper.config.AppConfig

class CashierApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends only minimal cashier payload and accepts processing response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"sourceDocumentId":"a","revisionState":"processing","status":"processing"}"""),
        )
        val jpeg = byteArrayOf(1, 2, 3, 4)

        val result = CashierApiClient().upload(config(), jpeg)

        assertEquals(UploadResult.Accepted("a"), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/source-documents", request.path)
        assertEquals("Bearer secret-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val idempotencyKey = request.getHeader("Idempotency-Key")
        assertTrue(!idempotencyKey.isNullOrBlank())
        assertEquals(idempotencyKey, java.util.UUID.fromString(idempotencyKey).toString())

        val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("images"), json.keys)
        val image = json.getValue("images").jsonArray.single().jsonObject
        assertEquals(setOf("data", "mimeType"), image.keys)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), image.getValue("data").jsonPrimitive.content)
        assertEquals("image/jpeg", image.getValue("mimeType").jsonPrimitive.content)
        assertFalse("entryDate" in json)
    }

    @Test
    fun `requires exact success contract`() = runBlocking {
        listOf(
            """{"revisionState":"processing"}""",
            """{"sourceDocumentId":"a","revisionState":"queued"}""",
            "not-json",
        ).forEach { body ->
            server.enqueue(MockResponse().setResponseCode(201).setBody(body))
            assertEquals(
                UploadResult.Failed(UploadFailure.INVALID_RESPONSE),
                CashierApiClient().upload(config(), byteArrayOf(1)),
            )
        }
    }

    @Test
    fun `parses all source document states and completed items`() = runBlocking {
        val responses = listOf(
            """{"status":"processing","result":null,"error":null}""" to
                StatusQueryResult.Status(SourceDocumentStatus.Processing),
            """{"status":"completed","result":{"title":"午餐","total":"10.20","entries":[{"name":"面条","description":null,"amount":"10.20","currency":"CNY","category":"餐饮"}]},"error":null}""" to
                StatusQueryResult.Status(
                    SourceDocumentStatus.Completed(
                        listOf(ConsumptionItem("10.20".toBigDecimal(), "CNY", "餐饮")),
                    ),
                ),
            """{"status":"anomaly","result":null,"error":{"code":"A01"}}""" to
                StatusQueryResult.Status(SourceDocumentStatus.Anomaly("A01")),
            """{"status":"failed","result":null,"error":{"code":"F01"}}""" to
                StatusQueryResult.Status(SourceDocumentStatus.Failed("F01")),
            """{"status":"cancelled","result":null,"error":null}""" to
                StatusQueryResult.Status(SourceDocumentStatus.Cancelled),
        )

        responses.forEach { (body, expected) ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            assertEquals(expected, CashierApiClient().query(config(), "doc/id"))
        }
        repeat(responses.size) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/source-documents/doc%2Fid", request.path)
            assertEquals("Bearer secret-key", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `maps status query failures and retryability`() = runBlocking {
        listOf(
            401 to StatusQueryResult.Failed(StatusQueryFailure.UNAUTHORIZED, false),
            404 to StatusQueryResult.Failed(StatusQueryFailure.NOT_FOUND, false),
            429 to StatusQueryResult.Failed(StatusQueryFailure.RATE_LIMITED, true),
            503 to StatusQueryResult.Failed(StatusQueryFailure.SERVER_ERROR, true),
            422 to StatusQueryResult.Failed(StatusQueryFailure.REJECTED, false),
            200 to StatusQueryResult.Failed(StatusQueryFailure.INVALID_RESPONSE, false, "{}"),
        ).forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            assertEquals(expected, CashierApiClient().query(config(), "a"))
        }
    }

    @Test
    fun `parses completed response with empty entries`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"completed","result":{"title":null,"total":"0.00","entries":[]},"error":null}"""),
        )

        assertEquals(
            StatusQueryResult.Status(SourceDocumentStatus.Completed(emptyList())),
            CashierApiClient().query(config(), "a"),
        )
    }

    @Test
    fun `rejects malformed status responses`() = runBlocking {
        listOf(
            """{}""",
            """{"revisionState":"processing"}""",
            """{"status":"queued"}""",
            """{"status":"completed","result":null}""",
            """{"status":"completed","result":"invalid"}""",
            """{"status":"completed","result":{}}""",
            """{"status":"completed","result":{"entries":"invalid"}}""",
            """{"status":"completed","result":{"entries":[{"amount":"invalid","currency":"CNY","category":"餐饮"}]}}""",
            """{"status":"completed","result":{"entries":[{"amount":10.20,"currency":"CNY","category":"餐饮"}]}}""",
            """{"status":"completed","result":{"entries":[{"amount":"10.20","currency":156,"category":"餐饮"}]}}""",
            """{"status":"completed","result":{"entries":[{"amount":"10.20","currency":"CNY","category":{"name":"餐饮"}}]}}""",
            """{"status":"anomaly","error":null}""",
            """{"status":"anomaly","error":{"code":""}}""",
            """{"status":"anomaly","error":{"code":101}}""",
            """{"status":"failed","error":{"message":"failed"}}""",
            """{"status":"failed","errorCode":"F01"}""",
        ).forEach { body ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            assertEquals(
                StatusQueryResult.Failed(StatusQueryFailure.INVALID_RESPONSE, false, body),
                CashierApiClient().query(config(), "a"),
            )
        }
    }

    @Test
    fun `maps authentication rate limit and server errors`() = runBlocking {
        listOf(
            401 to UploadFailure.UNAUTHORIZED,
            429 to UploadFailure.RATE_LIMITED,
            503 to UploadFailure.SERVER_ERROR,
            422 to UploadFailure.REJECTED,
        ).forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status))
            assertEquals(
                UploadResult.Failed(expected),
                CashierApiClient().upload(config(), byteArrayOf(1)),
            )
        }
    }

    @Test
    fun `does not retry a timed out upload`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutClient = OkHttpClient.Builder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        assertEquals(
            UploadResult.Failed(UploadFailure.NETWORK_ERROR),
            CashierApiClient(shortTimeoutClient).upload(config(), byteArrayOf(1)),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `status query times out as retryable network error`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(
            StatusQueryResult.Failed(StatusQueryFailure.NETWORK_ERROR, true),
            CashierApiClient(queryTimeoutMillis = 100).query(config(), "a"),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling status query cancels underlying call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CashierApiClient(queryTimeoutMillis = 30_000).query(config(), "a")
        }
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)

        withTimeout(1_000) {
            job.cancelAndJoin()
        }
    }

    private fun config() = AppConfig(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = "secret-key",
    )
}
