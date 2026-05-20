/*
 * Copyright (c) 2021 Auxio Project
 * GenreListFragment.kt is part of Auxio.
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
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.BrowserLayout
import org.oxycblt.auxio.home.HomeSettings
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.list.recycler.GenreGridViewHolder
import org.oxycblt.auxio.list.recycler.GenreViewHolder
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMsPopup
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] that shows a list of [Genre]s.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class GenreListFragment :
    ListFragment<Genre, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var homeSettings: HomeSettings
    private var genreAdapter: GenreAdapter? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        val layout = homeSettings.genreBrowserLayout
        val adapter = GenreAdapter(this, layout != BrowserLayout.LIST)
        genreAdapter = adapter

        binding.homeRecycler.apply {
            id = R.id.home_genre_recycler
            this.adapter = adapter
            popupProvider = this@GenreListFragment
            listener = this@GenreListFragment
            if (layout != BrowserLayout.LIST) {
                (layoutManager as? GridLayoutManager)?.spanCount = layout.spanCount
            }
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_genre_48)
            contentDescription = getString(R.string.lbl_genres)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_genres)

        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }

        collectImmediately(homeModel.genreList, ::updateGenres)
        collectImmediately(homeModel.empty, musicModel.indexingState, ::updateNoMusicIndicator)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
        genreAdapter = null
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val genre = homeModel.genreList.value.getOrNull(pos) ?: return null
        // Change how we display the popup depending on the current sort mode.
        return when (homeModel.genreSort.mode) {
            // By Name -> Use Name
            is Sort.Mode.ByName ->
                FastScrollRecyclerView.PopupProvider.PopupData(genre.name.thumb() ?: "?")

            // Duration -> Use compact bucket duration
            is Sort.Mode.ByDuration ->
                FastScrollRecyclerView.PopupProvider.PopupData(
                    genre.durationMs.formatDurationMsPopup()
                )

            // Count -> Use song count
            is Sort.Mode.ByCount ->
                FastScrollRecyclerView.PopupProvider.PopupData(genre.songs.size.toString())

            // Unsupported sort, error gracefully
            else -> null
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Genre) {
        detailModel.showGenre(item)
    }

    override fun onOpenMenu(item: Genre) {
        listModel.openMenu(R.menu.parent, item)
    }

    private fun updateGenres(genres: List<Genre>) {
        genreAdapter?.update(genres, homeModel.genreInstructions.consume())
    }

    private fun updateNoMusicIndicator(empty: Boolean, indexingState: IndexingState?) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty
        binding.homeNoMusicAction.isVisible =
            indexingState == null || (empty && indexingState is IndexingState.Completed)
    }

    private fun updateSelection(selection: List<Music>) {
        genreAdapter?.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        val genre = (parent as? Genre)?.takeIf { song?.run { genres.contains(it) } ?: false }
        genreAdapter?.setPlaying(genre, isPlaying)
    }

    private class GenreAdapter(
        private val listener: SelectableListListener<Genre>,
        private val isGrid: Boolean,
    ) :
        SelectionIndicatorAdapter<Genre, SelectionIndicatorAdapter.ViewHolder>(
            GenreViewHolder.DIFF_CALLBACK,
        ) {

        override fun getItemViewType(position: Int) =
            if (isGrid) GenreGridViewHolder.VIEW_TYPE else GenreViewHolder.VIEW_TYPE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            if (viewType == GenreGridViewHolder.VIEW_TYPE) {
                GenreGridViewHolder.from(parent)
            } else {
                GenreViewHolder.from(parent)
            }

        override fun onBindViewHolder(
            holder: SelectionIndicatorAdapter.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is GenreGridViewHolder -> holder.bind(getItem(position), listener)
                is GenreViewHolder -> holder.bind(getItem(position), listener)
            }
        }
    }
}
