package eu.kanade.tachiyomi.extension.en.omoi

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

@Source
abstract class Azuki :
    KeiSource(),
    ConfigurableSource {
    private val apiUrl = "https://$API_HOST"
    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(ImageInterceptor())
        addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != API_HOST) return@addInterceptor chain.proceed(request)

            val token = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
                .firstOrNull { it.name == "idToken" }?.value
            val apiRequest = request.newBuilder()
                .header("Azuki-Organization-Key", ORGANIZATION_KEY)
                .apply { if (token != null) header("X-User-Token", token) }
                .build()
            val response = chain.proceed(apiRequest)

            if (request.url.pathSegments.getOrNull(2) == "pages") {
                val message = when (response.code) {
                    401, 403 -> "Log in via WebView and purchase this chapter to read."
                    404 -> "This chapter is not available."
                    else -> return@addInterceptor response
                }
                response.close()
                throw IOException(message)
            }
            response
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$baseUrl/discover?sort=popular&page=$page").toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get("$baseUrl/discover?sort=recent_series&page=$page").toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/discover".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }
            filters.firstInstanceOrNull<SortFilter>()?.value?.let {
                addQueryParameter("sort", it)
            }

            filters.firstInstanceOrNull<AccessTypeFilter>()?.value?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("access_type", it)
            }

            filters.firstInstanceOrNull<PublisherFilter>()?.value?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("publisher_slug", it)
            }

            filters.firstInstanceOrNull<GenreFilter>()?.state?.filter { it.state }?.forEach {
                addQueryParameter("tags[]", it.value)
            }
        }.build()
        return client.get(url).toMangasPage()
    }

    private fun Response.toMangasPage(): MangasPage {
        val document = asJsoup()
        val mangas = document.select("ol.o-series-card-list li").map {
            SManga.create().apply {
                val link = it.selectFirst("a.a-card-link")!!
                url = link.attr("data-ga-item-id").substringAfter("series-")
                title = link.text()
                thumbnail_url = it.selectFirst("img")?.absUrl("src")
                memo = buildJsonObject {
                    put("slug", link.absUrl("href").toHttpUrl().pathSegments.last())
                }
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.memo["slug"]?.string ?: throw Exception("Migrate this entry from Omoi to Omoi")

        val details = async {
            if (!fetchDetails) return@async manga
            client.get("$apiUrl/manga/slug/$slug/v0").parseAs<DetailsDto>().toSManga()
        }

        val chapterList = async {
            if (!fetchChapters) return@async chapters

            val unlockedIds = async {
                val response = client.get("$apiUrl/user/mangas/${manga.url}/v0", ensureSuccess = false)
                if (response.isSuccessful) {
                    val status = response.parseAs<UserMangaStatusDto>()
                    (status.purchasedChapterUuids + status.unlockedChapterUuids + status.purchasedVolumeUuids).toSet()
                } else {
                    response.close()
                    emptySet()
                }
            }

            val url = "$apiUrl/mangas/${manga.url}/chapters/v4".toHttpUrl().newBuilder()
                .addQueryParameter("order", "ascending")
                .addQueryParameter("count", "1000")
                .build()
            val result = client.get(url).parseAs<ChapterDto>()

            val hideLocked = preferences.getBoolean(HIDE_LOCKED_PREF_KEY, false)
            val unlocked = unlockedIds.await()
            val now = System.currentTimeMillis()
            result.chapters
                .map { it to (it.uuid !in unlocked && it.volumeUuid !in unlocked && !it.isFree(now)) }
                .filter { (_, isLocked) -> !hideLocked || !isLocked }
                .map { (chapter, isLocked) -> chapter.toSChapter(slug, result.volumes[chapter.volumeUuid], isLocked) }
                .reversed()
        }

        SMangaUpdate(details.await(), chapterList.await())
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.memo["slug"]!!.string}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.memo["slug"]!!.string}/read/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val result = client.get("$apiUrl/chapters/${chapter.url}/pages/v1").parseAs<PageListDto>()
        return result.data.pages.mapIndexed { i, page ->
            Page(i, imageUrl = "${page.image.maxResUrl()}#drm")
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        AccessTypeFilter(),
        PublisherFilter(),
        GenreFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_LOCKED_PREF_KEY
            title = "Hide Locked Chapters"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val HIDE_LOCKED_PREF_KEY = "hide_locked"
        private const val API_HOST = "production.api.azuki.co"
        private const val ORGANIZATION_KEY = "199e5a19-a236-49f5-81f4-43d4a541748a"
    }
}
