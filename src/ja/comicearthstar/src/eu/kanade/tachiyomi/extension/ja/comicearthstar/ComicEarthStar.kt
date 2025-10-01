package eu.kanade.tachiyomi.extension.ja.comicearthstar

import eu.kanade.tachiyomi.multisrc.gigaviewer.GigaViewer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element

class ComicEarthStar : GigaViewer(
    "Comic Earth Star",
    "https://comic-earthstar.com",
    "ja",
    "https://cdn-img.comic-earthstar.com",
    isPaginated = true,
) {
    private val apiUrl = "$baseUrl/graphql"

    override val supportsLatest = false

    override val publisher: String = "アース・スター エンターテイメント"

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::imageIntercept)
        .build()

    private val oneshotQuery = """
        query Earthstar_Oneshot {
          seriesOneshot: serialGroup(groupName: "連載・読切：読切作品") {
            seriesSlice {
              seriesList {
                ...Earthstar_SeriesListItem_Series
                __typename
              }
              __typename
            }
            __typename
          }
        }

        fragment Earthstar_SeriesListItem_Series on Series {
          id
          thumbnailUri
          title
          keyColor
          author {
            name
            __typename
          }
          firstEpisode {
            permalink
            __typename
          }
          latestEpisode {
            permalink
            __typename
          }
          __typename
        }
    """.trimIndent()

    private inline fun <reified T> graphQlRequest(operationName: String, variables: T, query: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("opname", operationName)
            .build()

        val payload = Payload(operationName, variables, query)
        val requestBody = jsonInstance.encodeToString(Payload.serializer(serializer<T>()), payload).toRequestBody("application/json; charset=utf-8".toMediaType())

        return POST(url.toString(), headers, requestBody)
    }

    override fun popularMangaSelector(): String = "ul[class^=SeriesList_series_list__] > li > div"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.selectFirst("h3[class*=SeriesListItem_title__]")!!.text()
        thumbnail_url = link.selectFirst("img[class*=SeriesListItem_thumb__]")?.attr("src")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)

            return GET(url.build(), headers)
        }

        val filter = filters[0] as Filter.Select<*>
        val collection = getCollections()[filter.state]

        if (collection.path == "oneshot") {
            return graphQlRequest("Earthstar_Oneshot", EmptyVariables, oneshotQuery)
        }
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains("search")) {
            return super.searchMangaParse(response)
        }

        if (response.request.url.toString().startsWith(apiUrl)) {
            val result = response.parseAs<GraphQLResponse<OneshotDto>>()
            val mangas = result.data.seriesOneshot?.seriesSlice?.seriesList?.map { series ->
                SManga.create().apply {
                    title = series.title
                    thumbnail_url = series.thumbnailUri
                    setUrlWithoutDomain(series.firstEpisode.permalink)
                }
            } ?: emptyList()
            return MangasPage(mangas, false)
        }
        return popularMangaParse(response)
    }

    override fun searchMangaSelector(): String = "li[class^=SearchResultItem_li__]"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("p[class^=SearchResultItem_series_title__]")!!.text()
        thumbnail_url = link.selectFirst("img")?.attr("src")
    }

    override fun getCollections(): List<Collection> = listOf(
        Collection("連載中・連載終了", "series"),
        Collection("読切作品", "oneshot"),
    )
}
