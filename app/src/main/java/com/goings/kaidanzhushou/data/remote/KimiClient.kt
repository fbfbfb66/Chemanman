package com.goings.kaidanzhushou.data.remote

import com.goings.kaidanzhushou.BuildConfig
import com.goings.kaidanzhushou.domain.RecognitionDraft
import com.goings.kaidanzhushou.domain.PaymentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class KimiErrorKind { UNAUTHORIZED, QUOTA, RATE_LIMIT, SERVER, NETWORK, TIMEOUT, PERMANENT, INVALID_RESPONSE }

class KimiException(
    val kind: KimiErrorKind,
    message: String,
    val retryAfterMillis: Long? = null,
) : Exception(message)

class KimiClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val baseUrl: String = BuildConfig.KIMI_BASE_URL,
    private val requestTimeoutMillis: Long = 40_000,
    private val hedgeAfterMillis: Long = 12_000,
    maxConcurrentHedges: Int = 2,
) {
    private val hedgePermits = Semaphore(maxConcurrentHedges)

    /**
     * 识别请求呈双峰分布：正常约 10s 完成，卡住的那条再等也不会好。
     * 因此在 [hedgeAfterMillis] 内既没完成、也没开始吐字时，并发补发一个请求，谁先成功用谁，输的一方立刻取消。
     * 整个过程封在客户端内部：不改状态机、不加 attemptCount、不写 RETRY_WAIT，上层与用户无感。
     */
    suspend fun recognize(apiKey: String, image: File): RecognitionDraft = withContext(Dispatchers.IO) {
        val request = request(apiKey, image)
        coroutineScope {
            val streaming = CompletableDeferred<Unit>()
            val primary = async { attempt(request) { streaming.complete(Unit) } }
            // 首字节到达或请求已结束都说明无需对冲；只有静默超过窗口才补发。
            val stalled = withTimeoutOrNull(hedgeAfterMillis) {
                select {
                    primary.onAwait { false }
                    streaming.onAwait { false }
                }
            } ?: true
            // 名额耗尽时老实等原请求，避免大面积卡顿下在途请求翻倍。
            if (!stalled || !hedgePermits.tryAcquire()) return@coroutineScope primary.await().getOrThrow()
            try {
                val hedge = async { attempt(request) {} }
                val (settled, other) = select<Pair<Result<RecognitionDraft>, Deferred<Result<RecognitionDraft>>>> {
                    primary.onAwait { it to hedge }
                    hedge.onAwait { it to primary }
                }
                if (settled.isSuccess) settled.getOrThrow()
                else other.await().getOrElse { throw settled.exceptionOrNull()!! }
            } finally {
                hedgePermits.release()
            }
        }
    }

    private suspend fun attempt(request: Request, onStreamStart: () -> Unit): Result<RecognitionDraft> = try {
        Result.success(execute(request, onStreamStart))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: KimiException) {
        Result.failure(error)
    }

    private suspend fun execute(request: Request, onStreamStart: () -> Unit): RecognitionDraft {
        val call = http.newCall(request)
        call.timeout().timeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        // 落败方被取消时立刻断开 socket，不让它继续占用上行带宽。
        val cancelOnLoss = coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val safeMessage = when (response.code) {
                        401 -> "API Key 无效或已失效"
                        402 -> "Kimi 账户余额不足"
                        429 -> "请求过于频繁，稍后自动重试"
                        in 500..599 -> "Kimi 服务暂时不可用"
                        else -> "Kimi 请求参数错误（${response.code}）"
                    }
                    val bodyHint = response.body?.string().orEmpty().lowercase()
                    val kind = when {
                        response.code == 401 -> KimiErrorKind.UNAUTHORIZED
                        response.code == 402 || "balance" in bodyHint || "quota" in bodyHint || "余额" in bodyHint -> KimiErrorKind.QUOTA
                        response.code == 429 -> KimiErrorKind.RATE_LIMIT
                        response.code >= 500 -> KimiErrorKind.SERVER
                        else -> KimiErrorKind.PERMANENT
                    }
                    val retry = response.header("Retry-After")?.toDoubleOrNull()?.times(1000)?.toLong()
                    throw KimiException(kind, safeMessage, retry)
                }
                val body = response.body ?: throw KimiException(KimiErrorKind.INVALID_RESPONSE, "Kimi 返回空响应")
                val content = SseContentParser(json).parse(body.source(), onStreamStart)
                return parseDraft(content)
            }
        } catch (error: KimiException) {
            throw error
        } catch (_: InterruptedIOException) {
            throw KimiException(KimiErrorKind.TIMEOUT, "AI 识别超时，稍后自动重试")
        } catch (_: IOException) {
            throw KimiException(KimiErrorKind.NETWORK, "网络连接失败")
        } finally {
            cancelOnLoss.dispose()
        }
    }

    private fun request(apiKey: String, image: File): Request = Request.Builder()
        .url("${baseUrl}chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "text/event-stream")
        .post(requestBody(image).toString().toRequestBody(JSON_MEDIA))
        .build()

    internal fun parseDraft(content: String): RecognitionDraft = try {
        val objectValue = json.parseToJsonElement(content).jsonObject
        if (!REQUIRED_FIELDS.all(objectValue::containsKey)) {
            throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回字段不完整")
        }
        json.decodeFromString<RecognitionDraft>(content).also { draft ->
            if (draft.delivery_type != null && draft.delivery_type !in setOf("delivery", "pickup")) {
                throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回配送方式无效")
            }
            if (draft.payment_type != null && draft.payment_type !in PaymentType.codes) {
                throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回付款方式无效")
            }
        }
    } catch (error: KimiException) {
        throw error
    } catch (_: Exception) {
        throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回内容不符合数据格式")
    }

    private fun requestBody(image: File): JsonObject {
        val encoded = Base64.getEncoder().encodeToString(image.readBytes())
        return buildJsonObject {
            put("model", JsonPrimitive(MODEL))
            put("stream", JsonPrimitive(true))
            put("thinking", buildJsonObject { put("type", JsonPrimitive("disabled")) })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject { put("url", JsonPrimitive("data:image/jpeg;base64,$encoded")) })
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(PROMPT))
                        })
                    })
                })
            })
            put("response_format", buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("json_schema", buildJsonObject {
                    put("name", JsonPrimitive("waybill_record_v1_1"))
                    put("strict", JsonPrimitive(true))
                    put("schema", schema())
                })
            })
        }
    }

    private fun schema(): JsonObject {
        fun nullable(type: String) = JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null")))
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("additionalProperties", JsonPrimitive(false))
            put("properties", buildJsonObject {
                listOf("destination_text", "sender_name", "receiver_name", "receiver_mobile", "goods_name", "package").forEach {
                    put(it, buildJsonObject { put("type", nullable("string")) })
                }
                put("delivery_type", buildJsonObject {
                    put("type", nullable("string"))
                    put("enum", JsonArray(listOf(JsonPrimitive("delivery"), JsonPrimitive("pickup"), JsonNull)))
                })
                put("payment_type", buildJsonObject {
                    put("type", nullable("string"))
                    put("enum", JsonArray(listOf(JsonPrimitive("pay_billing"), JsonPrimitive("pay_arrival"), JsonPrimitive("pay_receipt"), JsonNull)))
                })
                put("quantity", buildJsonObject { put("type", nullable("integer")); put("minimum", JsonPrimitive(1)) })
                listOf("weight", "volume", "freight").forEach {
                    put(it, buildJsonObject { put("type", nullable("number")); put("minimum", JsonPrimitive(0)) })
                }
            })
            put("required", JsonArray(listOf(
                "destination_text", "delivery_type", "sender_name", "receiver_name", "receiver_mobile",
                "goods_name", "package", "quantity", "weight", "volume", "freight", "payment_type",
            ).map(::JsonPrimitive)))
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MODEL = "kimi-k2.6"
        val REQUIRED_FIELDS = setOf(
            "destination_text", "delivery_type", "sender_name", "receiver_name", "receiver_mobile",
            "goods_name", "package", "quantity", "weight", "volume", "freight", "payment_type",
        )
        const val PROMPT = """你是托运单录入助手。只提取图片中明确可见的信息，禁止推测、补全或编造。无法确认的字段必须返回 null。delivery_type 只能是 delivery（送货）或 pickup（自提）。付款方式严格映射：单据写“现付”返回 payment_type=pay_billing；写“提付”或“到付”返回 pay_arrival；写“回付”返回 pay_receipt；看不清则返回 null。quantity 为件数；weight、volume、freight 仅返回数字。发货地点永远是昆明，到站不可能是昆明，禁止将昆明填入 destination_text。若单据上显示两个到站，以被打勾标注的到站为准。单据中的“收货方”就是收货人，填入 receiver_name。"""
    }
}
