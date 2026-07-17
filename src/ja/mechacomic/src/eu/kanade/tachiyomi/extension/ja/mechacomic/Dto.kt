package eu.kanade.tachiyomi.extension.ja.mechacomic

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
class RankingResponse(
    val pagination: Pagination,
    @SerialName("ranking_books") val rankingBooks: List<RankingBook>,
)

@Serializable
class Pagination(
    @SerialName("current_page") private val currentPage: Int,
    @SerialName("per_page") private val perPage: Int,
    @SerialName("total_entries") private val totalEntries: Int,
) {
    fun hasNextPage() = currentPage * perPage < totalEntries
}

@Serializable
class RankingBook(
    private val path: String,
    @SerialName("jacket_image_path") private val jacketImagePath: String?,
    private val name: String,
) {
    fun toSManga(cdnUrl: String) = SManga.create().apply {
        val id = (cdnUrl + path).toHttpUrl().pathSegments.last()
        url = id
        title = name
        thumbnail_url = "$cdnUrl/$jacketImagePath"
    }
}

@Serializable
class CryptoKey(
    val cryptokey: String,
)

@Serializable
class ContentData(
    val images: Map<String, List<ImageData>>,
)

@Serializable
class ImageData(
    val src: String,
)
