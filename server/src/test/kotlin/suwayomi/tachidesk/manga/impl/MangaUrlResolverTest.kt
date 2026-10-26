/*
 * Copyright (C) 2026 TaisenDev
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package suwayomi.tachidesk.manga.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaUrlResolverTest {
    @Test
    fun `only absolute http URLs without embedded credentials are accepted`() {
        assertNotNull(MangaUrlResolver.parseHttpUri("https://example.org/manga/1"))
        assertNull(MangaUrlResolver.parseHttpUri("ftp://example.org/manga/1"))
        assertNull(MangaUrlResolver.parseHttpUri("https://user:pass@example.org/manga/1"))
        assertNull(MangaUrlResolver.parseHttpUri("/manga/1"))
    }

    @Test
    fun `base path must end at a URL path boundary`() {
        assertTrue(MangaUrlResolver.matchesBasePath("/reader/manga/1", "/reader"))
        assertTrue(MangaUrlResolver.matchesBasePath("/reader", "/reader/"))
        assertFalse(MangaUrlResolver.matchesBasePath("/readers/manga/1", "/reader"))
    }

    @Test
    fun `relative URL preserves query and removes source base path`() {
        val base = assertNotNull(MangaUrlResolver.parseHttpUri("https://example.org/reader"))
        val url = assertNotNull(MangaUrlResolver.parseHttpUri("https://example.org/reader/manga/1?lang=es"))

        assertEquals("/manga/1?lang=es", MangaUrlResolver.relativePath(url, base))
    }
}
