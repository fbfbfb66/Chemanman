package com.goings.kaidanzhushou.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 到站字典（destination-dictionary/v1），随包内置在 assets 里。
 * alias_index 是唯一权威索引（normalize 后的写法 → unique_key），App 不自己生成别名；
 * 一个 key 映射到多个站点即视为歧义，归一算法拒绝自动裁决。
 */
@Serializable
data class DestinationDictionary(
    val schema: String = "",
    val generated_at: String = "",
    val stations: List<Station> = emptyList(),
    val alias_index: Map<String, List<String>> = emptyMap(),
) {
    @Serializable
    data class Station(
        val unique_key: String,
        val name: String,
        val type: Int = 0,
        val showpname: String = "",
        val full_name: String = "",
        val parent_key: String? = null,
        val aliases: List<String> = emptyList(),
        val excluded: Boolean = false,
    )

    val byKey: Map<String, Station> by lazy { stations.associateBy { it.unique_key } }

    /** 注入 Kimi json_schema enum 的候选：非 excluded 站名，去重按字典序（稳定顺序利于 prompt 缓存）。 */
    val enumNames: List<String> by lazy { stations.filterNot { it.excluded }.map { it.name }.distinct().sorted() }

    fun isUsable(): Boolean = schema == SCHEMA && stations.any { !it.excluded } && alias_index.isNotEmpty()

    companion object {
        const val SCHEMA = "destination-dictionary/v1"
        val EMPTY = DestinationDictionary()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** 解析失败或 schema 不符时返回 null，调用方自行降级到 EMPTY。 */
        fun parse(text: String): DestinationDictionary? = try {
            json.decodeFromString<DestinationDictionary>(text).takeIf { it.schema == SCHEMA }
        } catch (_: Exception) {
            null
        }
    }
}

/** AI 读图取证的结果——只有版面证据，没有结论。 */
data class DestinationEvidence(
    val rawTokens: List<String> = emptyList(),
    val checkedToken: String? = null,
    val markType: String? = null,
    val layout: String? = null,
    val aiCanonical: String? = null,
)

/**
 * 归一结果。[name] 写入 destinationText（即最终填进车满满的标准站名）；
 * [needsReview] 为 true 时 uncertainFields 记入 destination_text；
 * [candidates] 是复核界面候选 chip 的 unique_key 列表。
 */
data class DestinationResolution(
    val uniqueKey: String? = null,
    val name: String? = null,
    /** 上级全称（full_name），随记录落库供导出使用，避免导出时依赖字典。 */
    val display: String = "",
    val needsReview: Boolean = true,
    val candidates: List<String> = emptyList(),
    val reason: String = "",
)

object DestinationNormalizer {

    fun normalize(value: String?): String =
        (value ?: "").replace(Regex("\\s+"), "").replace(Regex("[·．.，,、/／()（）\\-—－]"), "")

    /**
     * 确定性归一。裁决规则（业务已拍板）：
     * 1. 有打勾 → 用被勾项匹配，唯一命中即定；
     * 2. 无勾 + 印刷勾选表 → 客户没选，一律留空强制人工点选，绝不猜；
     * 3. 无勾 + 手写 → 多地名构成上下级链取最细粒度并标待复核（玉溪+通海→通海县），不构成链不猜。
     */
    fun resolve(evidence: DestinationEvidence, dict: DestinationDictionary): DestinationResolution {
        if (!dict.isUsable()) return DestinationResolution(reason = "no_dictionary")
        val tokens = evidence.rawTokens.map(::normalize).filter { it.isNotEmpty() }.distinct()

        evidence.checkedToken?.takeIf { normalize(it).isNotEmpty() }?.let { checked ->
            val hits = lookup(normalize(checked), dict)
            if (hits.size == 1) return resolved(hits[0], dict, needsReview = false, reason = "checked_exact")
            if (hits.size > 1) return DestinationResolution(candidates = hits, reason = "checked_ambiguous")
            // 被勾的应是一个地点；若拆成多段且构成上下级链（如「玉溪市通海县」路径写法）取最细，否则交人工。
            val segments = segment(normalize(checked), dict)
            if (segments != null) {
                chainFinest(segments, dict)?.let { return resolved(it, dict, needsReview = true, reason = "checked_segmented", candidates = segments) }
                return DestinationResolution(candidates = segments, reason = "checked_ambiguous")
            }
            canonicalFallback(evidence, dict)?.let { return it }
            return DestinationResolution(candidates = candidatesFor(tokens, dict), reason = "checked_unknown")
        }

        if (evidence.layout == "checklist") {
            return DestinationResolution(candidates = candidatesFor(tokens, dict), reason = "checklist_unmarked")
        }

        // 手写或版面未知：逐 token 展开。分词结果是「路径」（每段都唯一命中）而不是歧义，直接并入 keys。
        val keys = mutableListOf<String>()
        for (token in tokens) {
            val hits = lookup(token, dict)
            when {
                hits.size == 1 -> keys.add(hits[0])
                hits.size > 1 -> return DestinationResolution(candidates = hits, reason = "token_ambiguous")
                else -> segment(token, dict)?.let(keys::addAll)
            }
        }
        val distinct = keys.distinct()
        if (distinct.isEmpty()) {
            canonicalFallback(evidence, dict)?.let { return it }
            return DestinationResolution(candidates = candidatesFor(tokens, dict), reason = "no_dictionary_hit")
        }
        if (distinct.size == 1) {
            return resolved(distinct[0], dict, needsReview = tokens.size > 1, reason = "single_key")
        }
        chainFinest(distinct, dict)?.let { return resolved(it, dict, needsReview = true, reason = "chain_finest", candidates = distinct) }
        return DestinationResolution(candidates = distinct, reason = "unrelated_multi")
    }

