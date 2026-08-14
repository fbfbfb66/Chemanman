package com.goings.kaidanzhushou.data.remote

import com.goings.kaidanzhushou.BuildConfig
import com.goings.kaidanzhushou.domain.RecognitionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.Base64

enum class KimiErrorKind { UNAUTHORIZED, QUOTA, RATE_LIMIT, SERVER, NETWORK, PERMANENT, INVALID_RESPONSE }

class KimiException(
    val kind: KimiErrorKind,
    message: String,
    val retryAfterMillis: Long? = null,
) : Exception(message)

class KimiClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val baseUrl: String = BuildConfig.KIMI_BASE_URL,
) {
    suspend fun recognize(apiKey: String, image: File): RecognitionDraft = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl}chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(requestBody(image).toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(request).execute().use { response ->
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
                val content = SseContentParser(json).parse(body.source())
                parseDraft(content)
            }
        } catch (e: KimiException) {
            throw e
        } catch (e: IOException) {
            throw KimiException(KimiErrorKind.NETWORK, "网络连接失败")
        }
    }

    internal fun parseDraft(content: String): RecognitionDraft = try {
        val objectValue = json.parseToJsonElement(content).jsonObject
        if (!REQUIRED_FIELDS.all(objectValue::containsKey)) {
            throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回字段不完整")
        }
        json.decodeFromString<RecognitionDraft>(content)
    } catch (error: KimiException) {
        throw error
    } catch (_: Exception) {
        throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 返回内容不符合数据格式")
    }

    private fun requestBody(image: File): JsonObject {
        val encoded = Base64.getEncoder().encodeToString(image.readBytes())
        return buildJsonObject {
            put("model", JsonPrimitive("kimi-k3"))
            put("stream", JsonPrimitive(true))
            put("reasoning_effort", JsonPrimitive("high"))
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
                    put("name", JsonPrimitive("waybill_record_v1"))
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
                listOf("destination_text", "sender_name", "receiver_name", "receiver_mobile", "goods_name", "package", "notes").forEach {
                    put(it, buildJsonObject { put("type", nullable("string")) })
                }
                put("delivery_type", buildJsonObject {
                    put("type", nullable("string"))
                    put("enum", JsonArray(listOf(JsonPrimitive("delivery"), JsonPrimitive("pickup"), JsonNull)))
                })
                put("quantity", buildJsonObject { put("type", nullable("integer")); put("minimum", JsonPrimitive(1)) })
                listOf("weight", "volume", "freight").forEach {
                    put(it, buildJsonObject { put("type", nullable("number")); put("minimum", JsonPrimitive(0)) })
                }
                put("uncertain_fields", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
            })
            put("required", JsonArray(listOf(
                "destination_text", "delivery_type", "sender_name", "receiver_name", "receiver_mobile",
                "goods_name", "package", "quantity", "weight", "volume", "freight", "uncertain_fields", "notes",
            ).map(::JsonPrimitive)))
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val REQUIRED_FIELDS = setOf(
            "destination_text", "delivery_type", "sender_name", "receiver_name", "receiver_mobile",
            "goods_name", "package", "quantity", "weight", "volume", "freight", "uncertain_fields", "notes",
        )
        const val PROMPT = """你是托运单录入助手。只提取图片中明确可见的信息，禁止推测、补全或编造。无法确认的字段必须返回 null，并把字段名加入 uncertain_fields。delivery_type 只能是 delivery（送货）或 pickup（自提）。quantity 为件数；weight、volume、freight 仅返回数字。notes 只记录影响人工核对的简短说明。"""
    }
}
