/*
 * Copyright (C) 2026 TaisenDev
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.MangaUrlResolver
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class MangaUrlMutation {
    private val logger = KotlinLogging.logger {}

    enum class AddMangaFromUrlStatus {
        FOUND,
        EXTENSION_INSTALLED,
        NO_SOURCE_FOR_URL,
        INVALID_URL,
        FETCH_FAILED,
    }

    data class AddMangaFromUrlInput(
        val clientMutationId: String? = null,
        val url: String,
        val autoInstallExtension: Boolean? = null,
        val addToLibrary: Boolean? = null,
        val categoryIds: List<Int>? = null,
    )

    data class AddMangaFromUrlPayload(
        val clientMutationId: String?,
        val status: AddMangaFromUrlStatus,
        val manga: MangaType?,
        val installedExtensionPkgName: String?,
        val message: String?,
    )

    @RequireAuth
    fun addMangaFromUrl(
        @GraphQLIgnore
        userId: Int,
        input: AddMangaFromUrlInput,
    ): CompletableFuture<DataFetcherResult<AddMangaFromUrlPayload?>> {
        val effectiveAutoInstall = input.autoInstallExtension ?: false
        val effectiveAddToLibrary = input.addToLibrary ?: true
        val effectiveCategoryIds = input.categoryIds.orEmpty()

        return future {
            val result =
                runCatching {
                    MangaUrlResolver.resolveUrl(
                        userId = userId,
                        url = input.url,
                        autoInstallExtension = effectiveAutoInstall,
                        addToLibrary = effectiveAddToLibrary,
                    )
                }.onFailure { error ->
                    logger.warn(error) { "Failed to add manga from URL" }
                }.getOrElse {
                    MangaUrlResolver.Result(
                        status = MangaUrlResolver.Status.FETCH_FAILED,
                        message = "The manga could not be loaded. Check the source and retry.",
                    )
                }

            if (result.manga != null && effectiveAddToLibrary && effectiveCategoryIds.isNotEmpty()) {
                CategoryManga.addMangasToCategories(userId, listOf(result.manga.id), effectiveCategoryIds)
            }

            val mangaType =
                result.manga?.id?.let { id ->
                    transaction {
                        MangaTable
                            .selectAll()
                            .where { MangaTable.id eq id }
                            .firstOrNull()
                            ?.let { MangaType(it) }
                    }
                }

            DataFetcherResult
                .newResult<AddMangaFromUrlPayload?>()
                .data(
                    AddMangaFromUrlPayload(
                        clientMutationId = input.clientMutationId,
                        status = AddMangaFromUrlStatus.valueOf(result.status.name),
                        manga = mangaType,
                        installedExtensionPkgName = result.installedExtensionPkgName,
                        message = result.message,
                    ),
                ).build()
        }
    }
}
