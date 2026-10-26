/*
 * Copyright (C) 2026 TaisenDev
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import graphql.execution.DataFetcherResult
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture

class MangaUrlMutation {
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
    ): CompletableFuture<DataFetcherResult<AddMangaFromUrlPayload?>> =
        future {
            DataFetcherResult
                .newResult<AddMangaFromUrlPayload?>()
                .data(
                    AddMangaFromUrlPayload(
                        clientMutationId = input.clientMutationId,
                        status = AddMangaFromUrlStatus.NO_SOURCE_FOR_URL,
                        manga = null,
                        installedExtensionPkgName = null,
                        message = "Persistent manga import is disabled. Use bridgeResolveUrl for a transient lookup.",
                    ),
                ).build()
        }
}
