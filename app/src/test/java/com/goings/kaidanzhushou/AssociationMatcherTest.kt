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
}
