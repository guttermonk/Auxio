/*
 * Copyright (c) 2024 Auxio Project
 * CoverPickerDialogFragment.kt is part of Auxio.
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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import coil3.ImageLoader
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogCoverPickerBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Album
import timber.log.Timber as L

/**
 * A bottom-sheet dialog that lets the user choose a custom album cover from:
 * - Artwork already embedded in the album's tracks (shown as a thumbnail grid)
 * - The device's photo/file gallery (system picker)
 * - An online image search (DuckDuckGo, browser intent — no data leaves the app)
 * - A "Reset to default" action when a custom cover is already applied
 */
@AndroidEntryPoint
class CoverPickerDialogFragment :
    ViewBindingBottomSheetDialogFragment<DialogCoverPickerBinding>(), CoverPickerListener {

    @Inject lateinit var imageLoader: ImageLoader

    private val pickerModel: CoverPickerViewModel by viewModels()
    private val args: CoverPickerDialogFragmentArgs by navArgs()

    private val galleryPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                L.d("Gallery image selected: $uri")
                showConfirmDialog { permanent -> pickerModel.saveCover(uri, permanent) }
            }
        }

    private lateinit var coverAdapter: CoverPickerAdapter

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogCoverPickerBinding.inflate(inflater)

    override fun onStart() {
        super.onStart()
        (dialog as? BackportBottomSheetDialog)?.behavior?.state =
            BackportBottomSheetBehavior.STATE_EXPANDED
    }

    override fun onBindingCreated(binding: DialogCoverPickerBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        coverAdapter = CoverPickerAdapter(imageLoader, this)

        val layoutManager =
            GridLayoutManager(requireContext(), CoverPickerAdapter.SPAN_COUNT).apply {
                spanSizeLookup = coverAdapter.spanSizeLookup
            }

        binding.coverPickerRecycler.apply {
            adapter = coverAdapter
            this.layoutManager = layoutManager
            itemAnimator = null
        }

        pickerModel.setAlbum(args.albumUid)
        collectImmediately(pickerModel.currentAlbum, ::updateAlbumHeader)
        collectImmediately(pickerModel.pickerItems) { items ->
            coverAdapter.submitList(items) {
                // Re-measure the bottom sheet so it grows to fit thumbnails
                dialog
                    ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.requestLayout()
            }
        }
        collect(pickerModel.saveResult.flow, ::handleSaveResult)
    }

    override fun onDestroyBinding(binding: DialogCoverPickerBinding) {
        super.onDestroyBinding(binding)
        binding.coverPickerRecycler.adapter = null
    }

    // -----------------------------------------------------------------------
    // CoverPickerListener
    // -----------------------------------------------------------------------

    override fun onCoverSelected(item: CoverPickerItem.CoverOption) {
        L.d("Library cover selected: index=${item.index}")
        showConfirmDialog { permanent -> pickerModel.saveCoverFromLibrary(item, permanent) }
    }

    override fun onActionSelected(item: CoverPickerItem.ActionItem) {
        when (item.id) {
            CoverPickerItem.ACTION_BROWSE -> launchGalleryPicker()
            CoverPickerItem.ACTION_SEARCH -> launchOnlineSearch()
            CoverPickerItem.ACTION_RESET -> pickerModel.resetCover()
            else -> error("Unknown action id ${item.id}")
        }
    }

    override fun onOnlineCoverSelected(item: CoverPickerItem.OnlineCoverOption) {
        L.d("Online cover selected: source=${item.source} url=${item.fullUrl}")
        showConfirmDialog { permanent -> pickerModel.saveOnlineCover(item, permanent) }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun showConfirmDialog(onSave: (permanent: Boolean) -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_change_cover)
            .setPositiveButton(R.string.lbl_replace_cover) { _, _ -> onSave(true) }
            .setNeutralButton(R.string.lbl_update_cover) { _, _ -> onSave(false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAlbumHeader(album: Album?) {
        if (album == null) {
            L.d("No album to show, navigating away")
            findNavController().navigateUp()
            return
        }
        val binding = requireBinding()
        val context = requireContext()
        binding.coverPickerCover.bind(album)
        binding.coverPickerType.text = album.releaseType.resolve(context)
        binding.coverPickerName.text = album.name.resolve(context)
        binding.coverPickerInfo.text = album.artists.resolveNames(context)
    }

    private fun launchGalleryPicker() {
        galleryPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchOnlineSearch() {
        val album = pickerModel.currentAlbum.value ?: return
        val context = requireContext()
        val albumName = album.name.resolve(context)
        val artistName = album.artists.resolveNames(context)
        // Build a DuckDuckGo image-search URL.  The query intentionally includes
        // "album cover" so results skew toward artwork rather than live photos.
        val query = Uri.encode("$albumName $artistName album cover")
        val searchUri = Uri.parse("https://duckduckgo.com/?q=$query&ia=images&iax=images")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, searchUri))
        } catch (e: Exception) {
            L.e(e, "No browser available to open image search")
            context.showToast(R.string.err_no_browser)
        }
    }

    private fun handleSaveResult(success: Boolean?) {
        if (success == null) return
        pickerModel.saveResult.consume()
        if (success) {
            requireContext()
                .showToast(
                    if (pickerModel.hasCustomCover.value) R.string.lng_cover_saved
                    else R.string.lng_cover_reset
                )
            // Pop both the cover picker and the album menu dialog so the user lands back
            // at the album detail (or wherever they opened the menu from).
            if (!findNavController().popBackStack(R.id.album_menu_dialog, true)) {
                findNavController().navigateUp()
            }
        } else {
            // On failure, stay in the picker so the user can try a different source.
            requireContext().showToast(R.string.lng_cover_save_failed)
        }
    }
}
