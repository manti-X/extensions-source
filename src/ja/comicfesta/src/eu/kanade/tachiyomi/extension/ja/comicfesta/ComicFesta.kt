package eu.kanade.tachiyomi.extension.ja.comicfesta

import eu.kanade.tachiyomi.lib.clipstudioreader.ClipStudioReader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class ComicFesta : ClipStudioReader() {
    override val name = "Comic Festa"
    override val baseUrl = "https://comic.iowl.jp"
    override val lang = "ja"
    override val supportsLatest = true

    // The website has two versions: one for mobile and one for PC, each with different selectors. Since the mobile version allows access to specific chapters, a mobile UA is forced here.
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/sales_rankings/daily_general?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div[class*=list-item_detailBox__]").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a[href^=/titles/]")!!.attr("href"))
                title = element.selectFirst("div[class*=title-name_box__]")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")?.toHttpUrl()?.queryParameter("url")
            }
        }
        val currentPage = response.request.url.queryParameter("page")!!.toInt()
        val nextButton = document.selectFirst("div[class*=pagination-button_box__] a")
        val hasNextPage = if (nextButton != null) {
            val nextPageUrl = nextButton.absUrl("href").toHttpUrl()
            val nextPageNum = nextPageUrl.queryParameter("page")!!.toInt()
            nextPageNum > currentPage
        } else {
            false
        }
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        TODO("Not yet implemented")
    }

    override fun searchMangaParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        TODO("Not yet implemented")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        TODO("Not yet implemented")
    }
}
