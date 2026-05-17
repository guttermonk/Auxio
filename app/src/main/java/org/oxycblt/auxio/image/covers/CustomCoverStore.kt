/*
 * Copyright (c) 2024 Auxio Project
 * CustomCoverStore.kt is part of Auxio.
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

package org.oxycblt.auxio.image.covers

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Music
import timber.log.Timber as L

/**
 * Stores user-chosen custom album covers in internal storage, keyed by [Music.UID].
 *
 * Custom covers persist across music library rescans since they are indexed by the stable album UID
 * rather than any mutable metadata. Observers can react to cover changes via [updates].
 */
@Singleton
class CustomCoverStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File =
        context.filesDir.resolve("custom_covers").apply { mkdirs() }

    private val _updates = MutableSharedFlow<Music.UID>(extraBufferCapacity = 8)

    /**
     * Emits the [Music.UID] of an album whenever its custom cover is saved or cleared. Any UI
     * displaying that album's cover should rebind when this fires.
     */
    val updates: SharedFlow<Music.UID> = _updates.asSharedFlow()

    /**
     * Return the [File] that would hold the custom cover for [uid]. Existence of this file
     * indicates a custom cover is set; absence means the default cover is used.
     */
    fun fileFor(uid: Music.UID): File = dir.resolve(uid.toString().replace('/', '-'))

    /** Returns true if a custom cover has been saved for [uid]. */
    fun has(uid: Music.UID): Boolean = fileFor(uid).exists()

    /**
     * Copy the image at [uri] into internal storage as the custom cover for [uid].
     *
     * @return true on success, false if the source could not be read or written.
     */
    suspend fun save(uid: Music.UID, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dest = fileFor(uid)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                    ?: run {
                        L.e("Could not open input stream for cover URI: $uri")
                        return@withContext false
                    }
                L.d("Saved custom cover for $uid")
                true
            } catch (e: Exception) {
                L.e(e, "Failed to save custom cover for $uid")
                fileFor(uid).delete()
                false
            }
        }.also { success ->
            if (success) _updates.tryEmit(uid)
        }

    /**
     * Copy the raw bytes from a library [Cover] stream into internal storage as the custom cover
     * for [uid]. This is used when the user picks one of the covers already found in the library,
     * since those [Cover] objects may not be backed by a stable URI.
     *
     * @return true on success, false if the cover could not be opened or written.
     */
    suspend fun saveFromCover(uid: Music.UID, cover: org.oxycblt.musikr.covers.Cover): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dest = fileFor(uid)
                cover.open()?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                    ?: run {
                        L.e("Could not open stream for cover ${cover.id}")
                        return@withContext false
                    }
                L.d("Saved library cover for $uid")
                true
            } catch (e: Exception) {
                L.e(e, "Failed to save library cover for $uid")
                fileFor(uid).delete()
                false
            }
        }.also { success ->
            if (success) _updates.tryEmit(uid)
        }

    /**
     * Remove any custom cover for [uid], reverting to the library-derived artwork.
     */
    fun clear(uid: Music.UID) {
        fileFor(uid).delete()
        _updates.tryEmit(uid)
        L.d("Cleared custom cover for $uid")
    }
}
