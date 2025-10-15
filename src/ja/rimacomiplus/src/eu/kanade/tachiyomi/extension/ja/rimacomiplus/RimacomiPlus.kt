package eu.kanade.tachiyomi.extension.ja.rimacomiplus

import eu.kanade.tachiyomi.multisrc.comiciviewer.ComiciViewer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class RimacomiPlus : ComiciViewer(
    "RimacomiPlus",
    "https://rimacomiplus.jp",
    "ja",
) {
    override fun chapterListRequest(manga: SManga): Request {
        val tempResponse = client.newCall(GET(baseUrl + manga.url, headers)).execute()
        val finalUrl = tempResponse.request.url.toString()
        tempResponse.close()
        val chapterListUrl = finalUrl.toHttpUrl().newBuilder()
            .removeAllQueryParameters("s")
            .addPathSegment("list")
            .addQueryParameter("s", "1")
            .build()

        return GET(chapterListUrl, headers)
    }

    override fun getFilterOptions(): List<Pair<String, String>> = listOf(
        Pair("ランキング", "/ranking/manga"),
        Pair("読み切り", "/category/manga?type=読み切り"),
        Pair("完結", "/category/manga?type=完結"),
        Pair("連載", "/category/manga?type=連載中"),
    )
}
