/*
 * Copyright (c) 2025 Auxio Project
 * BulkTagEditorViewModel.kt is part of Auxio.
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
import android.media.MediaScannerConnection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jaudiotagger.tag.FieldKey
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

data class BulkTagFields(
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val genre: String,
)

@HiltViewModel
class BulkTagEditorViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val tagEditorService: TagEditorService,
) : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _tagFields = MutableStateFlow<BulkTagFields?>(null)
    val tagFields: StateFlow<BulkTagFields?> = _tagFields

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _saveResult = MutableEvent<Boolean>()
    val saveResult: Event<Boolean> = _saveResult

    fun setSongs(uids: List<Music.UID>) {
        val library = musicRepository.library ?: return
        val resolved = uids.mapNotNull { library.findSong(it) }
        if (resolved.isEmpty()) {
            L.w("No songs found for bulk edit")
            return
        }
        _songs.value = resolved
        loadSharedTags(resolved)
    }

    private fun loadSharedTags(songs: List<Song>) {
        _isLoading.value = true
        viewModelScope.launch {
            val allFields = songs.mapNotNull { tagEditorService.readTags(it.uri) }
            if (allFields.isEmpty()) {
                _isLoading.value = false
                return@launch
            }
            _tagFields.value =
                BulkTagFields(
                    artist = sharedValue(allFields) { it.artist },
                    album = sharedValue(allFields) { it.album },
                    albumArtist = sharedValue(allFields) { it.albumArtist },
                    year = sharedValue(allFields) { it.year },
                    genre = sharedValue(allFields) { it.genre },
                )
            _isLoading.value = false
        }
    }

    fun saveTags(fields: BulkTagFields) {
        val songs = _songs.value
        if (songs.isEmpty()) return
        val original = _tagFields.value
        val changed = mutableMapOf<FieldKey, String>()
        if (fields.artist != (original?.artist ?: "")) changed[FieldKey.ARTIST] = fields.artist
        if (fields.album != (original?.album ?: "")) changed[FieldKey.ALBUM] = fields.album
        if (fields.albumArtist != (original?.albumArtist ?: ""))
            changed[FieldKey.ALBUM_ARTIST] = fields.albumArtist
        if (fields.year != (original?.year ?: "")) changed[FieldKey.YEAR] = fields.year
        if (fields.genre != (original?.genre ?: "")) changed[FieldKey.GENRE] = fields.genre
        if (changed.isEmpty()) {
            _saveResult.put(true)
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            var allSuccess = true
            for (song in songs) {
                val success = tagEditorService.writePartialTags(song.uri, song.path.name, changed)
                if (!success) {
                    L.e("Failed to write tags for ${song.path.name}")
                    allSuccess = false
                }
            }
            if (allSuccess) {
                triggerMediaScan(songs)
            }
            _isLoading.value = false
            _saveResult.put(allSuccess)
        }
    }

    private fun triggerMediaScan(songs: List<Song>) {
        val paths =
            songs.mapNotNull { song ->
                val volumeRoot = song.path.volume.components ?: return@mapNotNull null
                "$volumeRoot/${song.path.components}"
            }
        if (paths.isNotEmpty()) {
            L.d("Triggering media scan for ${paths.size} files")
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }

    private fun sharedValue(fields: List<TagFields>, extract: (TagFields) -> String): String {
        val values = fields.map(extract).distinct()
        return if (values.size == 1) values[0] else ""
    }
}
