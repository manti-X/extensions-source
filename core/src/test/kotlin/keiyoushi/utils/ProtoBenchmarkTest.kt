package keiyoushi.utils

import eu.kanade.tachiyomi.extension.all.mangaplus.AllTitlesGroup
import eu.kanade.tachiyomi.extension.all.mangaplus.AllTitlesV3Entry
import eu.kanade.tachiyomi.extension.all.mangaplus.Chapter
import eu.kanade.tachiyomi.extension.all.mangaplus.ChapterListGroup
import eu.kanade.tachiyomi.extension.all.mangaplus.LatestChapter
import eu.kanade.tachiyomi.extension.all.mangaplus.MangaPlusResponse
import eu.kanade.tachiyomi.extension.all.mangaplus.RankedTitle
import eu.kanade.tachiyomi.extension.all.mangaplus.TagName
import eu.kanade.tachiyomi.extension.all.mangaplus.Title
import eu.kanade.tachiyomi.extension.all.mangaplus.UpdatedTitle
import eu.kanade.tachiyomi.extension.all.mangaplus.UpdatedTitleGroup
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.UUID

private val proto = ProtoBuf { }
private const val API_URL = "https://jumpg-webapi.tokyo-cdn.com/api"
private const val LANG = "eng"

class ProtoBenchmarkTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            Injekt.addSingleton(ProtoBuf { })
        }
    }

    private class Endpoint(
        val name: String,
        val fileName: String,
        val url: String,
    )

    private val endpoints = listOf(
        Endpoint("web_homeV4 (latest)", "web_homeV4.bin", "$API_URL/web/web_homeV4?lang=$LANG&clang=$LANG"),
        Endpoint("rankingV2 (popular)", "rankingV2.bin", "$API_URL/title_list/rankingV2?lang=$LANG&type=hottest&clang=$LANG"),
        Endpoint("allV2 (full catalog)", "allV2.bin", "$API_URL/title_list/allV2"),
        Endpoint("all_v3 serializing", "all_v3_serializing.bin", "$API_URL/title_list/all_v3?type=serializing&lang=$LANG&clang=$LANG"),
        Endpoint("all_v3 completed", "all_v3_completed.bin", "$API_URL/title_list/all_v3?type=completed&lang=$LANG&clang=$LANG"),
        Endpoint("title_detailV3", "title_detailV3.bin", "$API_URL/title_detailV3?title_id=100191&clang=$LANG"),
    )

    private val cacheDir = File("build/proto-payloads")

    private class Payload(val name: String, val bytes: ByteArray)

    private class Measurement(
        val name: String,
        val sizeBytes: Int,
        val byteArrayNanos: LongArray,
        val streamingNanos: LongArray,
        val byteArrayAlloc: Long,
        val streamingAlloc: Long,
    )

    // decoding

    @Test
    fun benchmarkDecoding() {
        printEnvironment("decoder")

        val results = mutableListOf<Measurement>()
        for (payload in loadPayloads()) {
            val expected = fingerprint(proto.decodeFromByteArray(MangaPlusResponse.serializer(), payload.bytes))
            val decoded = Buffer().write(payload.bytes).decodeProto<MangaPlusResponse>()
            assertEquals("Decoders disagree on ${payload.name}", expected, fingerprint(decoded))

            results += measureDecoding(payload)
            println("  ${payload.name}: ${format(payload.bytes.size)} bytes, decoders agree")
        }

        assumeTrue("No payload available, skipping benchmark", results.isNotEmpty())

        printDetail(results, "decode", "bytes in")
        printMarkdownTable(results, "Payload", "decoding")
    }

    private fun measureDecoding(payload: Payload): Measurement {
        val timedRounds = roundsFor(payload.bytes.size)
        var checksum = 0L

        repeat(timedRounds / 4) {
            checksum += decodeFromByteArray(payload.bytes)
            checksum += decodeFromSource(payload.bytes)
        }

        val byteArrayNanos = LongArray(timedRounds) {
            val start = System.nanoTime()
            checksum += decodeFromByteArray(payload.bytes)
            System.nanoTime() - start
        }
        val streamingNanos = LongArray(timedRounds) {
            val start = System.nanoTime()
            checksum += decodeFromSource(payload.bytes)
            System.nanoTime() - start
        }

        val byteArrayAlloc = allocatedPerOp { decodeFromByteArray(payload.bytes) }
        val streamingAlloc = allocatedPerOp { decodeFromSource(payload.bytes) }

        blackhole(checksum)
        return Measurement(payload.name, payload.bytes.size, byteArrayNanos, streamingNanos, byteArrayAlloc, streamingAlloc)
    }

    private fun decodeFromByteArray(payload: ByteArray): Long = fingerprintCheap(proto.decodeFromByteArray(MangaPlusResponse.serializer(), payload))

    // the Buffer fill counts towards the streaming side: a real response arrives as a source
    private fun decodeFromSource(payload: ByteArray): Long = fingerprintCheap(Buffer().write(payload).decodeProto<MangaPlusResponse>())

    // encoding

    @Test
    fun benchmarkEncoding() {
        printEnvironment("encoder")

        val results = mutableListOf<Measurement>()
        for (payload in loadPayloads()) {
            val value = proto.decodeFromByteArray(MangaPlusResponse.serializer(), payload.bytes)

            // both encoders must land on the same bytes before any timing is worth reporting
            val theirs = proto.encodeToByteArray(MangaPlusResponse.serializer(), value)
            val ours = encodeToBuffer(value).readByteArray()
            assertArrayEquals("Encoders disagree on ${payload.name}", theirs, ours)

            results += measureEncoding(payload.name, value, ours.size)
            println("  ${payload.name}: ${format(ours.size)} bytes out, encoders agree")
        }

        assumeTrue("No payload available, skipping benchmark", results.isNotEmpty())

        printDetail(results, "encode", "bytes out")
        printMarkdownTable(results, "Encoded", "encoding")
    }

    private fun measureEncoding(name: String, value: MangaPlusResponse, encodedSize: Int): Measurement {
        val timedRounds = roundsFor(encodedSize)
        var checksum = 0L

        repeat(timedRounds / 4) {
            checksum += encodeToByteArray(value)
            checksum += encodeToSink(value)
        }

        val byteArrayNanos = LongArray(timedRounds) {
            val start = System.nanoTime()
            checksum += encodeToByteArray(value)
            System.nanoTime() - start
        }
        val streamingNanos = LongArray(timedRounds) {
            val start = System.nanoTime()
            checksum += encodeToSink(value)
            System.nanoTime() - start
        }

        val byteArrayAlloc = allocatedPerOp { encodeToByteArray(value) }
        val streamingAlloc = allocatedPerOp { encodeToSink(value) }

        blackhole(checksum)
        return Measurement(name, encodedSize, byteArrayNanos, streamingNanos, byteArrayAlloc, streamingAlloc)
    }

    private fun encodeToByteArray(value: MangaPlusResponse): Long = proto.encodeToByteArray(MangaPlusResponse.serializer(), value).size.toLong()

    // what toRequestBodyProto does: straight into the sink, with no intermediate array
    private fun encodeToSink(value: MangaPlusResponse): Long = encodeToBuffer(value).size

    private fun encodeToBuffer(value: MangaPlusResponse): Buffer = Buffer().also { it.encodeProto(MangaPlusResponse.serializer(), value, false) }

    // payloads

    private fun loadPayloads(): List<Payload> {
        val client by lazy { OkHttpClient() }
        val session = UUID.randomUUID().toString()
        var fetched = false

        return endpoints.mapNotNull { endpoint ->
            val cached = File(cacheDir, endpoint.fileName)
            val bytes = if (cached.isFile) {
                cached.readBytes()
            } else {
                if (fetched) Thread.sleep(1_100)
                fetched = true
                fetch(client, session, endpoint)?.also {
                    cacheDir.mkdirs()
                    cached.writeBytes(it)
                }
            }
            bytes?.let { Payload(endpoint.name, it) }
        }
    }

    private fun fetch(client: OkHttpClient, session: String, endpoint: Endpoint): ByteArray? {
        if (System.getProperty("proto.benchmark.offline") == "true") {
            println("  ${endpoint.name}: skipped (offline, no cached payload)")
            return null
        }

        val request = Request.Builder()
            .url(endpoint.url)
            .cacheControl(CacheControl.Builder().noCache().build())
            .header("SESSION-TOKEN", session)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                response.body.bytes()
            }
        }.onFailure {
            println("  ${endpoint.name}: skipped (${it.message})")
        }.getOrNull()
    }

    // measurement plumbing

    private fun roundsFor(sizeBytes: Int) = (40_000_000 / sizeBytes.coerceAtLeast(1)).coerceIn(200, 2_000)

    private fun blackhole(checksum: Long) {
        if (checksum == Long.MIN_VALUE) println("unreachable, keeps the JIT from eliminating the work")
    }

    private fun allocatedPerOp(block: () -> Unit): Long {
        val reader = AllocationReader.instance ?: return -1

        repeat(50) { block() }
        val rounds = 200
        val before = reader.currentThreadAllocatedBytes()
        if (before < 0) return -1
        repeat(rounds) { block() }
        val after = reader.currentThreadAllocatedBytes()
        return if (after < 0) -1 else (after - before) / rounds
    }

    private class AllocationReader(
        private val bean: Any,
        private val getThreadAllocatedBytes: java.lang.reflect.Method,
    ) {
        fun currentThreadAllocatedBytes(): Long = runCatching {
            @Suppress("DEPRECATION")
            getThreadAllocatedBytes.invoke(bean, Thread.currentThread().id) as Long
        }.getOrDefault(-1L)

        companion object {
            val instance: AllocationReader? by lazy {
                runCatching {
                    val beanType = Class.forName("com.sun.management.ThreadMXBean")
                    val bean = Class.forName("java.lang.management.ManagementFactory")
                        .getMethod("getThreadMXBean")
                        .invoke(null)
                        ?: return@runCatching null

                    if (!beanType.isInstance(bean)) return@runCatching null

                    val supported = beanType.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean
                    if (!supported) return@runCatching null

                    beanType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                        .invoke(bean, true)

                    AllocationReader(
                        bean,
                        beanType.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType),
                    )
                }.getOrNull()
            }
        }
    }

    // fingerprints

    private fun fingerprint(response: MangaPlusResponse): String {
        val success = response.success ?: return "error=${response.error?.englishPopup?.subject}"
        val parts = mutableListOf<String>()

        success.allTitlesViewV3?.let { view ->
            parts += "v3.tags=" + view.tags.joinToString(",") { it.tag() }
            parts += "v3.titles=" + view.titles.joinToString(",") { entry: AllTitlesV3Entry ->
                entry.title.short() + "[" + entry.genres.joinToString("|") { it.tag() } + "]"
            }
        }
        success.allTitlesView?.let { view ->
            parts += "all.groups=" + view.allTitlesGroup.joinToString(",") { group: AllTitlesGroup ->
                group.titles.joinToString("|") { it.short() }
            }
        }
        success.titleRankingView?.let { view ->
            parts += "rank=" + view.rankedTitles.joinToString(",") { ranked: RankedTitle ->
                ranked.titles.joinToString("|") { it.short() }
            }
        }
        success.webHomeView?.let { view ->
            parts += "home.groups=" + view.groups.joinToString(",") { group: UpdatedTitleGroup ->
                group.titles.joinToString("|") { it.short() }
            }
            parts += "home.featured=" + (view.featured?.title?.short() ?: "none")
        }
        success.titleDetailView?.let { view ->
            parts += "detail=" + view.title.short() +
                "|overview=${view.overview.length}" +
                "|period=${view.viewingPeriodDescription.length}" +
                "|absence=${view.nonAppearanceInfo.length}" +
                "|genres=" + view.genreList.joinToString("/") { it.tag() } +
                "|chapters=" + view.chapterListGroup.joinToString("/") { group: ChapterListGroup ->
                    (group.firstChapterList + group.lastChapterList).joinToString("+") { it.short() }
                }
        }
        success.mangaViewer?.let { viewer ->
            parts += "viewer.titleId=${viewer.titleId}|token=${viewer.viewToken?.length}" +
                "|pages=" + viewer.pages.joinToString("/") { page ->
                    page.mangaPage?.let { "${it.imageUrl.length}:${it.encryptionKey?.length}" } ?: "null"
                }
        }
        return parts.joinToString("\n")
    }

    private fun Title.short() = "$titleId:$name:${author?.length}:${portraitImageUrl.length}:$language"

    private fun TagName.tag() = "$name/$slug"

    private fun UpdatedTitle.short() = "$updatedAt{" +
        latestChapters.joinToString("+") { chapter: LatestChapter -> chapter.title.short() } + "}"

    private fun Chapter.short() = "$chapterId:$name:${subTitle?.length}:$startTimeStamp"

    private fun fingerprintCheap(response: MangaPlusResponse): Long {
        val success = response.success ?: return 0L
        var sum = 0L
        success.allTitlesViewV3?.titles?.forEach { sum += it.title.titleId + it.genres.size }
        success.allTitlesView?.allTitlesGroup?.forEach { group -> group.titles.forEach { sum += it.titleId } }
        success.titleRankingView?.rankedTitles?.forEach { ranked -> ranked.titles.forEach { sum += it.titleId } }
        success.webHomeView?.groups?.forEach { group -> group.titles.forEach { sum += it.updatedAt + it.latestChapters.size } }
        success.titleDetailView?.let { sum += it.title.titleId + it.chapterList.size + it.genreList.size }
        success.mangaViewer?.let { sum += it.pages.size + (it.titleId ?: 0) }
        return sum
    }

    // reporting

    private fun printEnvironment(what: String) {
        println("Protobuf $what benchmark")
        println("  JVM:  ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        println("  OS:   ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        println("  CPUs: ${Runtime.getRuntime().availableProcessors()}")
        println()
        println("Payloads:")
    }

    private fun printDetail(results: List<Measurement>, verb: String, sizeLabel: String) {
        println()
        println("Per endpoint timings:")
        for (result in results) {
            println()
            println("${result.name} (${format(result.sizeBytes)} $sizeLabel, ${result.byteArrayNanos.size} rounds)")
            report("  ByteArray $verb ", result.byteArrayNanos)
            report("  Streaming $verb ", result.streamingNanos)
            if (result.byteArrayAlloc >= 0) {
                println(
                    "  allocated per $verb:   ByteArray %s bytes, Streaming %s bytes".format(
                        format(result.byteArrayAlloc),
                        format(result.streamingAlloc),
                    ),
                )
            } else {
                println("  allocated per $verb:   not available on this runtime")
            }
        }
    }

    private fun printMarkdownTable(results: List<Measurement>, sizeHeader: String, what: String) {
        println()
        println("Summary of $what (paste-ready):")
        println()
        println("| Endpoint | $sizeHeader | ByteArray | Streaming | Time | ByteArray alloc | Streaming alloc | Alloc |")
        println("|---|---:|---:|---:|---:|---:|---:|---:|")
        for (result in results) {
            val oldMs = result.byteArrayNanos.average() / 1_000_000.0
            val newMs = result.streamingNanos.average() / 1_000_000.0
            val timeDelta = (1.0 - newMs / oldMs) * 100.0
            val allocText = if (result.byteArrayAlloc < 0) {
                "n/a | n/a | n/a"
            } else {
                val allocDelta = (1.0 - result.streamingAlloc.toDouble() / result.byteArrayAlloc) * 100.0
                "%s | %s | %s".format(
                    format(result.byteArrayAlloc),
                    format(result.streamingAlloc),
                    signed(allocDelta),
                )
            }
            println(
                "| %s | %s | %.3f ms | %.3f ms | %s | %s |".format(
                    result.name,
                    format(result.sizeBytes),
                    oldMs,
                    newMs,
                    signed(timeDelta),
                    allocText,
                ),
            )
        }
    }

    private fun report(label: String, nanos: LongArray) {
        val sorted = nanos.sortedArray()
        println(
            "$label avg=%.3fms p50=%.3fms p95=%.3fms min=%.3fms max=%.3fms".format(
                sorted.average() / 1_000_000.0,
                sorted[sorted.size / 2] / 1_000_000.0,
                sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)] / 1_000_000.0,
                sorted.first() / 1_000_000.0,
                sorted.last() / 1_000_000.0,
            ),
        )
    }

    private fun format(value: Number) = "%,d".format(value.toLong())

    private fun signed(percent: Double) = if (percent >= 0) "%.1f%% faster".format(percent) else "%.1f%% slower".format(-percent)
}
