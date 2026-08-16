package com.goings.kaidanzhushou.data.remote

import com.goings.kaidanzhushou.BuildConfig
import com.goings.kaidanzhushou.domain.DestinationDictionary
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
    // 每次识别时取一次快照；destination_canonical 的 enum 从这里注入。空字典时 enum 只有 null，诚实降级。
    private val dictionary: () -> DestinationDictionary = { DestinationDictionary.EMPTY },
) {
    private val hedgePermits = Semaphore(maxConcurrentHedges)

    /**
     * 识别请求呈双峰分布：正常约 10s 完成，卡住的那条再等也不会好。
     * 因此在 [hedgeAfterMillis] 内既没完成、也没开始吐字时，并发补发一个请求，谁先成功用谁，输的一方立刻取消。
     * 整个过程封在客户端内部：不改状态机、不加 attemptCount、不写 RETRY_WAIT，上层与用户无感。
     */
    suspend fun recognize(apiKey: String, image: File): RecognitionDraft = withContext(Dispatchers.IO) {
        // 快照一次，保证对冲的两发请求用同一份字典。
        val dict = dictionary()
        val request = request(apiKey, image, dict)
        coroutineScope {
            val streaming = CompletableDeferred<Unit>()
            val primary = async { attempt(request, dict) { streaming.complete(Unit) } }
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
                val hedge = async { attempt(request, dict) {} }
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

    private suspend fun attempt(request: Request, dict: DestinationDictionary, onStreamStart: () -> Unit): Result<RecognitionDraft> = try {
        Result.success(execute(request, dict, onStreamStart))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: KimiException) {
        Result.failure(error)
    }

    private suspend fun execute(request: Request, dict: DestinationDictionary, onStreamStart: () -> Unit): RecognitionDraft {
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
                return parseDraft(content, dict)
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

    private fun request(apiKey: String, image: File, dict: DestinationDictionary): Request = Request.Builder()
        .url("${baseUrl}chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "text/event-stream")
        .post(requestBody(image, dict).toString().toRequestBody(JSON_MEDIA))
        .build()

    internal fun parseDraft(content: String, dict: DestinationDictionary = dictionary()): RecognitionDraft = try {
        val objectValue = json.parseToJsonElement(content).jsonObject
        if (!REQUIRED_FIELDS.all(objectValue::containsKey)) {
            throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回字段不完整")
        }
        json.decodeFromString<RecognitionDraft>(content).let { draft ->
            if (draft.delivery_type != null && draft.delivery_type !in setOf("delivery", "pickup")) {
                throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回配送方式无效")
            }
            if (draft.payment_type != null && draft.payment_type !in PaymentType.codes) {
                throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回付款方式无效")
            }
            // canonical 不在字典 → 降级为 null 而不是抛异常：字典是用户可更新的，可能与请求时的版本漂移，
            // 抛 INVALID_RESPONSE 会触发无意义的整轮重试（异于 payment_type 这种我们自有的闭集）。
            if (draft.destination_canonical != null && draft.destination_canonical !in dict.enumNames) {
                draft.copy(destination_canonical = null)
            } else draft
        }
    } catch (error: KimiException) {
        throw error
    } catch (_: Exception) {
        throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回内容不符合数据格式")
    }

    private fun requestBody(image: File, dict: DestinationDictionary): JsonObject {
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
                            put("text", JsonPrimitive(prompt(dict)))
                        })
                    })
                })
            })
            put("response_format", buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("json_schema", buildJsonObject {
                    put("name", JsonPrimitive("waybill_record_v1_3"))
                    put("strict", JsonPrimitive(true))
                    put("schema", schema(dict))
                })
            })
        }
    }

    private fun schema(dict: DestinationDictionary): JsonObject {
        fun nullable(type: String) = JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null")))
        fun nullableEnum(values: List<String>) = buildJsonObject {
            put("type", nullable("string"))
            put("enum", JsonArray(values.map(::JsonPrimitive) + JsonNull))
        }
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("additionalProperties", JsonPrimitive(false))
            put("properties", buildJsonObject {
                listOf("sender_name", "receiver_name", "receiver_mobile", "goods_name", "package").forEach {
                    put(it, buildJsonObject { put("type", nullable("string")) })
                }
                put("destination_raw_tokens", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("maxItems", JsonPrimitive(8))
                })
                put("destination_checked_token", buildJsonObject { put("type", nullable("string")) })
                put("destination_mark_type", nullableEnum(listOf("check", "circle", "underline")))
                put("destination_layout", nullableEnum(listOf("checklist", "handwritten")))
                // enum 硬约束：模型在解码层面就不可能输出「玉溪通海」这类字典外的词。
                put("destination_canonical", nullableEnum(dict.enumNames))
                put("delivery_type", buildJsonObject {
                    put("type", nullable("string"))
                    put("enum", JsonArray(listOf(JsonPrimitive("delivery"), JsonPrimitive("pickup"), JsonNull)))
                })
                put("payment_type", buildJsonObject {
                    put("type", nullable("string"))
                    put("enum", JsonArray(listOf(JsonPrimitive("pay_billing"), JsonPrimitive("pay_arrival"), JsonPrimitive("pay_receipt"), JsonNull)))
                })
                put("quantity", buildJsonObject { put("type", nullable("integer")); put("minimum", JsonPrimitive(1)) })
                listOf("weight", "volume", "freight_fee", "advance_payment", "total_freight").forEach {
                    put(it, buildJsonObject { put("type", nullable("number")); put("minimum", JsonPrimitive(0)) })
                }
            })
            put("required", JsonArray(REQUIRED_FIELDS.map(::JsonPrimitive)))
        }
    }

    private fun prompt(dict: DestinationDictionary): String {
        val stationList = dict.enumNames.joinToString("\n") { name ->
            val station = dict.stations.firstOrNull { it.name == name && !it.excluded }
            val prefix = station?.full_name?.removeSuffix(name).orEmpty()
            if (prefix.isBlank()) name else "$name（$prefix）"
        }
        return buildString {
            append(PROMPT_BASE)
            if (stationList.isNotBlank()) {
                append("\n可选的标准到站列表（destination_canonical 只能从中选择）：\n")
                append(stationList)
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MODEL = "kimi-k2.6"
        val REQUIRED_FIELDS = setOf(
            "destination_raw_tokens", "destination_checked_token", "destination_mark_type",
            "destination_layout", "destination_canonical",
            "delivery_type", "sender_name", "receiver_name", "receiver_mobile",
            "goods_name", "package", "quantity", "weight", "volume",
            "freight_fee", "advance_payment", "total_freight", "payment_type",
        )
        const val PROMPT_BASE = """你是托运单录入助手。只提取图片中明确可见的信息，禁止推测、补全或编造。无法确认的字段必须返回 null。宁可返回 null 也不要猜：填进去的字段会被当成可信数据直接用，猜错一个字段给人工核对带来的负担远大于留空。凡是字迹模糊、被遮挡、栏位含义拿不准、需要靠常识补全的，一律返回 null。delivery_type 只能是 delivery（送货）或 pickup（自提）。付款方式严格映射：单据写“现付”返回 payment_type=pay_billing；写“提付”或“到付”返回 pay_arrival；写“回付”返回 pay_receipt；看不清则返回 null。quantity 为件数；weight、volume、freight_fee、advance_payment、total_freight 仅返回数字。单据中的“收货方”就是收货人，填入 receiver_name。
费用栏常见三个数：运费、垫付款、总运费。不同单据叫法不同，按语义对应——垫付款也可能写作垫付、代垫、垫付费、垫付运费；总运费也可能写作合计运费、运费合计、运费总计、总计。这三个字段全都只做“照抄”：freight_fee 只抄“运费”那一栏的数字，advance_payment 只抄“垫付款”那一栏的数字，total_freight 只抄“总运费”那一栏的数字。禁止心算、禁止把两栏相加、禁止用其中两栏去推第三栏；哪一栏不存在、空白或看不清，对应字段就返回 null，加总与核对全部由软件完成。几乎每张单都有运费，很多单据只写运费这一个数：这种情况把它填进 freight_fee，advance_payment 和 total_freight 一律返回 null，软件会认定总运费等于运费。advance_payment 只在单据上真的写着“垫付款/垫付/代垫/垫付费”字样的那一栏里有数字时才填，绝不能把运费的数字抄进 advance_payment；单据上没有垫付款这一栏，advance_payment 就必须是 null。注意“代收货款”“保价费”“声明价值”都不是垫付款，不要混填。
到站字段只做“读图取证”，不要下结论：destination_raw_tokens 逐项照抄单据上出现的到站文字，一个格子或一个词一项，严禁把相邻的两个地名拼成一个词，严禁补上单据里没有的“市”“县”等字。若某一项带勾、圆圈或下划线标记，把该项原文放进 destination_checked_token，并在 destination_mark_type 填 check/circle/underline；没有任何标记就都返回 null。版面是印刷好的勾选表时 destination_layout 填 checklist，是手写的填 handwritten，看不清填 null。destination_canonical 只在你有十足把握判断标准到站时从列表中选择，拿不准一律返回 null。发货地点永远是昆明，到站不可能是昆明。"""
    }
}
