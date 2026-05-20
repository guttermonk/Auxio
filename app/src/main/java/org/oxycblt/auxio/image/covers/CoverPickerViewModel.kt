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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.detail.TagEditorService
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
    private val tagEditorService: TagEditorService,
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
    fun saveCover(uri: Uri) {
        val album = _currentAlbum.value ?: return
        viewModelScope.launch {
            val success = customCoverStore.save(album.uid, uri)
            if (success) {
                customCoverStore.markPermanent(album.uid)
                _hasCustomCover.value = true
                _pickerItems.value = buildItems(album)
                embedCoverInSongs(album)
            }
            _saveResult.put(success)
        }
    }

    fun saveCoverFromLibrary(item: CoverPickerItem.CoverOption) {
        val album = _currentAlbum.value ?: return
        viewModelScope.launch {
            val success = customCoverStore.saveFromCover(album.uid, item.cover)
            if (success) {
                customCoverStore.markPermanent(album.uid)
                _hasCustomCover.value = true
                _pickerItems.value = buildItems(album)
                embedCoverInSongs(album)
            }
            _saveResult.put(success)
        }
    }

    fun saveOnlineCover(item: CoverPickerItem.OnlineCoverOption) {
        val album = _currentAlbum.value ?: return
        viewModelScope.launch {
            val success = customCoverStore.saveFromUrl(album.uid, item.fullUrl)
            if (success) {
                customCoverStore.markPermanent(album.uid)
                _hasCustomCover.value = true
                _pickerItems.value = buildItems(album)
                embedCoverInSongs(album)
            }
            _saveResult.put(success)
        }
    }

    fun clearCover() {
        val album = _currentAlbum.value ?: return
        customCoverStore.markCleared(album.uid)
        _hasCustomCover.value = false
        _pickerItems.value = buildItems(album)
        _saveResult.put(true)
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private suspend fun embedCoverInSongs(album: Album) {
        val coverFile = customCoverStore.fileFor(album.uid)
        if (!coverFile.exists()) return
        for (song in album.songs) {
            val ok = tagEditorService.writeCoverArt(song.uri, song.path.name, coverFile)
            if (!ok) L.w("Failed to embed cover art in ${song.path.name}")
        }
        L.d("Embedded cover art in ${album.songs.size} songs")
    }

    private fun searchOnlineCovers(album: Album) {
        val albumName = album.name.resolve(context)
        val artistName = album.artists.resolveNames(context)
        viewModelScope.launch(Dispatchers.IO) {
            // Phase 1: search with album + artist
            var unique = fetchAllSources(albumName, artistName)

            // Phase 2: if fewer than 6, retry with album name only (if artist was provided)
            if (unique.size < 6 && artistName.isNotEmpty()) {
                val extra = fetchAllSources(albumName, "")
                val seen = unique.map { it.fullUrl }.toSet()
                unique = unique + extra.filter { it.fullUrl !in seen }
            }

            val capped = unique.take(6)
            val items =
                capped.mapIndexedNotNull { idx, result ->
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

    private suspend fun fetchAllSources(
        albumName: String,
        artistName: String,
    ): List<OnlineCoverSearch.Result> = coroutineScope {
        val all =
            listOf(
                    async { OnlineCoverSearch.fetchItunes(albumName, artistName) },
                    async { OnlineCoverSearch.fetchDeezer(albumName, artistName) },
                    async { OnlineCoverSearch.fetchCoverArtArchive(albumName, artistName) },
                    async { OnlineCoverSearch.fetchTheAudioDB(albumName, artistName) },
                    async { OnlineCoverSearch.fetchWikipedia(albumName, artistName) },
                )
                .awaitAll()
                .flatten()
        val seen = mutableSetOf<String>()
        all.filter { seen.add(it.fullUrl) }
    }

    private fun downloadThumbnail(url: String, index: Int): File? =
        try {
            val file = File(context.cacheDir, "auxio_cover_thumb_$index")
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w > 0 && h > 0) {
                val ratio = w.toFloat() / h.toFloat()
                if (ratio < 0.75f || ratio > 1.33f) {
                    L.d("Thumbnail not square enough (${w}x$h, ratio=$ratio): $url")
                    file.delete()
                    return null
                }
            }
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
        if (_hasCustomCover.value) {
            add(
                CoverPickerItem.ActionItem(
                    R.drawable.ic_delete_24,
                    R.string.lbl_clear_cover,
                    CoverPickerItem.ACTION_CLEAR,
                )
            )
        }
    }
}
