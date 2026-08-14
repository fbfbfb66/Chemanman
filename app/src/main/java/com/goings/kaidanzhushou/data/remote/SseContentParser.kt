package com.goings.kaidanzhushou.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource

class SseContentParser(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun parse(source: BufferedSource): String {
        val result = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            runCatching {
                val root = json.parseToJsonElement(payload).jsonObject
                root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.let(result::append)
        }
        if (result.isEmpty()) throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 未返回可解析内容")
        return result.toString()
    }
}
