package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.FilterChange
import suwayomi.tachidesk.graphql.types.updateFilterList
import suwayomi.tachidesk.manga.impl.MangaUrlResolver
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.server.JavalinSetup.future
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import eu.kanade.tachiyomi.source.Source as CatalogueSource
import eu.kanade.tachiyomi.source.model.Page as SourcePage
import suwayomi.tachidesk.manga.impl.Source as SourceRegistry

private suspend fun <T> bridgeSourceCall(block: suspend () -> T): T =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Source exceptions can contain manga/chapter URLs; don't persist them in server logs.
        throw IllegalStateException("Installed source request failed")
    }

/** Stateless bridge operations. These call installed Source objects directly and never insert manga,
 * chapter, page, library, history, or progress rows into Suwayomi's database. */
class BridgeMutation {
    enum class ListingType { SEARCH, POPULAR, LATEST }

    data class FetchSourceInput(
        val source: Long,
        val type: ListingType,
        val page: Int,
        val query: String? = null,
        val filters: List<FilterChange>? = null,
    )

    data class MangaInput(
        val source: Long,
        val url: String,
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val fetchManga: Boolean = true,
        val fetchChapters: Boolean = true,
    )

    data class PagesInput(
        val source: Long,
        val mangaUrl: String,
        val chapterUrl: String,
        val chapterName: String? = null,
        val chapterNumber: Float? = null,
        val scanlator: String? = null,
    )

    data class BridgeManga(
        val id: Int,
        val sourceId: String,
        val url: String,
        val realUrl: String,
        val title: String,
        val thumbnailUrl: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String> = emptyList(),
        val status: String = "UNKNOWN",
    )

    data class BridgeChapter(
        val id: Int,
        val url: String,
        val realUrl: String,
        val name: String,
        val uploadDate: Long,
        val chapterNumber: Float,
        val scanlator: String? = null,
    )

    data class FetchSourcePayload(
        val mangas: List<BridgeManga>,
        val hasNextPage: Boolean,
    )

    data class FetchMangaPayload(
        val manga: BridgeManga,
        val chapters: List<BridgeChapter>,
    )

    data class ResolveUrlPayload(
        val status: String,
        val manga: BridgeManga? = null,
        val message: String? = null,
    )

    data class FetchPagesPayload(
        val pages: List<String>,
    )

    @RequireAuth
    fun bridgeFetchSourceManga(
        @GraphQLIgnore userId: Int,
        input: FetchSourceInput,
    ) = future {
        require(input.page in 1..500) { "Page is outside the supported range" }
        require((input.query?.length ?: 0) <= 300) { "Search query is too long" }
        val source = httpSource(input.source)
        val filters = updateFilterList(source, input.filters)
        val result =
            bridgeSourceCall {
                when (input.type) {
                    ListingType.SEARCH -> {
                        source.getSearchManga(input.page, input.query.orEmpty(), filters)
                    }

                    ListingType.POPULAR -> {
                        source.getPopularManga(input.page)
                    }

                    ListingType.LATEST -> {
                        require(source.supportsLatest) { "Source does not support latest updates" }
                        source.getLatestUpdates(input.page)
                    }
                }
            }
        require(result.mangas.size <= MAX_MANGAS_PER_PAGE) { "Source returned too many results" }
        FetchSourcePayload(result.mangas.map { manga -> bridgeManga(input.source, source, manga) }, result.hasNextPage)
    }

    @RequireAuth
    fun bridgeFetchMangaAndChapters(
        @GraphQLIgnore userId: Int,
        input: MangaInput,
    ) = future {
        val http = httpSource(input.source)
        val manga = mangaInput(http, input)
        val update =
            bridgeSourceCall {
                http.getMangaUpdate(manga, emptyList(), fetchDetails = input.fetchManga, fetchChapters = input.fetchChapters)
            }
        require(update.chapters.size <= MAX_CHAPTERS_PER_MANGA) { "Source returned too many chapters" }
        FetchMangaPayload(
            manga = bridgeManga(input.source, http, update.manga),
            chapters = update.chapters.mapIndexed { index, chapter -> bridgeChapter(http, index, chapter) },
        )
    }

    @RequireAuth
    fun bridgeFetchChapterPages(
        @GraphQLIgnore userId: Int,
        input: PagesInput,
    ) = future {
        val http = httpSource(input.source)
        // Validate both URLs against the installed source before passing their relative paths to it.
        sourceRelativeUrl(http, input.mangaUrl)
        val chapter =
            SChapter.create().apply {
                url = sourceRelativeUrl(http, input.chapterUrl)
                name = input.chapterName.orEmpty()
                chapter_number = input.chapterNumber ?: -1f
                scanlator = input.scanlator
            }
        val pages = bridgeSourceCall { http.getPageList(chapter) }
        require(pages.size <= MAX_PAGES_PER_CHAPTER) { "Source returned too many pages" }
        FetchPagesPayload(
            pages.mapIndexed { index, page ->
                BridgeMediaToken.issue(input.source, absoluteUrl(http, page.url), page.imageUrl?.let { absoluteUrl(http, it) }, index)
            },
        )
    }

