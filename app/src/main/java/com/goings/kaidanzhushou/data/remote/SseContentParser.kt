package com.goings.kaidanzhushou.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource

class SseContentParser(private val json: Json = Json { ignoreUnknownKeys = true }) {
    /**
     * [onFirstContent] 在首个非空 content 分片到达时回调一次，调用方据此判断"模型已经开始吐字"。
     * 流一旦开始产出，几乎必然能走完，此时不应再发对冲请求。
     */
    fun parse(source: BufferedSource, onFirstContent: () -> Unit = {}): String {
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
            }.getOrNull()?.takeIf(String::isNotEmpty)?.let { chunk ->
                if (result.isEmpty()) onFirstContent()
                result.append(chunk)
            }
        }
        if (result.isEmpty()) throw KimiException(KimiErrorKind.INVALID_RESPONSE, "AI 未返回可解析内容")
        return result.toString()
    }
}
