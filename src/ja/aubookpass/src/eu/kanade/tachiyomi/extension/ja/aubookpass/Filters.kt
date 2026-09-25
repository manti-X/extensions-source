package eu.kanade.tachiyomi.extension.ja.aubookpass

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl.Builder

open class SelectFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    val value: String
        get() = vals[state].second
}

class GroupedCheckBox(name: String, val value: String) : Filter.CheckBox(name)

open class CheckBoxGroup(displayName: String, options: Array<Pair<String, String>>) : Filter.Group<GroupedCheckBox>(displayName, options.map { GroupedCheckBox(it.first, it.second) }) {
    val checked get() = state.filter { it.state }.map { it.value }
}

fun Builder.addFilter(param: String, filter: SelectFilter?) = filter?.value?.takeIf(String::isNotEmpty)?.let { addQueryParameter(param, it) }

fun Builder.addFilter(param: String, filter: Filter.Text?) = filter?.state?.takeIf(String::isNotBlank)?.let { addQueryParameter(param, it.trim()) }

class SortFilter :
    SelectFilter(
        "並べ替え",
        arrayOf(
            "人気順" to "poplar",
            "新着順" to "newest",
        ),
    )

class SearchTargetFilter :
    SelectFilter(
        "検索対象",
        arrayOf(
            "すべて" to "",
            "著者" to "author",
            "著者 (完全一致)" to "author_exact",
            "出版社" to "publisher",
            "掲載誌" to "magazine",
            "レーベル" to "label",
        ),
    )

class CategoryFilter :
    CheckBoxGroup(
        "カテゴリ",
        arrayOf(
            "少年コミック" to "A00002B00042C00183",
            "青年コミック" to "A00002B00042C00184",
            "男性コミック誌" to "A00002B00044",
            "少女コミック" to "A00002B00043C00185",
            "女性コミック" to "A00002B00043C00186",
            "女性コミック誌" to "A00002B00045",
            "BLコミック" to "A00009B00050C00193",
            "BLコミック誌" to "A00009B00050C00194",
            "TL" to "A00003B00049C00192",
            "女性向けアダルトコミック" to "A00003B00049C00189",
            "女性向けアダルトコミック誌" to "A00003B00049C00190",
            "男性向けアダルトコミック" to "A00003B00048C00187",
            "男性向けアダルトコミック誌" to "A00003B00048C00188",
        ),
    ) {
    init {
        state.forEach { it.state = it.value.startsWith("A00002") }
    }

    val tags get() = checked.ifEmpty { state.map { it.value } }.joinToString(",")
}

class ConditionFilter :
    CheckBoxGroup(
        "条件",
        arrayOf(
            "完結" to "completed",
            "試し読み" to "sample",
            "新着" to "new",
            "セール中" to "sale",
            "読み放題" to "yomihoudai",
            "全巻読み放題" to "yomihoudaiOnly",
        ),
    )

class PriceMinFilter : Filter.Text("最小価格 (円)")

class PriceMaxFilter : Filter.Text("最大価格 (円)")
