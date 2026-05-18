/*
 * Copyright (c) 2024 Auxio Project
 * CoverPickerViewModel.kt is part of Auxio.
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import timber.log.Timber as L

/**
 * Drives the [CoverPickerDialogFragment]. Resolves the album from the music library, builds the
 * list of picker items, and delegates save/reset operations to [CustomCoverStore].
 */
@HiltViewModel
class CoverPickerViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val customCoverStore: CustomCoverStore,
) : ViewModel(), MusicRepository.UpdateListener {

    private val _currentAlbum = MutableStateFlow<Album?>(null)
    /** The album whose cover is being edited. Null until [setAlbum] is called. */
    val currentAlbum: StateFlow<Album?> = _currentAlbum

    private val _pickerItems = MutableStateFlow<List<CoverPickerItem>>(emptyList())
    /** The complete ordered list of items for the picker RecyclerView. */
    val pickerItems: StateFlow<List<CoverPickerItem>> = _pickerItems

    private val _hasCustomCover = MutableStateFlow(false)
    /** Whether the album currently has a user-chosen custom cover applied. */
    val hasCustomCover: StateFlow<Boolean> = _hasCustomCover

    private val _saveResult = MutableEvent<Boolean>()
    /**
     * Fires with `true` on successful save/reset, `false` on failure. The fragment should show the
     * appropriate toast and then dismiss.
     */
    val saveResult: Event<Boolean> = _saveResult

    /** Online thumbnails discovered by the most recent search, shown in the picker. */
    private var onlineResults: List<CoverPickerItem.OnlineCoverOption> = emptyList()
    private var isSearchingOnline = false

    init {
        musicRepository.addUpdateListener(this)
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        // Re-resolve the album after a library update so the cover list stays fresh.
        _currentAlbum.value?.uid?.let { setAlbum(it) }
    }

    /**
     * Load the album identified by [uid] from the current library. Safe to call multiple times;
     * only the most recent uid matters. Also kicks off an online cover search in the background.
     */
    fun setAlbum(uid: Music.UID) {
        val album = musicRepository.library?.findAlbum(uid)
        if (album == null) {
            L.w("Album $uid not found in library")
            _currentAlbum.value = null
            _pickerItems.value = emptyList()
            return
        }
        _currentAlbum.value = album
        _hasCustomCover.value = customCoverStore.has(uid)
        onlineResults = emptyList()
        isSearchingOnline = true
        _pickerItems.value = buildItems(album)
        searchOnlineCovers(album)
    }

    /**
     * Persist the image at [uri] as the custom cover for the current album. Emits the result via
     * [saveResult].
     */
    fun saveCover(uri: Uri, permanent: Boolean = false) {
        val album = _currentAlbum.value ?: return
        viewModelScope.launch {
            val success = customCoverStore.save(album.uid, uri)
            if (success) {
                if (permanent) customCoverStore.markPermanent(album.uid)
                _hasCustomCover.value = true
                _pickerItems.value = buildItems(album)
            }
            _saveResult.put(success)
        }
    }

    fun saveCoverFromLibrary(item: CoverPickerItem.CoverOption, permanent: Boolean = false) {
        val album = _currentAlbum.value ?: return
        viewModelScope.launch {
            val success = customCoverStore.saveFromCover(album.uid, item.cover)
            if (success) {
                if (permanent) customCoverStore.markPermanent(album.uid)
                _hasCustomCover.value = true
                _pickerItems.value = buildItems(album)
            }
            _saveResult.put(success)
        }
    }

    /** Remove the custom cover for the current album, reverting to library artwork. */
    fun resetCover() {
        val album = _currentAlbum.value ?: return
        customCoverStore.clear(album.uid)
        _hasCustomCover.value = false
        _pickerItems.value = buildItems(album)
        _saveResult.put(true)
    }

    fun saveOnlineCover(item: CoverPickerItem.OnlineCoverOption, permanent: Boolean = false) {
        val album = _currentAlbum.value ?: return
        viewModelScope.launch {
            val success = customCoverStore.saveFromUrl(album.uid, item.fullUrl)
            if (success) {
                if (permanent) customCoverStore.markPermanent(album.uid)
                _hasCustomCover.value = true
                _pickerItems.value = buildItems(album)
            }
            _saveResult.put(success)
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun searchOnlineCovers(album: Album) {
        val albumName = album.name.resolve(context)
        val artistName = album.artists.resolveNames(context)
        viewModelScope.launch(Dispatchers.IO) {
            val rawResults =
                listOf(
                        async { OnlineCoverSearch.fetchItunes(albumName, artistName) },
                        async { OnlineCoverSearch.fetchDeezer(albumName, artistName) },
                        async { OnlineCoverSearch.fetchCoverArtArchive(albumName, artistName) },
                    )
                    .awaitAll()

            val items =
                rawResults.filterNotNull().mapIndexedNotNull { idx, result ->
                    val file =
                        downloadThumbnail(result.thumbnailUrl, idx) ?: return@mapIndexedNotNull null
                    CoverPickerItem.OnlineCoverOption(file, result.fullUrl, result.source, idx)
                }

            withContext(Dispatchers.Main) {
                isSearchingOnline = false
                onlineResults = items
                _currentAlbum.value?.let { _pickerItems.value = buildItems(it) }
            }
        }
    }

    private fun downloadThumbnail(url: String, index: Int): File? =
        try {
            val file = File(context.cacheDir, "auxio_cover_thumb_$index")
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
            file
        } catch (e: Exception) {
            L.w(e, "Failed to download thumbnail from $url")
            null
        }

    private fun buildItems(album: Album): List<CoverPickerItem> = buildList {
        when {
            isSearchingOnline -> add(CoverPickerItem.SectionLabel(R.string.lbl_searching_online))
            onlineResults.isNotEmpty() -> {
                add(CoverPickerItem.SectionLabel(R.string.lbl_online_artwork))
                addAll(onlineResults)
            }
        }

        add(
            CoverPickerItem.ActionItem(
                R.drawable.ic_file_24,
                R.string.lbl_browse_device,
                CoverPickerItem.ACTION_BROWSE,
            )
        )
        add(
            CoverPickerItem.ActionItem(
                R.drawable.ic_search_24,
                R.string.lbl_search_online,
                CoverPickerItem.ACTION_SEARCH,
            )
        )
        if (customCoverStore.has(album.uid) && !customCoverStore.isPermanent(album.uid)) {
            add(
                CoverPickerItem.ActionItem(
                    R.drawable.ic_close_24,
                    R.string.lbl_reset_cover,
                    CoverPickerItem.ACTION_RESET,
                )
            )
        }
    }
}