    /** Resolves a URL only through an already-installed source; it never auto-installs or adds a manga. */
    @RequireAuth
    fun bridgeResolveUrl(
        @GraphQLIgnore userId: Int,
        url: String,
    ) = future {
        require(url.length in 1..4096) { "URL is missing or too long" }
        val uri =
            MangaUrlResolver.parseHttpUri(url)
                ?: return@future ResolveUrlPayload("INVALID_URL", message = "A valid public HTTP or HTTPS URL is required")
        val matching =
            SourceRegistry
                .getSourceList()
                .mapNotNull { metadata ->
                    val id = metadata.id.toLongOrNull() ?: return@mapNotNull null
                    val http = GetSource.getSourceOrNull(id) as? HttpSource ?: return@mapNotNull null
                    val base = MangaUrlResolver.parseHttpUri(http.baseUrl) ?: return@mapNotNull null
                    if (!sameOrigin(uri, base) ||
                        !MangaUrlResolver.matchesBasePath(uri.rawPath.orEmpty(), base.rawPath.orEmpty())
                    ) {
                        return@mapNotNull null
                    }
                    Triple(id, http, base)
                }.maxByOrNull {
                    it.third.rawPath
                        .orEmpty()
                        .trimEnd('/')
                        .length
                }
                ?: return@future ResolveUrlPayload("NO_SOURCE_FOR_URL", message = "Install the matching catalog extension first")

        val (sourceId, http, base) = matching
        val manga =
            SManga.create().apply {
                this.url = MangaUrlResolver.relativePath(uri, base)
                title = ""
            }
        val resolved = bridgeSourceCall { http.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga }
        ResolveUrlPayload("FOUND", bridgeManga(sourceId, http, resolved))
    }

    private suspend fun source(sourceId: Long): CatalogueSource = GetSource.getSourceOrNull(sourceId) ?: error("Source is not installed")

    private suspend fun httpSource(sourceId: Long): HttpSource =
        source(sourceId) as? HttpSource ?: error("Bridge supports online HTTP sources only")

    private fun mangaInput(
        http: HttpSource,
        input: MangaInput,
    ): SManga {
        require(input.url.length in 1..4096) { "URL is missing or too long" }
        val relative = sourceRelativeUrl(http, input.url)
        return SManga.create().apply {
            url = relative
            title = input.title.orEmpty()
            thumbnail_url = input.thumbnailUrl?.let { absoluteUrl(http, it) }
            author = input.author
            artist = input.artist
            description = input.description
        }
    }

    private fun bridgeManga(
        sourceId: Long,
        http: HttpSource,
        manga: SManga,
    ): BridgeManga {
        val realUrl = http.getMangaUrl(manga)
        val thumbnail = manga.thumbnail_url?.let { absoluteUrl(http, it) }?.let { BridgeMediaToken.issue(sourceId, it, it, 0) }
        return BridgeManga(
            id = 0, // Compatibility field only; bridge identity is sourceId + canonical URL.
            sourceId = sourceId.toString(),
            url = manga.url,
            realUrl = realUrl,
            title = manga.title,
            thumbnailUrl = thumbnail,
            author = manga.author,
            artist = manga.artist,
            description = manga.description,
            genre = manga.getGenres().orEmpty(),
            status =
                when (manga.status) {
                    SManga.ONGOING -> "ONGOING"
                    SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> "COMPLETED"
                    SManga.ON_HIATUS -> "HIATUS"
                    SManga.CANCELLED -> "CANCELLED"
                    else -> "UNKNOWN"
                },
        )
    }

    private fun bridgeChapter(
        http: HttpSource,
        index: Int,
        chapter: SChapter,
    ) = BridgeChapter(
        id = index,
        url = chapter.url,
        realUrl = http.getChapterUrl(chapter),
        name = chapter.name,
        uploadDate = chapter.date_upload,
        chapterNumber = chapter.chapter_number,
        scanlator = chapter.scanlator,
    )

    private fun sourceRelativeUrl(
        http: HttpSource,
        raw: String,
    ): String {
        require(raw.length in 1..4096) { "URL is missing or too long" }
        val base = MangaUrlResolver.parseHttpUri(http.baseUrl) ?: error("Installed source has an invalid base URL")
        val parsed = MangaUrlResolver.parseHttpUri(raw)
        val target =
            parsed ?: runCatching { base.resolve(URI(raw)).normalize() }.getOrNull()
                ?: error("Invalid source URL")
        require(sameOrigin(target, base)) { "URL does not belong to the selected source" }
        require(MangaUrlResolver.matchesBasePath(target.rawPath.orEmpty(), base.rawPath.orEmpty())) {
            "URL is outside the selected source path"
        }
        return MangaUrlResolver.relativePath(target, base)
    }

    private fun absoluteUrl(
        http: HttpSource,
        raw: String,
    ): String {
        val uri = URI(raw)
        val base = URI(http.baseUrl)
        return if (uri.isAbsolute) uri.toString() else base.resolve(uri).toString()
    }

