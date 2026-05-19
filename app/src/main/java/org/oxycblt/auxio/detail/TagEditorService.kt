/*
 * Copyright (c) 2025 Auxio Project
 * TagEditorService.kt is part of Auxio.
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

package org.oxycblt.auxio.detail

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import timber.log.Timber as L

data class TagFields(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: String,
    val disc: String,
    val year: String,
    val genre: String,
    val comment: String,
)

@Singleton
class TagEditorService @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun readTags(uri: Uri): TagFields? =
        withContext(Dispatchers.IO) {
            val tempFile = copyToTemp(uri) ?: return@withContext null
            try {
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault
                TagFields(
                    title = tag.getFirst(FieldKey.TITLE),
                    artist = tag.getFirst(FieldKey.ARTIST),
                    album = tag.getFirst(FieldKey.ALBUM),
                    albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST),
                    track = tag.getFirst(FieldKey.TRACK),
                    disc = tag.getFirst(FieldKey.DISC_NO),
                    year = tag.getFirst(FieldKey.YEAR),
                    genre = tag.getFirst(FieldKey.GENRE),
                    comment = tag.getFirst(FieldKey.COMMENT),
                )
            } catch (e: Exception) {
                L.e(e, "Failed to read tags from $uri")
                null
            } finally {
                tempFile.delete()
            }
        }

    suspend fun writeTags(uri: Uri, fields: TagFields): Boolean =
        withContext(Dispatchers.IO) {
            val tempFile = copyToTemp(uri) ?: return@withContext false
            try {
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.TITLE, fields.title)
                tag.setField(FieldKey.ARTIST, fields.artist)
                tag.setField(FieldKey.ALBUM, fields.album)
                tag.setField(FieldKey.ALBUM_ARTIST, fields.albumArtist)
                tag.setField(FieldKey.TRACK, fields.track)
                tag.setField(FieldKey.DISC_NO, fields.disc)
                tag.setField(FieldKey.YEAR, fields.year)
                tag.setField(FieldKey.GENRE, fields.genre)
                tag.setField(FieldKey.COMMENT, fields.comment)
                audioFile.commit()
                copyBack(tempFile, uri)
            } catch (e: Exception) {
                L.e(e, "Failed to write tags to $uri")
                false
            } finally {
                tempFile.delete()
            }
        }

    private fun copyToTemp(uri: Uri): File? =
        try {
            val tempFile = File(context.cacheDir, "auxio_tag_edit_temp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            tempFile
        } catch (e: Exception) {
            L.e(e, "Failed to copy file to temp for tag editing")
            null
        }

    private fun copyBack(tempFile: File, uri: Uri): Boolean =
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            L.e(e, "Failed to write modified file back to $uri")
            false
        }
}
