package com.goings.kaidanzhushou.data.local

import android.content.Context
import com.goings.kaidanzhushou.domain.DestinationDictionary

/**
 * 到站字典的 Context 薄壳：站点表随包内置在 assets 里（只有两个几乎不变的站点，
 * 不做运行时同步）。解析与匹配全在 [DestinationDictionary]（纯函数，可单测）。
 *
 * 要改站点：同时改 assets/destination_dictionary.json 与油猴脚本顶部的 DESTINATIONS，
 * 两边的 unique_key 必须一致——它是 XLSX 里 destination_unique_key 列的取值来源。
 */
class DictionaryStore(private val context: Context) {

    @Volatile
    private var cached: DestinationDictionary? = null

    fun current(): DestinationDictionary = cached ?: load().also { cached = it }

    private fun load(): DestinationDictionary =
        runCatching { context.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }
            .getOrNull()
            ?.let(DestinationDictionary.Companion::parse)
            ?: DestinationDictionary.EMPTY

    private companion object {
        const val FILE_NAME = "destination_dictionary.json"
    }
}
