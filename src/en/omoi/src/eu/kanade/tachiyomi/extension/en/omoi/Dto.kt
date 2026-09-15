package eu.kanade.tachiyomi.extension.en.omoi

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

private val COVER_WIDTH_REGEX = Regex("""/\d+_""")

@Serializable
class DetailsDto(
    private val slug: String,
    private val uuid: String,
    private val name: String,
    @SerialName("short_description") private val shortDescription: String?,
    @SerialName("is_complete") private val isComplete: Boolean?,
    private val image: Image?,
    private val tags: List<String>?,
    private val creators: List<Creator>?,
    private val credits: String?,
    @SerialName("release_schedule") private val releaseSchedule: String?,
    @SerialName("alt_titles") private val altTitles: List<AltTitle>?,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = uuid
        title = name
        thumbnail_url = image?.maxResUrl()?.replace(COVER_WIDTH_REGEX, "/2400_")
        author = creators?.joinToString { it.name }
        description = buildString {
            append(shortDescription)
            if (!credits.isNullOrBlank()) {
                append("\n\n$credits")
            }
            if (!altTitles.isNullOrEmpty()) {
                append("\n\nAlternative Titles:")
                altTitles
                    .map { it.name }
                    .forEach { append("\n$it") }
            }
            if (!releaseSchedule.isNullOrBlank()) {
                append("\n\n$releaseSchedule")
            }
        }
        genre = tags?.joinToString()
        status = if (isComplete == true) SManga.COMPLETED else SManga.ONGOING
        memo = buildJsonObject {
            put("slug", slug)
        }
    }
}

@Serializable
class Image(
    private val webp: List<Webp>,
) {
    fun maxResUrl() = webp.maxBy { it.width }.url
}

@Serializable
class Webp(
    val url: String,
    val width: Int,
)

@Serializable
class Creator(
    val name: String,
)

@Serializable
class AltTitle(
    val name: String,
)

@Serializable
class ChapterDto(
    val chapters: List<Chapter>,
    @SerialName("volume_uuid_to_volume") val volumes: Map<String, Volume>,
)

@Serializable
class Chapter(
    val uuid: String,
    private val title: String?,
    private val label: String,
    @SerialName("volume_uuid") val volumeUuid: String?,
    @SerialName("release_date") private val releaseDate: String?,
    @SerialName("free_published_date") private val freePublishedDate: String?,
    @SerialName("free_unpublished_date") private val freeUnpublishedDate: String?,
    @SerialName("is_upcoming") private val isUpcoming: Boolean?,
) {
    fun isFree(now: Long) = freePublishedDate != null &&
        Instant.tryParse(freePublishedDate) <= now &&
        (freeUnpublishedDate == null || Instant.tryParse(freeUnpublishedDate) > now)

    fun toSChapter(slug: String, volume: Volume?, isLocked: Boolean): SChapter = SChapter.create().apply {
        url = uuid
        val chapter = listOfNotNull(volume?.let { "Vol. ${it.orderNumber}" }, "Chapter $label").joinToString(" ")
        val fullTitle = if (title != null) "$chapter - $title" else chapter
        val upcoming = if (isUpcoming == true) "$fullTitle - [Upcoming]" else fullTitle
        name = if (isLocked) "🔒 $upcoming" else upcoming
        date_upload = Instant.tryParse(releaseDate)
        memo = buildJsonObject {
            put("slug", slug)
        }
    }
}

@Serializable
class Volume(
    @SerialName("order_number") val orderNumber: Int,
)

@Serializable
class UserMangaStatusDto(
    @SerialName("purchased_chapter_uuids")
    val purchasedChapterUuids: List<String> = emptyList(),
    @SerialName("unlocked_chapter_uuids")
    val unlockedChapterUuids: List<String> = emptyList(),
    @SerialName("purchased_volume_uuids")
    val purchasedVolumeUuids: List<String> = emptyList(),
)

@Serializable
class PageListDto(
    val data: PageDataDto,
)

@Serializable
class PageDataDto(
    val pages: List<PageDto>,
)

@Serializable
class PageDto(
    val image: Image,
)
