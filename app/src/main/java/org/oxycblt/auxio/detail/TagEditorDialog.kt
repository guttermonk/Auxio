/*
 * Copyright (c) 2025 Auxio Project
 * TagEditorDialog.kt is part of Auxio.
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

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogTagEditorBinding
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@AndroidEntryPoint
class TagEditorDialog : ViewBindingMaterialDialogFragment<DialogTagEditorBinding>() {
    private val tagModel: TagEditorViewModel by viewModels()
    private val args: TagEditorDialogArgs by navArgs()
    private var pendingFields: TagFields? = null

    private val writePermLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val fields = pendingFields
            pendingFields = null
            if (result.resultCode == Activity.RESULT_OK && fields != null) {
                tagModel.saveTags(fields)
            }
        }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogTagEditorBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        super.onConfigDialog(builder)
        builder
            .setTitle(R.string.lbl_edit_tags)
            .setPositiveButton(R.string.lbl_save, null)
            .setNegativeButton(android.R.string.cancel, null)
    }

    override fun onBindingCreated(binding: DialogTagEditorBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        tagModel.setSong(args.songUid)
        collectImmediately(tagModel.currentSong, ::updateSong)
        collectImmediately(tagModel.tagFields, ::populateFields)
        collectImmediately(tagModel.isLoading, ::updateLoading)
        collect(tagModel.saveResult.flow, ::handleSaveResult)
    }

    override fun onStart() {
        super.onStart()
        (requireDialog() as AlertDialog)
            .getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setOnClickListener { saveCurrentFields() }
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            L.d("No song to edit, navigating away")
            findNavController().navigateUp()
        }
    }

    private fun populateFields(fields: TagFields?) {
        if (fields == null) return
        val binding = requireBinding()
        binding.tagEditorTitle.setText(fields.title)
        binding.tagEditorArtist.setText(fields.artist)
        binding.tagEditorAlbum.setText(fields.album)
        binding.tagEditorAlbumArtist.setText(fields.albumArtist)
        binding.tagEditorTrack.setText(fields.track)
        binding.tagEditorDisc.setText(fields.disc)
        binding.tagEditorYear.setText(fields.year)
        binding.tagEditorGenre.setText(fields.genre)
        binding.tagEditorComment.setText(fields.comment)
    }

    private fun updateLoading(loading: Boolean) {
        val binding = requireBinding()
        val dialog = requireDialog() as AlertDialog
        binding.tagEditorLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tagEditorFields.visibility = if (loading) View.GONE else View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !loading
    }

    private fun saveCurrentFields() {
        val binding = requireBinding()
        val fields =
            TagFields(
                title = binding.tagEditorTitle.text.toString(),
                artist = binding.tagEditorArtist.text.toString(),
                album = binding.tagEditorAlbum.text.toString(),
                albumArtist = binding.tagEditorAlbumArtist.text.toString(),
                track = binding.tagEditorTrack.text.toString(),
                disc = binding.tagEditorDisc.text.toString(),
                year = binding.tagEditorYear.text.toString(),
                genre = binding.tagEditorGenre.text.toString(),
                comment = binding.tagEditorComment.text.toString(),
            )
        val song = tagModel.currentSong.value ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingFields = fields
            val request =
                MediaStore.createWriteRequest(
                    requireContext().contentResolver,
                    listOf(song.uri),
                )
            writePermLauncher.launch(IntentSenderRequest.Builder(request).build())
        } else {
            tagModel.saveTags(fields)
        }
    }

    private fun handleSaveResult(error: String?) {
        if (error == null) return
        tagModel.saveResult.consume()
        if (error.isEmpty()) {
            requireContext().showToast(R.string.lng_tags_saved)
            findNavController().navigateUp()
        } else {
            Toast.makeText(requireContext(), "Could not save tags: $error", Toast.LENGTH_LONG)
                .show()
        }
    }
}
