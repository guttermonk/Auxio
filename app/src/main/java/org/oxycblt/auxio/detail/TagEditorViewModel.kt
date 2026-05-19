/*
 * Copyright (c) 2025 Auxio Project
 * TagEditorViewModel.kt is part of Auxio.
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
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@HiltViewModel
class TagEditorViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val tagEditorService: TagEditorService,
) : ViewModel() {

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _tagFields = MutableStateFlow<TagFields?>(null)
    val tagFields: StateFlow<TagFields?> = _tagFields

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _saveResult = MutableEvent<Boolean>()
    val saveResult: Event<Boolean> = _saveResult

    fun setSong(uid: Music.UID) {
        val song = musicRepository.library?.findSong(uid)
        if (song == null) {
            L.w("Song $uid not found in library")
            _currentSong.value = null
            return
        }
        _currentSong.value = song
        loadTags(song)
    }

    private fun loadTags(song: Song) {
        _isLoading.value = true
        viewModelScope.launch {
            val fields = tagEditorService.readTags(song.uri, song.path.name)
            _tagFields.value = fields
            _isLoading.value = false
        }
    }

    fun saveTags(fields: TagFields) {
        val song = _currentSong.value ?: return
        _isLoading.value = true
        viewModelScope.launch {
            val success = tagEditorService.writeTags(song.uri, song.path.name, fields)
            _isLoading.value = false
            if (success) {
                triggerMediaScan(song)
            }
            _saveResult.put(success)
        }
    }

    private fun triggerMediaScan(song: Song) {
        val volume = song.path.volume
        val volumeRoot = volume.components
        if (volumeRoot != null) {
            val fullPath = "$volumeRoot/${song.path.components}"
            L.d("Triggering media scan for $fullPath")
            MediaScannerConnection.scanFile(context, arrayOf(fullPath), null, null)
        }
    }
}
