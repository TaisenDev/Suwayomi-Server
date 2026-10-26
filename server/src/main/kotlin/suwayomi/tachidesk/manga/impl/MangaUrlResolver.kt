/*
 * Copyright (C) 2026 TaisenDev
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.MangaList.insertOrUpdate
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.net.URI

object MangaUrlResolver {
    private val logger = KotlinLogging.logger {}

    enum class Status {
        FOUND,
        EXTENSION_INSTALLED,
        NO_SOURCE_FOR_URL,
        INVALID_URL,
        FETCH_FAILED,
    }

    data class Result(
        val status: Status,
        val manga: MangaDataClass? = null,
        val installedExtensionPkgName: String? = null,
        val message: String? = null,
    )

    private data class SourceMatch(
        val id: Long,
        val baseUrl: URI,
    )

    internal fun parseHttpUri(rawUrl: String): URI? =
        runCatching {
            URI(rawUrl.trim()).normalize().takeIf { uri ->
                uri.scheme?.lowercase() in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null
            }
        }.getOrNull()

    private fun normalizedHost(uri: URI): String = uri.host.lowercase().removePrefix("www.")

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    internal fun matchesBasePath(
        urlPath: String,
        basePath: String,
    ): Boolean {
        val base = basePath.trimEnd('/')
        return base.isEmpty() || urlPath == base || urlPath.startsWith("$base/")
    }

    private suspend fun findInstalledSource(uri: URI): SourceMatch? =
        Source
            .getSourceList()
            .mapNotNull { source ->
                val baseUrl = source.baseUrl?.let(::parseHttpUri) ?: return@mapNotNull null
                if (normalizedHost(baseUrl) != normalizedHost(uri)) return@mapNotNull null
                if (effectivePort(baseUrl) != effectivePort(uri)) return@mapNotNull null
                if (!baseUrl.scheme.equals(uri.scheme, ignoreCase = true)) return@mapNotNull null
                if (!matchesBasePath(uri.rawPath.orEmpty(), baseUrl.rawPath.orEmpty())) return@mapNotNull null
                SourceMatch(source.id.toLong(), baseUrl)
            }.sortedWith(
                compareByDescending<SourceMatch> {
                    it.baseUrl.rawPath
                        .orEmpty()
                        .trimEnd('/')
                        .length
                }.thenBy { it.id },
            ).firstOrNull()

    private suspend fun findOnlineExtensionForHost(host: String): ExtensionInfo? {
        val stores =
            runCatching { ExtensionStoreService.getAndRefresh() }
                .onFailure { logger.warn(it) { "Failed to refresh extension stores" } }
                .getOrNull() ?: return null

        for (store in stores) {
            val extensions =
                runCatching { ExtensionStoreService.getExtensions(store) }
                    .onFailure { logger.warn(it) { "Failed to fetch extensions for store: ${store.indexUrl}" } }
                    .getOrNull() ?: continue
            val match =
                extensions.firstOrNull { extension ->
                    extension.sources.any { source ->
                        parseHttpUri(source.homeUrl)?.let(::normalizedHost) == host
                    }
                }
            if (match != null) return match
        }
        return null
    }

    /** Resolves a public manga URL using an installed source and optionally adds it to the library. */
    suspend fun resolveUrl(
        userId: Int,
        url: String,
        autoInstallExtension: Boolean = false,
        addToLibrary: Boolean = true,
    ): Result {
        val uri =
            parseHttpUri(url) ?: return Result(
                Status.INVALID_URL,
                message = "A valid HTTP or HTTPS URL is required.",
            )
        val host = normalizedHost(uri)

        var match = findInstalledSource(uri)
        var installedPkgName: String? = null

        if (match == null && autoInstallExtension) {
            val online = findOnlineExtensionForHost(host)
            if (online != null) {
                logger.info { "Installing extension '${online.pkgName}' to handle URL host '$host'" }
                runCatching { ExtensionsList.fetchExtensionsCached() }
                runCatching { Extension.installExtension(online.pkgName) }
                    .onFailure {
                        logger.warn(it) { "Failed to install extension '${online.pkgName}' for host '$host'" }
                        return Result(
                            status = Status.FETCH_FAILED,
                            message = "The matching extension could not be installed.",
                        )
                    }
                installedPkgName = online.pkgName
                match = findInstalledSource(uri)
            }
        }

        if (match == null) {
            return Result(
                Status.NO_SOURCE_FOR_URL,
                installedExtensionPkgName = installedPkgName,
                message = "No installed source matches this URL. You can install its extension and retry.",
            )
        }

        val relativeUrl = relativePath(uri, match.baseUrl)
        val sManga =
            SManga.create().apply {
                this.url = relativeUrl
                this.title = ""
            }

        val mangaId =
            transaction {
                MangasPage(listOf(sManga), false).insertOrUpdate(match.id).first()
            }

        val updateFailure =
            runCatching { Manga.updateMangaAndChapters(mangaId, updateManga = true, updateChapters = true) }
                .exceptionOrNull()

        if (updateFailure != null) {
            logger.warn(updateFailure) { "Failed to fetch manga details and chapters for id=$mangaId" }
            return Result(
                status = Status.FETCH_FAILED,
                installedExtensionPkgName = installedPkgName,
                message = "The source could not load this manga. Check Cloudflare or source availability, then retry.",
            )
        }

        if (addToLibrary) Library.addMangaToLibrary(userId, mangaId)

        val mangaData =
            transaction {
                MangaTable
                    .selectAll()
                    .where { MangaTable.id eq mangaId }
                    .first()
                    .let { MangaTable.toDataClass(it) }
            }

        return Result(
            status = if (installedPkgName != null) Status.EXTENSION_INSTALLED else Status.FOUND,
            manga = mangaData,
            installedExtensionPkgName = installedPkgName,
        )
    }

    internal fun relativePath(
        url: URI,
        baseUrl: URI,
    ): String {
        val basePath = baseUrl.rawPath.orEmpty().trimEnd('/')
        val urlPath = url.rawPath.orEmpty().ifEmpty { "/" }
        val path = if (basePath.isEmpty()) urlPath else urlPath.removePrefix(basePath).ifEmpty { "/" }
        return path + (url.rawQuery?.let { "?$it" } ?: "")
    }
}
