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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
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
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                TagFields(
                    title =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
                    artist =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                    album =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
                    albumArtist =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            ?: "",
                    track =
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
                        ) ?: "",
                    disc =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                            ?: "",
                    year =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: "",
                    genre =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "",
                    comment = "",
                )
            } catch (e: Exception) {
                L.e(e, "Failed to read tags from $uri")
                null
            } finally {
                retriever.release()
            }
        }

    suspend fun writeTags(uri: Uri, fileName: String?, fields: TagFields): String? =
        withContext(Dispatchers.IO) {
            val tempFile = copyToTemp(uri, fileName) ?: return@withContext "Could not copy file"
            try {
                L.d("Writing tags to temp file: ${tempFile.name} (${tempFile.length()} bytes)")
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
                L.d("Tags written, copying back to $uri")
                copyBack(tempFile, uri)
            } catch (e: Exception) {
                L.e(e, "Failed to write tags to $uri")
                val cause =
                    generateSequence(e as Throwable) { it.cause }
                        .joinToString(" -> ") { "${it::class.simpleName}: ${it.message}" }
                cause
            } finally {
                tempFile.delete()
            }
        }

    suspend fun writePartialTags(
        uri: Uri,
        fileName: String?,
        fields: Map<FieldKey, String>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val tempFile = copyToTemp(uri, fileName) ?: return@withContext false
            try {
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault
                for ((key, value) in fields) {
                    tag.setField(key, value)
                }
                audioFile.commit()
                copyBack(tempFile, uri) == null
            } catch (e: Exception) {
                L.e(e, "Failed to write partial tags to $uri")
                false
            } finally {
                tempFile.delete()
            }
        }

    suspend fun writeCoverArt(songUri: Uri, fileName: String?, coverFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val tempFile = copyToTemp(songUri, fileName) ?: return@withContext false
            try {
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault
                val artwork = StandardArtwork()
                artwork.binaryData = coverFile.readBytes()
                artwork.mimeType =
                    when {
                        coverFile.name.endsWith(".png", true) -> "image/png"
                        else -> "image/jpeg"
                    }
                artwork.pictureType = 3 // Cover (front)
                tag.deleteArtworkField()
                tag.setField(artwork)
                audioFile.commit()
                copyBack(tempFile, songUri) == null
            } catch (e: Exception) {
                L.e(e, "Failed to write cover art to $songUri")
                false
            } finally {
                tempFile.delete()
            }
        }

    private fun copyToTemp(uri: Uri, fileName: String?): File? {
        return try {
            val ext = fileName?.substringAfterLast('.', "") ?: ""
            val suffix = if (ext.isNotEmpty()) ".$ext" else ""
            val tempFile = File(context.cacheDir, "auxio_tag_edit_temp$suffix")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
                ?: run {
                    L.e("Could not open input stream for tag editing: $uri")
                    return null
                }
            if (tempFile.length() == 0L) {
                L.e("Temp file is empty after copy for tag editing: $uri")
                tempFile.delete()
                return null
            }
            L.d("Copied ${tempFile.length()} bytes to temp file: ${tempFile.name}")
            tempFile
        } catch (e: Exception) {
            L.e(e, "Failed to copy file to temp for tag editing")
            null
        }
    }

    private fun copyBack(tempFile: File, uri: Uri): String? {
        L.d("copyBack: uri=$uri, tempSize=${tempFile.length()}")
        // Use openFileDescriptor with "wt" mode for reliable SAF write access.
        // openOutputStream can fail on tree-based document URIs on some devices.
        try {
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "wt")
            if (pfd != null) {
                pfd.use { fd ->
                    FileOutputStream(fd.fileDescriptor).use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                L.d("Wrote ${tempFile.length()} bytes back via file descriptor")
                return null
            }
            L.w("openFileDescriptor returned null for $uri")
        } catch (e: Exception) {
            L.w(e, "openFileDescriptor failed for $uri")
        }
        // Fallback to openOutputStream
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
            if (output != null) {
                output.use { tempFile.inputStream().use { input -> input.copyTo(it) } }
                L.d("Wrote ${tempFile.length()} bytes back via output stream")
                return null
            }
        } catch (e: Exception) {
            L.w(e, "openOutputStream also failed for $uri")
        }
        return "No write access to $uri"
    }

    companion object {
        init {
            // Disable JAudioTagger's java.util.logging to prevent
            // NoSuchMethodException on Android's incomplete logging framework.
            try {
                Logger.getLogger("org.jaudiotagger").level = Level.OFF
            } catch (_: Exception) {}
        }
    }
}
