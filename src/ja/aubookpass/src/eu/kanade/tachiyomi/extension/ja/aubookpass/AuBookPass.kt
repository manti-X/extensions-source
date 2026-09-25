package eu.kanade.tachiyomi.extension.ja.aubookpass

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.boolean
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID.randomUUID

@Source
abstract class AuBookPass :
    KeiSource(),
    ConfigurableSource {
    private val apiUrl get() = "$baseUrl/api"
    private val accessKey = "c03116c3a79dda5b1b28d8819cd5216f"
    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(ImageInterceptor())
        addCookie("safe_search" to "0")
        addInterceptor {
            val request = it.request()
            val response = it.proceed(request)
            if (response.code == 403 && request.url.encodedPath == "/api/viewer") {
                throw IOException("Log in via WebView and purchase this product to read.")
            }
            response
        }
    }

    override suspend fun getPopularManga(page: Int) = getSearchMangaList(page, "", FilterList(CategoryFilter()))

    override suspend fun getLatestUpdates(page: Int) = getSearchMangaList(page, "", FilterList(SortFilter().apply { state = 1 }, CategoryFilter()))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("query", query)
            addFilter("searchTarget", filters.firstInstanceOrNull<SearchTargetFilter>())
            addFilter("sort", filters.firstInstanceOrNull<SortFilter>())
            filters.firstInstanceOrNull<CategoryFilter>()?.let { addQueryParameter("assortTags", it.tags) }
            filters.firstInstanceOrNull<ConditionFilter>()?.checked?.forEach { addQueryParameter(it, "1") }
            addFilter("priceFrom", filters.firstInstanceOrNull<PriceMinFilter>())
            addFilter("priceTo", filters.firstInstanceOrNull<PriceMaxFilter>())
        }.build()

        val result = client.get(url).parseAs<SearchResponse>()
        val mangas = result.hits.map { it.toSManga() }
        // pages past 31 are always empty
        val hasNextPage = page < 31 && page * 99 < result.totalHits
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        SearchTargetFilter(),
        CategoryFilter(),
        ConditionFilter(),
        Filter.Separator(),
        PriceMinFilter(),
        PriceMaxFilter(),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/collections/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val hideLocked = preferences.getBoolean(HIDE_LOCKED_PREF_KEY, false)
        val url = "https://universal-proxy.bookpass.auone.jp/item/4.0/front/collection".toHttpUrl().newBuilder()
            .addQueryParameter("accessKey", accessKey)
            .addQueryParameter("collectionId", manga.url)
            .addQueryParameter("limit", "1000")
            .addQueryParameter("sort", "desc")
            .build()

        val items = client.get(url).parseAs<DetailsResponse>().collectionInfo.itemInfo

        return SMangaUpdate(
            items.first().toSManga(),
            items.map { it.contentInfo }
                .filter { !hideLocked || (!it.isLocked && !it.isPreview) }
                .map { it.toSChapter() },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/viewer".toHttpUrl().newBuilder()
        .addQueryParameter("iid", chapter.url)
        .addQueryParameter("sample", chapter.memo["isSample"]!!.boolean.toString())
        .addQueryParameter("storeUrl", "$baseUrl/titles/${chapter.url}")
        .build()
        .toString()

    override suspend fun getPageList(chapter: SChapter): List<Page> = coroutineScope {
        val tokenUrl = "$apiUrl/viewer".toHttpUrl().newBuilder()
            .addQueryParameter("iid", chapter.url)
            .addQueryParameter("storeUrl", "0")
            .addQueryParameter("storeType", "BrPc1")
            .addQueryParameter("sample", chapter.memo["isSample"]!!.boolean.toString())
            .build()

        val token = client.get(tokenUrl).parseAs<TokenResponse>()
        val nmr = randomUUID().toString()
        val viewerHeaders = headersBuilder()
            .set(HEADER_NMR, nmr)
            .set(HEADER_TOKEN, token.authToken)
            .set(HEADER_USE_CACHE, "false")
            .set(HEADER_UUID, token.uuid)
            .build()

        val base = "$VIEWER_URL/${token.iid}"
        val metaData = async { client.get("$base/meta", viewerHeaders).parseAs<MetaResponse>().data }
        val cipherKey = async { extractCipherKey(client.get("$base/decrypt", viewerHeaders).use { it.body.string() }) }

        val meta = metaData.await()
        val maxIndex = meta.page.all?.minus(1) ?: throw Exception("Novels are not supported!")
        val key = cipherKey.await()

        (0..maxIndex).map { index ->
            val url = "$base/$PATH_IMAGE_URL".toHttpUrl().newBuilder()
                .addQueryParameter(PARAM_INDICES, index.toString())
                .addQueryParameter(PARAM_CODE, QUALITY_HIGH)
                .addQueryParameter(PARAM_ACCEPT, ACCEPT_FORMATS)
                .fragment("$nmr;${token.authToken};${token.uuid};$maxIndex;$key;${meta.type}")
                .build()
            Page(index, imageUrl = url.toString())
        }
    }

    // /decrypt worker: 'var e = [int, int, int, int]'
    private fun extractCipherKey(workerJs: String): String {
        val keyArray = HEADER_KEY.find(workerJs)?.value
            ?: FOUR_INT_ARRAY.findAll(workerJs).map { it.value }.firstOrNull {
                it.trim('[', ']').split(",").map(String::trim) != IV_DIGITS
            }
            ?: throw Exception("missing keys")
        return NUMBER.findAll(keyArray).joinToString("") { "%08x".format(it.value.toLong().toInt()) }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_LOCKED_PREF_KEY
            title = "Hide Locked Chapters"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val HIDE_LOCKED_PREF_KEY = "hide_locked"
        private val HEADER_KEY = Regex("""\be\s*=\s*\[\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*\d+\s*]""")
        private val FOUR_INT_ARRAY = Regex("""\[\s*\d+\s*(?:,\s*\d+\s*){3}]""")
        private val NUMBER = Regex("""\d+""")
        private val IV_DIGITS = listOf("0", "1", "2", "3")
    }
}