    private fun sameOrigin(
        a: URI,
        b: URI,
    ): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b) && a.rawUserInfo == null

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            else -> 80
        }

    companion object {
        const val MAX_MANGAS_PER_PAGE = 100
        const val MAX_CHAPTERS_PER_MANGA = 5000
        const val MAX_PAGES_PER_CHAPTER = 2000
    }
}

/** Signed, expiring media handles keep page/cover URLs usable without storing page rows or exposing
 * an open proxy. The signing key exists only in process memory and rotates on restart. */
object BridgeMediaToken {
    private val signingKey = ByteArray(32).also(SecureRandom()::nextBytes)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private const val TTL_SECONDS = 3600L
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

    data class Image(
        val bytes: ByteArray,
        val contentType: String,
    )

    fun issue(
        sourceId: Long,
        pageUrl: String,
        imageUrl: String?,
        index: Int,
    ): String {
        val expires = System.currentTimeMillis() / 1000 + TTL_SECONDS
        val payload =
            listOf(
                sourceId.toString(),
                expires.toString(),
                index.toString(),
                enc(pageUrl),
                enc(imageUrl.orEmpty()),
            ).joinToString("\n")
        val encoded = enc(payload)
        return "$encoded.${encoder.encodeToString(mac(payload.toByteArray(Charsets.UTF_8)))}"
    }

    suspend fun fetch(token: String): Image? {
        if (token.length > 32768) return null
        val parts = token.split('.', limit = 2)
        if (parts.size != 2) return null
        val payloadBytes = runCatching { decoder.decode(parts[0]) }.getOrNull() ?: return null
        val signature = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return null
        val expected = mac(payloadBytes)
        if (!MessageDigest.isEqual(signature, expected)) return null
        val fields = runCatching { String(payloadBytes, Charsets.UTF_8).split('\n') }.getOrNull() ?: return null
        if (fields.size != 5) return null
        val sourceId = fields[0].toLongOrNull() ?: return null
        val expires = fields[1].toLongOrNull() ?: return null
        val index = fields[2].toIntOrNull() ?: return null
        if (expires < System.currentTimeMillis() / 1000 || index !in 0..BridgeMutation.MAX_PAGES_PER_CHAPTER) return null
        val pageUrl = dec(fields[3]) ?: return null
        var imageUrl = dec(fields[4])?.ifEmpty { null }
        if (!publicHttpUrl(pageUrl)) return null
        val source = GetSource.getSourceOrNull(sourceId) as? HttpSource ?: return null
        val page = SourcePage(index, pageUrl, imageUrl)
        if (imageUrl == null) {
            imageUrl = bridgeSourceCall { source.getImageUrl(page) }
            page.imageUrl = imageUrl
        }
        if (!publicHttpUrl(imageUrl ?: return null)) return null
        val response = bridgeSourceCall { source.getImage(page) }
        response.use {
            if (!publicHttpUrl(it.request.url.toString())) return null
            val body = it.body ?: return null
            if (body.contentLength() > MAX_IMAGE_BYTES) return null
            val bytes =
                body.byteStream().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total <= MAX_IMAGE_BYTES) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, MAX_IMAGE_BYTES + 1 - total))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                    output.toByteArray()
                }
            if (bytes.size > MAX_IMAGE_BYTES) return null
            val mediaType = body.contentType()?.toString()?.substringBefore(';') ?: "application/octet-stream"
            if (!mediaType.startsWith("image/")) return null
            return Image(bytes, mediaType)
        }
    }

    private fun publicHttpUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()?.trimEnd('.')
            if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.rawUserInfo != null) return false
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
            val addresses = java.net.InetAddress.getAllByName(host)
            addresses.isNotEmpty() &&
                addresses.all { address ->
                    val bytes = address.address
                    val b0 = bytes.getOrNull(0)?.toInt()?.and(0xff) ?: 0
                    val b1 = bytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
                    val blockedV4 =
                        bytes.size == 4 && (
                            b0 == 0 || b0 == 10 || b0 == 127 ||
                                (b0 == 169 && b1 == 254) ||
                                (b0 == 172 && (b1 and 0xf0) == 16) ||
                                (b0 == 192 && b1 == 168) ||
                                (b0 == 100 && (b1 and 0xc0) == 64) ||
                                (b0 == 198 && b1 in 18..19)
                        )
                    val blockedV6 =
                        bytes.size == 16 && (
                            (b0 and 0xfe) == 0xfc ||
                                (b0 == 0xfe && (b1 and 0xc0) == 0x80)
                        )
                    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                        !address.isSiteLocalAddress && !address.isMulticastAddress && !blockedV4 && !blockedV6
                }
        }.getOrDefault(false)

    private fun mac(bytes: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
            doFinal(bytes)
        }

    private fun enc(value: String): String = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String? = runCatching { String(decoder.decode(value), Charsets.UTF_8) }.getOrNull()
}
