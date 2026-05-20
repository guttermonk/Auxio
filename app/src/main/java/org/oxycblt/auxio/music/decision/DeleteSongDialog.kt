/*
 * Copyright (c) 2025 Auxio Project
 * DeleteSongDialog.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.decision

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogDeleteSongBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

@AndroidEntryPoint
class DeleteSongDialog : ViewBindingMaterialDialogFragment<DialogDeleteSongBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val args: DeleteSongDialogArgs by navArgs()

    @Inject lateinit var musicRepository: MusicRepository

    private val deletePermLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                L.d("Delete permission granted, song deleted by system")
                requireContext().showToast(R.string.lng_song_deleted)
                findNavController().navigateUp()
                musicModel.refresh()
            } else {
                requireContext().showToast(R.string.lng_song_delete_failed)
            }
        }

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_confirm_delete_song)
            .setPositiveButton(R.string.lbl_delete) { _, _ -> deleteSong() }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogDeleteSongBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogDeleteSongBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val song = musicRepository.library?.findSong(args.songUid)
        if (song == null) {
            findNavController().navigateUp()
            return
        }
        binding.deletionInfo.text =
            getString(R.string.fmt_deletion_info, song.path.name ?: song.name.raw)
    }

    private fun deleteSong() {
        val song = musicRepository.library?.findSong(args.songUid)
        if (song == null) {
            findNavController().navigateUp()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request =
                MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(song.uri))
            deletePermLauncher.launch(IntentSenderRequest.Builder(request).build())
        } else {
            try {
                val rows = requireContext().contentResolver.delete(song.uri, null, null)
                if (rows > 0) {
                    L.d("Deleted song file: ${song.path.name}")
                    requireContext().showToast(R.string.lng_song_deleted)
                    findNavController().navigateUp()
                    musicModel.refresh()
                } else {
                    L.e("Failed to delete song file: ${song.path.name}")
                    requireContext().showToast(R.string.lng_song_delete_failed)
                }
            } catch (e: Exception) {
                L.e(e, "Failed to delete song file: ${song.path.name}")
                requireContext().showToast(R.string.lng_song_delete_failed)
            }
        }
    }
}
