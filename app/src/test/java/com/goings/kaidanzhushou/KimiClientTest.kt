package com.goings.kaidanzhushou

import com.goings.kaidanzhushou.data.remote.KimiClient
import com.goings.kaidanzhushou.data.remote.KimiErrorKind
import com.goings.kaidanzhushou.data.remote.KimiException
import com.goings.kaidanzhushou.data.remote.SseContentParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class KimiClientTest {
    private val payload = """{"destination_text":"杭州","delivery_type":"delivery","sender_name":"张三","receiver_name":"李四","receiver_mobile":null,"goods_name":"配件","package":null,"quantity":2,"weight":12.5,"volume":null,"freight":8,"payment_type":"pay_arrival"}"""

    @Test fun parsesMultiChunkSse() {
        val first = payload.substring(0, 80)
        val second = payload.substring(80)
        val json = Json
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":${json.encodeToString(first)}}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":${json.encodeToString(second)}}}]}\n\n" + "data: [DONE]\n\n"
        assertEquals(payload, SseContentParser().parse(Buffer().writeUtf8(sse)))
    }

    @Test fun mockServerStreamsStructuredDraft() {
        runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val chunk = "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(payload)}}}]}\n\ndata: [DONE]\n\n"
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(chunk))
            val file = File.createTempFile("waybill", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val client = KimiClient(OkHttpClient(), baseUrl = server.url("/v1/").toString())
            val result = client.recognize("test-key", file)
            assertEquals("杭州", result.destination_text)
            assertEquals(2, result.quantity)
            assertEquals("pay_arrival", result.payment_type)
            val request = server.takeRequest()
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val requestJson = request.body.readUtf8()
            assertTrue(requestJson.contains("kimi-k2.6"))
            assertTrue(requestJson.contains("\"thinking\":{\"type\":\"disabled\"}"))
            assertTrue(requestJson.contains("pay_arrival"))
            assertTrue(requestJson.contains("pay_receipt"))
            assertTrue(requestJson.contains("发货地点永远是昆明，到站不可能是昆明"))
            assertTrue(requestJson.contains("若单据上显示两个到站，以被打勾标注的到站为准"))
            assertTrue(requestJson.contains("“收货方”就是收货人"))
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun missingFieldsAreRejected() {
        val client = KimiClient(OkHttpClient(), baseUrl = "http://localhost/")
        val error = runCatching { client.parseDraft("{\"destination_text\":\"杭州\"}") }.exceptionOrNull() as KimiException
        assertEquals(KimiErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test fun paymentTypesAreStrictAndMayBeNull() {
        val client = KimiClient(OkHttpClient(), baseUrl = "http://localhost/")
        listOf("pay_billing", "pay_arrival", "pay_receipt").forEach { payment ->
            assertEquals(payment, client.parseDraft(payload.replace("pay_arrival", payment)).payment_type)
        }
        val invalid = runCatching { client.parseDraft(payload.replace("pay_arrival", "cash")) }.exceptionOrNull() as KimiException
        assertEquals(KimiErrorKind.INVALID_RESPONSE, invalid.kind)
        assertEquals(null, client.parseDraft(payload.replace("\"pay_arrival\"", "null")).payment_type)
    }

    @Test fun rateLimitCarriesRetryAfter() {
        runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("{}"))
            val file = File.createTempFile("waybill", ".jpg")
            val client = KimiClient(OkHttpClient(), baseUrl = server.url("/").toString())
            val error = runCatching { client.recognize("test", file) }.exceptionOrNull() as KimiException
            assertEquals(KimiErrorKind.RATE_LIMIT, error.kind)
            assertEquals(2000L, error.retryAfterMillis)
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun timeoutIsReportedOnceAndLeftToTheWorkerToRetry() {
        runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val chunk = "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(payload)}}}]}\n\ndata: [DONE]\n\n"
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody(chunk).setBodyDelay(5, TimeUnit.SECONDS)) }
            val file = File.createTempFile("waybill", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val client = KimiClient(
                OkHttpClient(),
                baseUrl = server.url("/v1/").toString(),
                requestTimeoutMillis = 300,
                hedgeAfterMillis = 100,
            )

            val error = runCatching { client.recognize("test", file) }.exceptionOrNull() as KimiException
            assertEquals(KimiErrorKind.TIMEOUT, error.kind)
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun stalledRequestIsHedgedAndTheFasterCopyWins() {
        runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val chunk = "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(payload)}}}]}\n\ndata: [DONE]\n\n"
            server.enqueue(MockResponse().setResponseCode(200).setBody(chunk).setBodyDelay(4, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setResponseCode(200).setBody(chunk))
            val file = File.createTempFile("waybill", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val client = KimiClient(
                OkHttpClient(),
                baseUrl = server.url("/v1/").toString(),
                requestTimeoutMillis = 30_000,
                hedgeAfterMillis = 200,
            )

            assertEquals("杭州", client.recognize("test", file).destination_text)
            assertEquals(2, server.requestCount)
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun healthyRequestIsNotHedged() {
        runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val chunk = "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(payload)}}}]}\n\ndata: [DONE]\n\n"
            server.enqueue(MockResponse().setResponseCode(200).setBody(chunk))
            val file = File.createTempFile("waybill", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val client = KimiClient(OkHttpClient(), baseUrl = server.url("/v1/").toString(), hedgeAfterMillis = 200)

            assertEquals("杭州", client.recognize("test", file).destination_text)
            assertEquals(1, server.requestCount)
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun streamingResponseSuppressesHedgeEvenWhenSlowToFinish() {
        runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val head = payload.substring(0, 20)
            val tail = payload.substring(20)
            // 首个分片很快到达，之后是一长串空 content 事件，整流直到远超对冲窗口才结束。
            // 节流是双向的，2KB/250ms 让请求体上传只占一两个周期，慢的是响应而不是上传。
            val filler = "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n\n".repeat(800)
            val sse = "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(head)}}}]}\n\n" + filler +
                "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(tail)}}}]}\n\n" + "data: [DONE]\n\n"
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(sse)
                    .throttleBody(2048, 250, TimeUnit.MILLISECONDS)
            )
            val file = File.createTempFile("waybill", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val client = KimiClient(
                OkHttpClient(),
                baseUrl = server.url("/v1/").toString(),
                requestTimeoutMillis = 30_000,
                hedgeAfterMillis = 2_500,
            )

            assertEquals("杭州", client.recognize("test", file).destination_text)
            assertEquals(1, server.requestCount)
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun fastPermanentFailureIsNotHedged() {
        runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val file = File.createTempFile("waybill", ".jpg")
            val client = KimiClient(OkHttpClient(), baseUrl = server.url("/").toString(), hedgeAfterMillis = 200)
            val error = runCatching { client.recognize("test", file) }.exceptionOrNull() as KimiException
            assertEquals(KimiErrorKind.UNAUTHORIZED, error.kind)
            assertEquals(1, server.requestCount)
            file.delete()
        } finally { server.shutdown() }
        }
    }

    @Test fun firstContentChunkIsSignalledOnce() {
        val json = Json
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":${json.encodeToString("甲")}}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":${json.encodeToString("乙")}}}]}\n\n" + "data: [DONE]\n\n"
        var signals = 0
        assertEquals("甲乙", SseContentParser().parse(Buffer().writeUtf8(sse)) { signals++ })
        assertEquals(1, signals)
    }

    @Test fun classifiesPermanentQuotaServerAndNetworkFailures() {
        listOf(
            MockResponse().setResponseCode(401) to KimiErrorKind.UNAUTHORIZED,
            MockResponse().setResponseCode(402) to KimiErrorKind.QUOTA,
            MockResponse().setResponseCode(400).setBody("quota exceeded") to KimiErrorKind.QUOTA,
            MockResponse().setResponseCode(503) to KimiErrorKind.SERVER,
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) to KimiErrorKind.NETWORK,
        ).forEach { (response, expected) ->
            val server = MockWebServer(); server.start()
            try {
                server.enqueue(response)
                val file = File.createTempFile("waybill", ".jpg")
                val client = KimiClient(OkHttpClient(), baseUrl = server.url("/").toString())
                val error = runBlocking { runCatching { client.recognize("test", file) }.exceptionOrNull() } as KimiException
                assertEquals(expected, error.kind)
                file.delete()
            } finally { server.shutdown() }
        }
    }

    @Test fun malformedJsonStreamIsRejected() {
        val server = MockWebServer(); server.start()
        try {
            val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"not-json\"}}]}\n\ndata: [DONE]\n\n"
            server.enqueue(MockResponse().setResponseCode(200).setBody(sse))
            val file = File.createTempFile("waybill", ".jpg")
            val error = runBlocking {
                runCatching { KimiClient(OkHttpClient(), baseUrl = server.url("/").toString()).recognize("test", file) }.exceptionOrNull()
            } as KimiException
            assertEquals(KimiErrorKind.INVALID_RESPONSE, error.kind)
            file.delete()
        } finally { server.shutdown() }
    }
}
