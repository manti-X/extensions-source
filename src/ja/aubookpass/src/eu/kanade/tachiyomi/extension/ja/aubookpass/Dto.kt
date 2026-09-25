package eu.kanade.tachiyomi.extension.ja.aubookpass

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
class SearchResponse(
    val totalHits: Int,
    val hits: List<Hit>,
)

@Serializable
class Hit(
    private val id: String,
    private val title: String,
    private val imgUrl: String?,
) {
    fun toSManga() = SManga.create().apply {
        url = id
        title = this@Hit.title
        thumbnail_url = imgUrl?.replace("_l.jpg", "_xl.jpg")
    }
}

@Serializable
class DetailsResponse(
    val collectionInfo: Collection,
)

@Serializable
class Collection(
    val itemInfo: List<ItemInfo>,
)

@Serializable
class ItemInfo(
    private val collectionInfo: CollectionInfo,
    val contentInfo: ContentInfo,
) {
    fun toSManga() = SManga.create().apply {
        title = collectionInfo.collectionName
        author = contentInfo.authorInfo?.joinToString { it.authorName }
        description = buildString {
            contentInfo.descriptionLong?.let { append(it.replace(BR_REGEX, "\n")) }
            collectionInfo.collectionNameKana?.let { append("\n\nAlternative Title: $it") }
            contentInfo.magazineName?.takeIf(String::isNotEmpty)?.let { append("\n\nMagazine: $it") }
            contentInfo.labelName?.takeIf(String::isNotEmpty)?.let { append("\n\nLabel: $it") }
            contentInfo.publisherName?.takeIf(String::isNotEmpty)?.let { append("\n\nPublisher: $it") }
            if (contentInfo.isAdult == 1) append("\n\nRating: 18+")
        }
        genre = contentInfo.bpAssortTagGenreInfo?.joinToString { it.genreName }
        status = if (collectionInfo.isCompleted == 1) SManga.COMPLETED else SManga.ONGOING
        thumbnail_url = contentInfo.thumbnailFileUri?.replace("http://", "https://")?.replace("_l.jpg", "_xl.jpg")
    }
}

@Serializable
class CollectionInfo(
    val collectionName: String,
    val collectionNameKana: String?,
    val isCompleted: Int?,
)

@Serializable
class ContentInfo(
    val aid: String,
    private val collectionIndex: Int?,
    private val itemName: String,
    val authorInfo: List<AuthorInfo>?,
    val descriptionLong: String?,
    val publisherName: String?,
    val labelName: String?,
    val magazineName: String?,
    val bpAssortTagGenreInfo: List<BpAssortTagGenreInfo>?,
    val isAdult: Int?,
    private val priceInclTax: Int?,
    val thumbnailFileUri: String?,
    private val orgSalesFrom: String?,
    private val deviceInfo: List<DeviceInfo>?,
) {
    // Br* devices are the browser viewer, the others are app downloads
    private val browserDevice: DeviceInfo?
        get() = deviceInfo?.firstOrNull { it.bpDeviceId.startsWith("Br") }

    private val isFree: Boolean
        get() = priceInclTax == 0 || browserDevice?.isFreePromotion == true

    val isPreview: Boolean
        get() = !isFree && browserDevice?.hasSample == 1

    val isLocked: Boolean
        get() = !isFree && !isPreview

    fun toSChapter(isPurchased: Boolean): SChapter = SChapter.create().apply {
        val isSample = isPreview && !isPurchased
        val lock = if (isLocked && !isPurchased) "🔒 " else ""
        val preview = if (isSample) "🔒 (Preview) " else ""
        url = aid
        name = lock + preview + itemName
        date_upload = dateFormat.tryParseDateTime(orgSalesFrom ?: browserDevice?.salesFrom)
        chapter_number = collectionIndex?.toFloat() ?: -1f
        memo = buildJsonObject {
            put("isSample", isSample)
        }
    }
}

@Serializable
class PurchaseResponse(
    val retrievedData: PurchaseData,
)

@Serializable
class PurchaseData(
    val itemInfo: List<PurchaseInfo>?,
)

@Serializable
class PurchaseInfo(
    val aid: String,
    private val purchasedFlag: String?,
) {
    val isPurchased: Boolean
        get() = purchasedFlag == "1"
}

@Serializable
class AuthorInfo(
    val authorName: String,
)

@Serializable
class BpAssortTagGenreInfo(
    val genreName: String,
)

@Serializable
class DeviceInfo(
    val bpDeviceId: String,
    val hasSample: Int?,
    private val promotionPriceInclTax: Int?,
    private val promotionFrom: String?,
    private val promotionTo: String?,
    val salesFrom: String?,
) {
    val isFreePromotion: Boolean
        get() = promotionPriceInclTax == 0 &&
            System.currentTimeMillis() in dateFormat.tryParseDateTime(promotionFrom)..<dateFormat.tryParseDateTime(promotionTo)
}

@Serializable
class TokenResponse(
    val authToken: String,
    val uuid: String,
    val iid: String,
)

@Serializable
class MetaResponse(
    val data: MetaData,
)

@Serializable
class MetaData(
    val type: String,
    val page: MetaPage,
)

@Serializable
class MetaPage(
    val all: Int?,
)

@Serializable
class ImageResponse(
    val data: Data,
)

@Serializable
class Data(
    val url: String,
    val meta: List<Meta>,
)

@Serializable
class Meta(
    val isCrypted: Boolean,
    val isScrambled: Boolean,
    val mimetype: String,
    val width: Int,
    val height: Int,
)

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.of("Asia/Tokyo"))
private val BR_REGEX = Regex("""<br\s*/?>""")
