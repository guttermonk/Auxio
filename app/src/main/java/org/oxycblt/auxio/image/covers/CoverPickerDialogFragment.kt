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

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.target
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogCoverPickerBinding
import org.oxycblt.auxio.image.CoverView
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
                showConfirmDialog(newCoverUri = uri) { pickerModel.saveCover(uri) }
            }
        }

    private var pendingSave: (() -> Unit)? = null

    private val writePermLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val save = pendingSave
            pendingSave = null
            if (result.resultCode == Activity.RESULT_OK && save != null) {
                save()
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
        collectImmediately(pickerModel.pickerItems) { items -> coverAdapter.submitList(items) }
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
        showConfirmDialog(newCoverCover = item.cover) { pickerModel.saveCoverFromLibrary(item) }
    }

    override fun onActionSelected(item: CoverPickerItem.ActionItem) {
        when (item.id) {
            CoverPickerItem.ACTION_BROWSE -> launchGalleryPicker()
            CoverPickerItem.ACTION_SEARCH -> launchOnlineSearch()
            CoverPickerItem.ACTION_CLEAR -> confirmClearCover()
            else -> error("Unknown action id ${item.id}")
        }
    }

    override fun onOnlineCoverSelected(item: CoverPickerItem.OnlineCoverOption) {
        L.d("Online cover selected: source=${item.source} url=${item.fullUrl}")
        showConfirmDialog(newCoverFile = item.thumbFile) { pickerModel.saveOnlineCover(item) }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun requestWriteAndSave(save: () -> Unit) {
        val album = pickerModel.currentAlbum.value ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingSave = save
            val uris = album.songs.map { it.uri }
            val request = MediaStore.createWriteRequest(requireContext().contentResolver, uris)
            writePermLauncher.launch(IntentSenderRequest.Builder(request).build())
        } else {
            save()
        }
    }

    private fun showConfirmDialog(
        newCoverUri: Uri? = null,
        newCoverFile: java.io.File? = null,
        newCoverCover: org.oxycblt.musikr.covers.Cover? = null,
        onSave: () -> Unit,
    ) {
        val album = pickerModel.currentAlbum.value ?: return
        val view =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cover_confirm, null)
        view.findViewById<CoverView>(R.id.cover_confirm_old).bind(album)
        val newImage = view.findViewById<ImageView>(R.id.cover_confirm_new)
        when {
            newCoverUri != null -> newImage.setImageURI(newCoverUri)
            newCoverFile != null -> {
                val bmp = BitmapFactory.decodeFile(newCoverFile.absolutePath)
                newImage.setImageBitmap(bmp)
            }
            newCoverCover != null ->
                imageLoader.enqueue(
                    ImageRequest.Builder(requireContext())
                        .data(newCoverCover)
                        .target(newImage)
                        .build()
                )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_change_cover)
            .setView(view)
            .setPositiveButton(R.string.lbl_replace_cover) { _, _ -> requestWriteAndSave(onSave) }
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

    private fun confirmClearCover() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_clear_cover)
            .setMessage(R.string.lng_clear_cover_confirm)
            .setPositiveButton(R.string.lbl_clear) { _, _ ->
                requestWriteAndSave { pickerModel.clearCover() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    if (pickerModel.hasCoverArt.value) R.string.lng_cover_saved
                    else R.string.lng_cover_cleared
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
