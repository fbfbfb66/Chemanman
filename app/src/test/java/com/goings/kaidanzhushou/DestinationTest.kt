package com.goings.kaidanzhushou

import com.goings.kaidanzhushou.domain.DestinationDictionary
import com.goings.kaidanzhushou.domain.DestinationEvidence
import com.goings.kaidanzhushou.domain.DestinationNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTest {
    // 夹具照抄测试环境常用到站：玉溪市/通海县构成上下级链，「玉溪」是独立关键词，昆明是发货地。
    // 与 assets/destination_dictionary.json 一致的真值：实际在发的只有通海县和玉溪市，
    // 同名关键词站 xzqh_kw_玉溪 与郑州/开航/昆明全部 excluded。
    private val dictionaryJson = """
        {
          "schema": "destination-dictionary/v1",
          "generated_at": "2026-08-15T00:00:00.000Z",
          "stations": [
            {"unique_key": "xzqh_id_37979", "name": "玉溪市", "type": 2, "showpname": "云南省", "full_name": "云南省玉溪市", "parent_key": null, "aliases": ["玉溪"], "excluded": false},
            {"unique_key": "xzqh_id_38010", "name": "通海县", "type": 3, "showpname": "云南省玉溪市", "full_name": "云南省玉溪市通海县", "parent_key": "xzqh_id_37979", "aliases": ["通海"], "excluded": false},
            {"unique_key": "xzqh_id_20277", "name": "郑州市", "type": 2, "showpname": "河南省", "full_name": "河南省郑州市", "parent_key": null, "aliases": [], "excluded": true},
            {"unique_key": "xzqh_kw_开航", "name": "开航", "type": 4, "showpname": "", "full_name": "开航", "parent_key": null, "aliases": [], "excluded": true},
            {"unique_key": "xzqh_kw_玉溪", "name": "玉溪", "type": 4, "showpname": "", "full_name": "玉溪", "parent_key": null, "aliases": [], "excluded": true},
            {"unique_key": "xzqh_id_37704", "name": "昆明市", "type": 2, "showpname": "云南省", "full_name": "云南省昆明市", "parent_key": null, "aliases": [], "excluded": true}
          ],
          "alias_index": {
            "玉溪市": ["xzqh_id_37979"], "玉溪": ["xzqh_id_37979"],
            "通海县": ["xzqh_id_38010"], "通海": ["xzqh_id_38010"]
          }
        }
    """.trimIndent()

    private val dict = DestinationDictionary.parse(dictionaryJson)!!

    @Test fun parsesDictionaryAndRejectsWrongSchema() {
        assertTrue(dict.isUsable())
        assertEquals(6, dict.stations.size)
        assertNull(DestinationDictionary.parse("""{"schema":"other/v1"}"""))
        assertNull(DestinationDictionary.parse("not json"))
        assertFalse(DestinationDictionary.EMPTY.isUsable())
    }

    @Test fun enumOnlyContainsTheTwoStationsActuallyInUse() {
        assertEquals(listOf("玉溪市", "通海县").sorted(), dict.enumNames)
    }

    @Test fun checkedTokenResolvesToCheckedStation() {
        // 单据印「玉溪 | 通海」，勾在玉溪 → 玉溪市（同名关键词站已排除，不会被误选）。
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪", "通海"), checkedToken = "玉溪", markType = "check", layout = "checklist"),
            dict,
        )
        assertEquals("xzqh_id_37979", result.uniqueKey)
        assertEquals("玉溪市", result.name)
        assertFalse(result.needsReview)
        assertEquals("checked_exact", result.reason)
    }

    @Test fun checkedAliasResolvesToCounty() {
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪", "通海"), checkedToken = "通海", markType = "check", layout = "checklist"),
            dict,
        )
        assertEquals("xzqh_id_38010", result.uniqueKey)
        assertEquals("通海县", result.name)
        assertFalse(result.needsReview)
    }

    @Test fun unmarkedChecklistNeverGuesses() {
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪", "通海"), layout = "checklist"),
            dict,
        )
        assertNull(result.uniqueKey)
        assertTrue(result.needsReview)
        assertEquals("checklist_unmarked", result.reason)
        assertTrue(result.candidates.isNotEmpty())
    }

    @Test fun handwrittenChainTakesFinestGrain() {
        // 手写「玉溪市 通海县」= 地址路径 → 取最细粒度通海县，标待复核。
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪市", "通海县"), layout = "handwritten"),
            dict,
        )
        assertEquals("xzqh_id_38010", result.uniqueKey)
        assertEquals("通海县", result.name)
        assertTrue(result.needsReview)
        assertEquals("chain_finest", result.reason)
    }

    @Test fun mergedSingleTokenIsSegmented() {
        // 模型违规吐出「玉溪市通海县」单 token → 分词救回，标待复核。
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪市通海县"), layout = "handwritten"),
            dict,
        )
        assertEquals("xzqh_id_38010", result.uniqueKey)
        assertTrue(result.needsReview)
    }

    @Test fun unrelatedPlacesAreRejected() {
        // 当前只发通海/玉溪两地且互为上下级，构造不出无关多地名；用放开郑州的变体守住这条分支。
        val threeStations = dict.copy(
            stations = dict.stations.map { if (it.unique_key == "xzqh_id_20277") it.copy(excluded = false) else it },
            alias_index = dict.alias_index + mapOf("郑州" to listOf("xzqh_id_20277"), "郑州市" to listOf("xzqh_id_20277")),
        )
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪市", "郑州"), layout = "handwritten"),
            threeStations,
        )
        assertNull(result.uniqueKey)
        assertEquals("unrelated_multi", result.reason)
        assertTrue("xzqh_id_37979" in result.candidates && "xzqh_id_20277" in result.candidates)
    }

    @Test fun unknownTokenBesideAKnownOneStillNeedsReview() {
        // 单据带了一个我们不发的地名：归到认识的那个，但必须标待复核让人看一眼。
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("玉溪市", "郑州"), layout = "handwritten"),
            dict,
        )
        assertEquals("xzqh_id_37979", result.uniqueKey)
        assertTrue(result.needsReview)
    }

    @Test fun excludedStationsNeverResolveNorAppearInCandidates() {
        // 发货地昆明，以及与玉溪市同名、实际不用的关键词站，都不能被选中。
        listOf("昆明市" to "xzqh_id_37704", "开航" to "xzqh_kw_开航").forEach { (token, key) ->
            val resolved = DestinationNormalizer.resolve(
                DestinationEvidence(rawTokens = listOf(token), checkedToken = token),
                dict,
            )
            assertNull(resolved.uniqueKey)
            assertFalse(key in DestinationNormalizer.candidatesFor(listOf(token), dict))
        }
        assertFalse("xzqh_kw_玉溪" in DestinationNormalizer.candidatesFor(listOf("玉溪"), dict))
    }

    @Test fun emptyDictionaryDegradesHonestly() {
        val result = DestinationNormalizer.resolve(
            DestinationEvidence(rawTokens = listOf("通海"), checkedToken = "通海"),
            DestinationDictionary.EMPTY,
        )
        assertNull(result.uniqueKey)
        assertEquals("no_dictionary", result.reason)
    }

    @Test fun candidatesRankExactAboveFuzzy() {
        val candidates = DestinationNormalizer.candidatesFor(listOf("通海"), dict)
        assertEquals("xzqh_id_38010", candidates.first())
    }

    @Test fun normalizeStripsNoiseCharacters() {
        assertEquals("玉溪通海", DestinationNormalizer.normalize(" 玉 溪 · 通海 "))
        assertEquals("通海县", DestinationNormalizer.normalize("通海（县）"))
    }
}
