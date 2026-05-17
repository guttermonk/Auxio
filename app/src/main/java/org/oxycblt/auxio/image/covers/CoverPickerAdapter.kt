/*
 * Copyright (c) 2024 Auxio Project
 * CoverPickerAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import org.oxycblt.auxio.databinding.ItemCoverThumbnailBinding
import org.oxycblt.auxio.databinding.ItemMenuOptionBinding
import org.oxycblt.auxio.image.coil.RoundedRectTransformation
import org.oxycblt.auxio.image.coil.SquareCropTransformation
import org.oxycblt.auxio.util.getDrawableCompat
import org.oxycblt.musikr.covers.Cover

/** Items shown in the cover picker bottom sheet. */
sealed interface CoverPickerItem {
    /** A text section header ("Artwork from your library", etc.). */
    data class SectionLabel(@StringRes val titleRes: Int) : CoverPickerItem

    /**
     * A cover thumbnail pulled from the album's existing library artwork.
     *
     * @param cover The [Cover] to render.
     * @param index Zero-based index among all library covers (used as stable ID).
     * @param isSelected Whether this cover is the currently active one.
     */
    data class CoverOption(val cover: Cover, val index: Int, val isSelected: Boolean) :
        CoverPickerItem

    /** A full-width action row (browse gallery, search online, reset). */
    data class ActionItem(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        val id: Int,
    ) : CoverPickerItem

    companion object {
        const val ACTION_BROWSE = 1
        const val ACTION_SEARCH = 2
        const val ACTION_RESET = 3
    }
}

/** Callbacks from the cover picker RecyclerView to the fragment. */
interface CoverPickerListener {
    fun onCoverSelected(item: CoverPickerItem.CoverOption)

    fun onActionSelected(item: CoverPickerItem.ActionItem)
}

private const val VIEW_TYPE_SECTION = 0
private const val VIEW_TYPE_THUMBNAIL = 1
private const val VIEW_TYPE_ACTION = 2

/**
 * Adapter for the cover picker's [RecyclerView]. Uses a [GridLayoutManager] externally configured
 * with [SPAN_COUNT] columns; section labels and action rows span the full width via
 * [spanSizeLookup].
 */
class CoverPickerAdapter(
    private val imageLoader: ImageLoader,
    private val listener: CoverPickerListener,
) : ListAdapter<CoverPickerItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    /** Attach a [GridLayoutManager.SpanSizeLookup] so headers/actions span all columns. */
    val spanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                when (getItemViewType(position)) {
                    VIEW_TYPE_THUMBNAIL -> 1
                    else -> SPAN_COUNT
                }
        }

    override fun getItemViewType(position: Int) =
        when (getItem(position)) {
            is CoverPickerItem.SectionLabel -> VIEW_TYPE_SECTION
            is CoverPickerItem.CoverOption -> VIEW_TYPE_THUMBNAIL
            is CoverPickerItem.ActionItem -> VIEW_TYPE_ACTION
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION ->
                SectionViewHolder(
                    inflater.inflate(org.oxycblt.auxio.R.layout.item_header, parent, false)
                )
            VIEW_TYPE_THUMBNAIL ->
                ThumbnailViewHolder(ItemCoverThumbnailBinding.inflate(inflater, parent, false))
            VIEW_TYPE_ACTION ->
                ActionViewHolder(ItemMenuOptionBinding.inflate(inflater, parent, false))
            else -> error("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CoverPickerItem.SectionLabel -> (holder as SectionViewHolder).bind(item)
            is CoverPickerItem.CoverOption ->
                (holder as ThumbnailViewHolder).bind(item, imageLoader, listener)
            is CoverPickerItem.ActionItem -> (holder as ActionViewHolder).bind(item, listener)
        }
    }

    companion object {
        const val SPAN_COUNT = 3

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<CoverPickerItem>() {
                override fun areItemsTheSame(a: CoverPickerItem, b: CoverPickerItem): Boolean =
                    when {
                        a is CoverPickerItem.SectionLabel && b is CoverPickerItem.SectionLabel ->
                            a.titleRes == b.titleRes
                        a is CoverPickerItem.CoverOption && b is CoverPickerItem.CoverOption ->
                            a.index == b.index
                        a is CoverPickerItem.ActionItem && b is CoverPickerItem.ActionItem ->
                            a.id == b.id
                        else -> false
                    }

                override fun areContentsTheSame(a: CoverPickerItem, b: CoverPickerItem) = a == b
            }
    }
}

// ---------------------------------------------------------------------------
// ViewHolders
// ---------------------------------------------------------------------------

private class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: android.widget.TextView = view.findViewById(android.R.id.title)

    fun bind(item: CoverPickerItem.SectionLabel) {
        title.setText(item.titleRes)
    }
}

private class ThumbnailViewHolder(private val binding: ItemCoverThumbnailBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
        item: CoverPickerItem.CoverOption,
        imageLoader: ImageLoader,
        listener: CoverPickerListener,
    ) {
        binding.root.setOnClickListener { listener.onCoverSelected(item) }
        binding.coverThumbnailCheck.isVisible = item.isSelected

        val context = binding.root.context
        // Force square crop + rounded corners to match the rest of the app's cover style
        val cornerPx = context.resources.getDimension(org.oxycblt.auxio.R.dimen.spacing_small)
        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(item.cover)
                .target(binding.coverThumbnailImage)
                .transformations(
                    SquareCropTransformation.INSTANCE,
                    RoundedRectTransformation(cornerPx),
                )
                .build()
        )
    }
}

private class ActionViewHolder(private val binding: ItemMenuOptionBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: CoverPickerItem.ActionItem, listener: CoverPickerListener) {
        binding.root.setOnClickListener { listener.onActionSelected(item) }
        binding.title.apply {
            setText(item.titleRes)
            isEnabled = true
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                context.getDrawableCompat(item.iconRes),
                null,
                null,
                null,
            )
        }
    }
}