    /**
     * canonical 受 enum 约束，必是字典里的真实站名——但它是模型的判断而非版面证据，
     * 只在证据解析完全失败时作低置信兜底，且必标待复核。checklist 无勾的路径不经过这里（不猜是硬规则）。
     */
    private fun canonicalFallback(evidence: DestinationEvidence, dict: DestinationDictionary): DestinationResolution? {
        val canonical = normalize(evidence.aiCanonical).takeIf { it.isNotEmpty() } ?: return null
        val hits = lookup(canonical, dict)
        return if (hits.size == 1) resolved(hits[0], dict, needsReview = true, reason = "ai_canonical") else null
    }

    /** keys 构成完整上下级链时返回最细的一个，否则 null。 */
    private fun chainFinest(keys: List<String>, dict: DestinationDictionary): String? {
        if (keys.size < 2) return keys.firstOrNull()
        val sorted = keys.sortedBy { depth(it, dict) }
        val isChain = sorted.zipWithNext().all { (upper, lower) -> isAncestor(upper, lower, dict) }
        return if (isChain) sorted.last() else null
    }

    /** excluded 站（发货地昆明、实际不发的站点）永不返回。 */
    private fun lookup(token: String, dict: DestinationDictionary): List<String> {
        if (token.isEmpty()) return emptyList()
        return dict.alias_index[token].orEmpty().filter { dict.byKey[it]?.excluded == false }
    }

    /**
     * 最长匹配 + 必须完整消耗的分词，专治模型仍吐出「玉溪通海」单 token 的情况。
     * 任一切片歧义或残留未消耗即失败（返回 null）——分词只在无歧义时给答案。
     */
    internal fun segment(token: String, dict: DestinationDictionary): List<String>? {
        if (token.length < 2) return null
        val result = mutableListOf<String>()
        var index = 0
        while (index < token.length) {
            var matched = false
            for (end in token.length downTo index + 2) {
                val hits = lookup(token.substring(index, end), dict)
                if (hits.size == 1) {
                    result.add(hits[0]); index = end; matched = true; break
                }
                if (hits.size > 1) return null
            }
            if (!matched) return null
        }
        // 单切片等价于直查命中，不算分词成果；要求至少切出两段才有意义。
        return result.distinct().takeIf { result.size >= 2 }
    }

    private fun depth(key: String, dict: DestinationDictionary): Int {
        var current = dict.byKey[key]
        var level = 0
        while (current?.parent_key != null && level < 8) {
            current = dict.byKey[current.parent_key]; level++
        }
        return level
    }

    private fun isAncestor(upperKey: String, lowerKey: String, dict: DestinationDictionary): Boolean {
        var current = dict.byKey[lowerKey]
        var guard = 0
        while (current?.parent_key != null && guard < 8) {
            if (current.parent_key == upperKey) return true
            current = dict.byKey[current.parent_key]; guard++
        }
        return false
    }

    private fun resolved(
        key: String,
        dict: DestinationDictionary,
        needsReview: Boolean,
        reason: String,
        candidates: List<String> = emptyList(),
    ): DestinationResolution {
        val station = dict.byKey[key] ?: return DestinationResolution(reason = "missing_station")
        return DestinationResolution(key, station.name, station.full_name, needsReview, candidates, reason)
    }

    /** 打分只用于排候选 chip，永不参与自动裁决。 */
    internal fun candidatesFor(tokens: List<String>, dict: DestinationDictionary): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val scores = mutableMapOf<String, Int>()
        for (station in dict.stations) {
            if (station.excluded) continue
            val name = normalize(station.name)
            var best = 0
            for (token in tokens) {
                val score = when {
                    token == name -> 1000
                    station.aliases.any { normalize(it) == token } -> 800
                    token.length >= 2 && (name.startsWith(token) || token.startsWith(name)) -> 600
                    else -> {
                        val distance = levenshtein(token, name)
                        if (distance <= 2) 400 - 200 * distance else 0
                    }
                }
                if (score > best) best = score
            }
            if (best > 0) scores[station.unique_key] = best
        }
        return scores.entries.sortedByDescending { it.value }.take(6).map { it.key }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
