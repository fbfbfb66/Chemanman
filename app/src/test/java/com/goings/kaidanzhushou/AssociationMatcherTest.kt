package com.goings.kaidanzhushou

import com.goings.kaidanzhushou.domain.AssociationMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationMatcherTest {
    private data class Item(val id: String, val name: String, val phone: String = "", val uses: Int = 1, val usedAt: Long = 1)

    private fun resolve(query: String, values: List<Item>) = AssociationMatcher.resolve(
        query = query,
        values = values,
        normalizedName = { AssociationMatcher.normalize(it.name) },
        useCount = Item::uses,
        lastUsedAt = Item::usedAt,
    )

    @Test fun similarChineseNameIsSuggestedButNotAutomaticallySelected() {
        val result = resolve("任运飞", listOf(Item("receiver", "任云飞")))
        assertNull(result.automatic)
        assertTrue(result.needsChoice)
        assertEquals("receiver", result.candidates.single().value.id)
    }

    @Test fun oneExactNameIsAutomaticAndDuplicateNamesNeedChoice() {
        val only = resolve(" 任云飞。", listOf(Item("first", "任云飞")))
        assertEquals("first", only.automatic?.id)
        assertFalse(only.needsChoice)

        val duplicate = resolve("任云飞", listOf(Item("first", "任云飞", "1501"), Item("second", "任云飞", "1502")))
        assertNull(duplicate.automatic)
        assertTrue(duplicate.needsChoice)
        assertEquals(2, duplicate.candidates.size)
    }

    @Test fun phoneNeverChangesPersonRanking() {
        val first = resolve("任云飞", listOf(Item("a", "任云飞", "111"), Item("b", "任云飞", "999")))
        val second = resolve("任云飞", listOf(Item("a", "任云飞", "999"), Item("b", "任云飞", "111")))
        assertEquals(first.candidates.map { it.value.id }, second.candidates.map { it.value.id })
    }

    @Test fun goodsUsesTheSameExactAndFuzzyRules() {
        val exact = resolve("硫酸铜", listOf(Item("goods", "硫酸铜")))
        assertEquals("goods", exact.automatic?.id)
        val fuzzy = resolve("硫酸同", listOf(Item("goods", "硫酸铜")))
        assertNull(fuzzy.automatic)
        assertTrue(fuzzy.needsChoice)
    }

    private fun search(query: String, values: List<Item>) = AssociationMatcher.search(
        query = query,
        values = values,
        normalizedName = { AssociationMatcher.normalize(it.name) },
        useCount = Item::uses,
        lastUsedAt = Item::usedAt,
    ).map { it.id }

    @Test fun searchMatchesByPrefixSubstringAndScatteredChars() {
        val values = listOf(
            Item("kunming", "昆明恒通商贸"),
            Item("ren", "任云飞"),
            Item("guang", "广州盛达供应链"),
        )
        // 打一个字就该捞出来——这正是纠错匹配做不到的。
        assertEquals(listOf("ren"), search("任", values))
        assertEquals(listOf("kunming"), search("昆明", values))
        // 子串：不从头开始也算。
        assertEquals(listOf("kunming"), search("商贸", values))
        // 顺序散落：昆…商 都在，且顺序对。
        assertEquals(listOf("kunming"), search("昆商", values))
        // 顺序不对不算。
        assertTrue(search("商昆", values).isEmpty())
        // 错别字不算：那是 resolve 的活。
        assertTrue(search("任运飞", values).isEmpty())
        assertTrue(search("", values).isEmpty())
    }

    @Test fun searchRanksExactThenPrefixThenSubstringThenUsage() {
        val values = listOf(
            Item("substring", "云南云飞", uses = 99),
            Item("prefix", "云飞物流", uses = 1),
            Item("exact", "云飞", uses = 1),
            Item("prefix-hot", "云飞快运", uses = 5),
        )
        // 命中档位优先于常用度：完全相同 → 前缀（内部按常用度）→ 子串。
        assertEquals(listOf("exact", "prefix-hot", "prefix", "substring"), search("云飞", values))
    }

    @Test fun searchIgnoresPunctuationAndCaseLikeResolve() {
        val values = listOf(Item("abc", "ABC 物流(昆明)"))
        assertEquals(listOf("abc"), search("abc物流", values))
        assertEquals(listOf("abc"), search(" 昆明 ", values))
    }
}
