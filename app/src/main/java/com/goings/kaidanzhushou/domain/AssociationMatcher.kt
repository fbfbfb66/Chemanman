package com.goings.kaidanzhushou.domain

import java.util.Locale
import kotlin.math.max

data class AssociationCandidate<T>(
    val value: T,
    val score: Double,
    val exact: Boolean,
)

data class AssociationResolution<T>(
    val automatic: T?,
    val candidates: List<AssociationCandidate<T>>,
    val needsChoice: Boolean,
)

object AssociationMatcher {
    private val ignored = Regex("[\\s\\p{P}\\p{S}]+")

    fun normalize(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace(ignored, "")

    fun normalizePhone(value: String?): String = value.orEmpty().filter(Char::isDigit)

    fun maskPhone(value: String): String {
        val digits = normalizePhone(value)
        return when {
            digits.length >= 7 -> "${digits.take(3)} **** ${digits.takeLast(4)}"
            digits.length >= 3 -> "${digits.take(2)} *** ${digits.takeLast(1)}"
            else -> digits
        }
    }

    fun <T> resolve(
        query: String?,
        values: List<T>,
        normalizedName: (T) -> String,
        useCount: (T) -> Int,
        lastUsedAt: (T) -> Long,
        limit: Int = 5,
    ): AssociationResolution<T> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            val recent = values.sortedWith(compareByDescending<T> { lastUsedAt(it) }.thenByDescending { useCount(it) })
                .take(limit)
                .map { AssociationCandidate(it, 0.0, false) }
            return AssociationResolution(null, recent, false)
        }

        val ranked = values.mapNotNull { value ->
            val candidateName = normalizedName(value)
            val score = score(normalizedQuery, candidateName) ?: return@mapNotNull null
            AssociationCandidate(value, score, candidateName == normalizedQuery)
        }.sortedWith(
            compareByDescending<AssociationCandidate<T>> { it.score }
                .thenByDescending { useCount(it.value) }
                .thenByDescending { lastUsedAt(it.value) },
        ).take(limit)

        val exact = ranked.filter { it.exact }
        return AssociationResolution(
            automatic = exact.singleOrNull()?.value,
            candidates = ranked,
            needsChoice = exact.size > 1 || (exact.isEmpty() && ranked.isNotEmpty()),
        )
    }

    fun editDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun score(left: String, right: String): Double? {
        if (left == right) return 1.0
        if (right.isBlank()) return null
        val longest = max(left.length, right.length)
        val distance = editDistance(left, right)
        val similarity = 1.0 - distance.toDouble() / longest
        val accepted = if (longest <= 4) distance <= 1 && minOf(left.length, right.length) >= 2 else similarity >= 0.75
        return similarity.takeIf { accepted }
    }
}

object AssociationFields {
    const val RECEIVER = "receiver_profile"
    const val GOODS = "goods_profile"
}
