/*
 * Copyright (c) 2025 Auxio Project
 * StaleAsHitCache.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.shim

import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.CachedFile
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.fs.File

/**
 * A [MutableCache] shim that promotes stale cache entries to hits.
 *
 * Used during the fast phase-1 pass so that previously-cached metadata is served immediately
 * without waiting for TagLib re-extraction of modified files. Phase 2 then runs with the real cache
 * to re-extract only the files that were stale, keeping the library accurate.
 *
 * Files with no cache entry at all ([CacheResult.Miss]) still go through extraction so that
 * brand-new tracks are included in the phase-1 library.
 */
class StaleAsHitCache(private val inner: MutableCache) : MutableCache {
    override suspend fun read(file: File): CacheResult =
        when (val result = inner.read(file)) {
            is CacheResult.Stale -> CacheResult.Hit(result.cachedFile)
            else -> result
        }

    override suspend fun write(cachedFile: CachedFile) = inner.write(cachedFile)

    override suspend fun cleanup(excluding: List<CachedFile>) = inner.cleanup(excluding)
}
