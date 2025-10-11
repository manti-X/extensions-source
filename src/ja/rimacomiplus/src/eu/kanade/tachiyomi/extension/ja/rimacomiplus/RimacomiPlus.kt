package eu.kanade.tachiyomi.extension.ja.rimacomiplus

import eu.kanade.tachiyomi.multisrc.comiciviewer.ComiciViewer
import eu.kanade.tachiyomi.multisrc.comiciviewer.ViewerResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

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

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val viewer = document.selectFirst("#comici-viewer") ?: throw Exception("You need to log in via WebView to read this chapter or purchase this chapter")
        val comiciViewerId = viewer.attr("comici-viewer-id")
        val memberJwt = viewer.attr("data-member-jwt")
        val apiHeaders = headers.newBuilder()
            .set("Referer", response.request.url.toString())
            .build()

        val requestUrl = "$baseUrl/book/contentsInfo".toHttpUrl().newBuilder()
            .addQueryParameter("comici-viewer-id", comiciViewerId)
            .addQueryParameter("user-id", memberJwt)
            .addQueryParameter("page-from", "0")

        val pageTo = client.newCall(GET(requestUrl.addQueryParameter("page-to", "1").build(), apiHeaders))
            .execute().use { initialResponse ->
                if (!initialResponse.isSuccessful) {
                    throw Exception("Failed to get page list")
                }
                initialResponse.parseAs<ViewerResponse>().totalPages.toString()
            }

        val getAllPagesUrl = requestUrl.setQueryParameter("page-to", pageTo).build()
        return client.newCall(GET(getAllPagesUrl, apiHeaders)).execute().use { allPagesResponse ->
            if (allPagesResponse.isSuccessful) {
                allPagesResponse.parseAs<ViewerResponse>().result.map { resultItem ->
                    val urlBuilder = resultItem.imageUrl.toHttpUrl().newBuilder()
                    if (resultItem.scramble.isNotEmpty()) {
                        urlBuilder.addQueryParameter("scramble", resultItem.scramble)
                    }
                    Page(
                        index = resultItem.sort,
                        imageUrl = urlBuilder.build().toString(),
                    )
                }.sortedBy { it.index }
            } else {
                throw Exception("Failed to get full page list")
            }
        }
    }

    override fun getFilterOptions(): List<Pair<String, String>> = listOf(
        Pair("ランキング", "/ranking/manga"),
        Pair("読み切り", "/category/manga?type=読み切り"),
        Pair("完結", "/category/manga?type=完結"),
        Pair("連載", "/category/manga?type=連載中"),
    )
}
