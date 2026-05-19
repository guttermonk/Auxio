/*
 * Copyright (c) 2025 Auxio Project
 * BulkTagEditorDialog.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogBulkTagEditorBinding
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.auxio.util.showToast

@AndroidEntryPoint
class BulkTagEditorDialog :
    ViewBindingMaterialDialogFragment<DialogBulkTagEditorBinding>() {
    private val tagModel: BulkTagEditorViewModel by viewModels()
    private val args: BulkTagEditorDialogArgs by navArgs()

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogBulkTagEditorBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        super.onConfigDialog(builder)
        builder
            .setTitle(R.string.lbl_edit_tags)
            .setPositiveButton(R.string.lbl_save, null)
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun onBindingCreated(
        binding: DialogBulkTagEditorBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        tagModel.setSongs(args.songUids.toList())
        collectImmediately(tagModel.tagFields, ::populateFields)
        collectImmediately(tagModel.isLoading, ::updateLoading)
        collectImmediately(tagModel.songs) { songs ->
            binding.bulkTagEditorHint.text =
                requireContext().getPlural(R.plurals.fmt_editing_songs, songs.size)
        }
        collect(tagModel.saveResult.flow, ::handleSaveResult)
    }

    override fun onStart() {
        super.onStart()
        (requireDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setOnClickListener { saveCurrentFields() }
    }

    private fun populateFields(fields: BulkTagFields?) {
        if (fields == null) return
        val binding = requireBinding()
        binding.bulkTagEditorArtist.setText(fields.artist)
        binding.bulkTagEditorAlbum.setText(fields.album)
        binding.bulkTagEditorAlbumArtist.setText(fields.albumArtist)
        binding.bulkTagEditorYear.setText(fields.year)
        binding.bulkTagEditorGenre.setText(fields.genre)
    }

    private fun updateLoading(loading: Boolean) {
        val binding = requireBinding()
        val dialog = requireDialog() as AlertDialog
        binding.bulkTagEditorLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.bulkTagEditorFields.visibility = if (loading) View.GONE else View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !loading
    }

    private fun saveCurrentFields() {
        val binding = requireBinding()
        val fields =
            BulkTagFields(
                artist = binding.bulkTagEditorArtist.text.toString(),
                album = binding.bulkTagEditorAlbum.text.toString(),
                albumArtist = binding.bulkTagEditorAlbumArtist.text.toString(),
                year = binding.bulkTagEditorYear.text.toString(),
                genre = binding.bulkTagEditorGenre.text.toString(),
            )
        tagModel.saveTags(fields)
    }

    private fun handleSaveResult(success: Boolean?) {
        if (success == null) return
        tagModel.saveResult.consume()
        if (success) {
            requireContext().showToast(R.string.lng_tags_saved)
            findNavController().navigateUp()
        } else {
            requireContext().showToast(R.string.lng_tags_save_failed)
        }
    }
}
