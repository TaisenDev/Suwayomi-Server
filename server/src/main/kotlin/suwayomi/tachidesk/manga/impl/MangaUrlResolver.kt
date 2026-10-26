/*
 * Copyright (C) 2026 TaisenDev
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.manga.impl

import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import java.net.URI

object MangaUrlResolver {
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

    internal fun parseHttpUri(rawUrl: String): URI? =
        runCatching {
            URI(rawUrl.trim()).normalize().takeIf { uri ->
                uri.scheme?.lowercase() in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null
            }
        }.getOrNull()

    internal fun matchesBasePath(
        urlPath: String,
        basePath: String,
    ): Boolean {
        val base = basePath.trimEnd('/')
        return base.isEmpty() || urlPath == base || urlPath.startsWith("$base/")
    }

    /**
     * Legacy import entry point retained for API compatibility.
     * Persistent manga imports are disabled; transient resolution belongs to BridgeMutation.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun resolveUrl(
        userId: Int,
        url: String,
        autoInstallExtension: Boolean = false,
        addToLibrary: Boolean = true,
    ): Result =
        Result(
            status = if (parseHttpUri(url) == null) Status.INVALID_URL else Status.NO_SOURCE_FOR_URL,
            message = "Persistent manga imports are disabled. Use the transient bridge resolver.",
        )

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
