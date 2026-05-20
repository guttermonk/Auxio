/*
 * Copyright (c) 2021 Auxio Project
 * AlbumListFragment.kt is part of Auxio.
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

package org.oxycblt.auxio.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.BrowserLayout
import org.oxycblt.auxio.home.HomeSettings
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.image.covers.CustomCoverStore
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.AlbumGridViewHolder
import org.oxycblt.auxio.list.recycler.AlbumViewHolder
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Album]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AlbumListFragment :
    ListFragment<Album, FragmentHomeListBinding>(),
    FastScrollRecyclerView.Listener,
    FastScrollRecyclerView.PopupProvider {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var homeSettings: HomeSettings
    @Inject lateinit var customCoverStore: CustomCoverStore
    private var albumAdapter: AlbumAdapter? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        val layout = homeSettings.albumBrowserLayout
        val adapter = AlbumAdapter(this, layout != BrowserLayout.LIST)
        albumAdapter = adapter

        binding.homeRecycler.apply {
            id = R.id.home_album_recycler
            this.adapter = adapter
            popupProvider = this@AlbumListFragment
            listener = this@AlbumListFragment
            if (layout != BrowserLayout.LIST) {
                (layoutManager as? GridLayoutManager)?.spanCount = layout.spanCount
            }
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_album_48)
            contentDescription = getString(R.string.lbl_albums)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_albums)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.albumList, ::updateAlbums)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
        collect(customCoverStore.updates) { changedUid: Music.UID ->
            val pos = homeModel.albumList.value.indexOfFirst { it.uid == changedUid }
            if (pos != -1) albumAdapter?.notifyItemChanged(pos)
        }
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
        albumAdapter = null
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val album = homeModel.albumList.value.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.albumSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(album.name.thumb() ?: "?")

            // By Artist -> Use name of first artist
            is Sort.Mode.ByArtist ->
                FastScrollRecyclerView.PopupProvider.PopupData(album.artists[0].name.thumb() ?: "?")

            // Date -> Use year of the range minimum
            is Sort.Mode.ByDate -> {
                val year = album.dates?.min?.year ?: return null
                FastScrollRecyclerView.PopupProvider.PopupData(getString(R.string.fmt_number, year))
            }

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    album.durationMs.formatDurationMsPopup()
                )

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                FastScrollRecyclerView.PopupProvider.PopupData(album.songs.size.toString())

            // Last added -> Use year
            is Sort.Mode.ByDateAdded -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = album.addedMs
                FastScrollRecyclerView.PopupProvider.PopupData(
                    getString(R.string.fmt_number, calendar.get(Calendar.YEAR))
                )
            }

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Album) {
        detailModel.showAlbum(item)
    }

    override fun onOpenMenu(item: Album) {
        listModel.openMenu(R.menu.album, item)
    }

    private fun updateAlbums(albums: List<Album>) {
        albumAdapter?.update(albums, homeModel.albumInstructions.consume())
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        albumAdapter?.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        val album = (parent as? Album)?.takeIf { song?.album == it }
        albumAdapter?.setPlaying(album, isPlaying)
    }

    private class AlbumAdapter(
        private val listener: SelectableListListener<Album>,
        private val isGrid: Boolean,
    ) : SelectionIndicatorAdapter<Album, SelectionIndicatorAdapter.ViewHolder>(
            AlbumViewHolder.DIFF_CALLBACK,
        ) {

        override fun getItemViewType(position: Int) =
            if (isGrid) AlbumGridViewHolder.VIEW_TYPE else AlbumViewHolder.VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            if (viewType == AlbumGridViewHolder.VIEW_TYPE) {
                AlbumGridViewHolder.from(parent)
            } else {
                AlbumViewHolder.from(parent)
            }

        override fun onBindViewHolder(
            holder: SelectionIndicatorAdapter.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is AlbumGridViewHolder -> holder.bind(getItem(position), listener)
                is AlbumViewHolder -> holder.bind(getItem(position), listener)
            }
        }
    }
}
