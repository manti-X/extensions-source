package eu.kanade.tachiyomi.extension.ja.musicbookjp

import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class MusicBookJp :
    KeiSource(),
    ConfigurableSource {
    override val lang: String = "ja"
    override val name = "Music Book Japan"
    override val baseUrl = "https://music-book.jp"

    private val preferences by getPreferencesLazy()

    // mobile ua gives infinite redirects
    override fun Headers.Builder.configureHeaders() = set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(ImageInterceptor())
        addInterceptor {
            val request = it.request()
            val response = it.proceed(request)
            if (response.request.url.pathSegments.last() == "oversea.html") {
                throw IOException("This service is only available in Japan.")
            }
            response
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/comic/store/rankingDetails".toHttpUrl().newBuilder()
            .addQueryParameter("rankingType", "D")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/comic/store/newRelease".toHttpUrl().newBuilder()
            .addQueryParameter("tab", "male")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filterList: FilterList): MangasPage {
        val url = "$baseUrl/comic/store/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).toMangasPage()
    }

    private fun Response.toMangasPage(): MangasPage {
        val document = this.asJsoup()
        val script = document.selectFirst("script:containsData(__NUXT__)")!!.data()
        val thumbnails = THUMBNAIL_REGEX.findAll(script).joinToString(",", "[", "]") {
            """{"id":"${it.groupValues[1]}","thumbnail":${it.groupValues[2]}}"""
        }.parseAs<List<RankingThumbnail>>().associate { it.idThumbnail }

        val mangas = document.select("a.index-link:has(.ranking-title), a.index-link:has(span.title)").map {
            SManga.create().apply {
                val seriesId = it.absUrl("href").toHttpUrl().queryParameter("seriesId")!!
                setUrlWithoutDomain(seriesId)
                title = it.selectFirst(".ranking-title .text, span.title")!!.text()
                thumbnail_url = thumbnails[seriesId]
            }
        }

        val hasNextPage = document.selectFirst("ul.pager li.pager-box.active ~ li.pager-box") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val listUrl = getMangaUrl(manga).toHttpUrl().newBuilder()
            .addQueryParameter("sort", "desc")
            .addQueryParameter("tab", "book")
            .build()

        var page = 1
        var document = client.get(listUrl.newBuilder().addQueryParameter("page", page.toString()).build()).asJsoup()

        val details = if (fetchDetails) {
            val magazineName = document.selectFirst("a[href*=magazineDetails]")?.text()
            val publisherName = document.selectFirst("a[href*=publisherDetails]")?.text()
            SManga.create().apply {
                title = document.selectFirst("article.main h1")!!.text()
                thumbnail_url = document.selectFirst("img.copy-guard-image[src*=comicsp.file]")?.absUrl("src")?.toHttpUrl()?.newBuilder()?.query(null)?.build().toString()
                author = document.selectFirst("a[href*=authorDetails]")?.text()
                genre = document.select("a[href*=genreDetails]").map { it.text() }.distinct().joinToString()
                description = buildString {
                    document.selectFirst("section.series-outline-section p.text")?.text()?.let(::append)
                    publisherName?.takeIf { it.isNotEmpty() }?.let { append("\n\nPublisher: $it") }
                    magazineName?.takeIf { it.isNotEmpty() }?.let { append("\n\nMagazine: $it") }
                }
            }
        } else {
            manga
        }

        val chapterList = mutableListOf<SChapter>()
        if (fetchChapters) {
            val hideLocked = preferences.getBoolean(HIDE_LOCKED_PREF_KEY, false)
            while (true) {
                chapterList += document.select("ul.item-list li.item").mapNotNull {
                    val readContents = it.selectFirst("a[href*=readContents]")
                    val locked = readContents == null
                    if (locked && hideLocked) return@mapNotNull null

                    SChapter.create().apply {
                        val lock = if (locked) "🔒 " else ""
                        setUrlWithoutDomain(
                            readContents?.absUrl("href")?.toHttpUrl()?.queryParameter("itemId")
                                ?: it.selectFirst("img")!!.absUrl("src").toHttpUrl().pathSegments[1],
                        )
                        name = lock + it.selectFirst("span.title")!!.text()
                    }
                }

                val hasNextPage = document.selectFirst("ul.pager li.pager-box.active ~ li.pager-box") != null
                if (!hasNextPage) break
                page++
                document = client.get(listUrl.newBuilder().addQueryParameter("page", page.toString()).build()).asJsoup()
            }
        }

        return SMangaUpdate(details, if (fetchChapters) chapterList else chapters)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/store/seriesDetails?seriesId=${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/comic/bv/index.html?cid=${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val a001 = CharArray(6) { SCID_CHARS[Random.nextInt(SCID_CHARS.length)] }.concatToString()
        val viewer = client.post("$baseUrl/comic/eblieva/bvApi/pageList", ViewerRequestBody(a001, chapter.url).toJsonRequestBody()).parseAs<ViewerResponse>()
        val pageData = viewer.data.parseAs<PageData> { it.decryptResponse(a001, viewer.sckb) }
        val display = pageData.displaySettingInfo.first()
        val layoutUrl = display.layoutUrl
        val total = client.get("$layoutUrl/layoutInfo.json").body.string().let { PAGE_COUNT_REGEX.findAll(it).last().groupValues[1].toInt() }
        val fragments = HashMap<Int, String>()

        return (1..total).map {
            val group = minOf((it - 1) / 5, display.config.paramList.lastIndex)
            val fragment = fragments.getOrPut(group) {
                val (mapping, gridWidth, gridHeight) = display.config.paramList[group].toScramble()
                FragmentData(mapping, gridWidth, gridHeight).toJsonString()
            }
            Page(it - 1, imageUrl = "$layoutUrl/${it.toString().padStart(5, '0')}.jpg#$fragment")
        }
    }

    private fun String.decryptResponse(a001: String, sckb: String): String {
        val key = MessageDigest.getInstance("MD5").digest((a001 + "bvhm" + sckb).toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return cipher.doFinal(Base64.decode(this, Base64.DEFAULT)).toString(Charsets.UTF_8)
    }

    private fun ScrambleParam.toScramble(): Triple<IntArray, Int, Int> {
        val nums = coordinates.split(",").map(String::toInt)
        val seq = IntArray(nums.size).also {
            it[0] = nums[0]
            it[1] = nums[1]
        }
        for (e in 2 until nums.size) seq[e] = nums[e] + seq[e - 1] - seq[e - 2]

        val gridWidth = seq[seq.lastIndex - 1]
        val gridHeight = seq[seq.lastIndex]
        val mapping = IntArray(gridWidth * gridHeight)
        var t = 1
        for (k in mapping.indices) {
            if (t >= mapping.size) t = 0
            mapping[seq[k] - 1] = t
            t += 2
        }
        return Triple(mapping, gridWidth, gridHeight)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_LOCKED_PREF_KEY
            title = "Hide Locked Chapters"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = emptyList()

    companion object {
        private const val HIDE_LOCKED_PREF_KEY = "hide_locked"
        private val THUMBNAIL_REGEX = Regex("""id:"(\d+)"[^}]*?thumbnail:("[^"]*")""")
        private val PAGE_COUNT_REGEX = Regex("""::(\d+)""")
        private const val SCID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val IV = "bvinitialvectolv".toByteArray()
    }
}
