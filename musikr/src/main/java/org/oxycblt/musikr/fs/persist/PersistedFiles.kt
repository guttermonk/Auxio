/*
 * Copyright (c) 2026 Auxio Project
 * PersistedFiles.kt is part of Auxio.
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

package org.oxycblt.musikr.fs.persist

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File as JFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * Persistence of the file list discovered during indexing so a future startup can skip the slow
 * filesystem walk. After a successful index, the explored file list is written to disk; on next
 * startup, [replay] yields the same list directly without touching MediaStore/SAF, letting the
 * pipeline rebuild the library from cache hits while the real FS walk runs separately to catch
 * additions/removals.
 */
object PersistedFiles {
    private const val MAGIC = 0x4D55534C // "MUSL"
    private const val VERSION = 1

    private const val VOLUME_INTERNAL: Int = 0
    private const val VOLUME_EXTERNAL: Int = 1
    private const val VOLUME_THIRD_PARTY: Int = 2

    /**
     * Wrap [delegate] so every file it produces during exploration is captured and written to
     * [file] tagged with [revision]. The recording is only persisted if exploration completes
     * without error.
     */
    fun recording(delegate: FS, file: JFile, revision: UUID): FS =
        RecordingFS(delegate, file, revision)

    /**
     * Open an [FS] that replays files previously recorded to [file] under [revision]. Returns
     * null if the file is missing, the format/version doesn't match, or the recorded revision
     * differs from [revision] (i.e. a forced rescan happened in between).
     */
    fun replay(context: Context, file: JFile, revision: UUID): FS? {
        if (!file.isFile) return null
        val records = readRecords(file, revision) ?: return null
        return ReplayFS(records, VolumeManager.from(context))
    }

    private fun readRecords(file: JFile, revision: UUID): List<FileRecord>? =
        try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != MAGIC) return@use null
                if (input.readInt() != VERSION) return@use null
                val high = input.readLong()
                val low = input.readLong()
                if (UUID(high, low) != revision) return@use null
                val count = input.readInt()
                val out = ArrayList<FileRecord>(count)
                repeat(count) { out.add(FileRecord.read(input)) }
                out
            }
        } catch (_: Throwable) {
            null
        }

    private fun writeRecords(file: JFile, revision: UUID, records: List<FileRecord>) {
        val tmp = JFile(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(FileOutputStream(tmp).buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(revision.mostSignificantBits)
                output.writeLong(revision.leastSignificantBits)
                output.writeInt(records.size)
                for (record in records) record.write(output)
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private data class FileRecord(
        val uri: String,
        val volumeType: Int,
        val volumeKey: String,
        val pathComponents: List<String>,
        val modifiedMs: Long,
        val mimeType: String,
        val size: Long,
        val addedMs: Long,
    ) {
        fun write(out: DataOutputStream) {
            out.writeUTF(uri)
            out.writeByte(volumeType)
            out.writeUTF(volumeKey)
            out.writeInt(pathComponents.size)
            for (c in pathComponents) out.writeUTF(c)
            out.writeLong(modifiedMs)
            out.writeUTF(mimeType)
            out.writeLong(size)
            out.writeLong(addedMs)
        }

        fun toFile(volumeManager: VolumeManager): File? {
            val volume = resolveVolume(volumeManager) ?: return null
            return File(
                uri = Uri.parse(uri),
                path = Path(volume, Components.parseUnix(pathComponents.joinToString("/"))),
                addedMs = CachedAddedMs(addedMs),
                modifiedMs = modifiedMs,
                mimeType = mimeType,
                size = size,
                parent = null,
            )
        }

        private fun resolveVolume(volumeManager: VolumeManager): Volume? =
            when (volumeType) {
                VOLUME_INTERNAL ->
                    volumeManager.getVolumes().firstOrNull {
                        it is Volume.Internal && it.mediaStoreName == volumeKey
                    }
                VOLUME_EXTERNAL ->
                    volumeManager.getVolumes().firstOrNull {
                        it is Volume.External && it.mediaStoreName == volumeKey
                    }
                VOLUME_THIRD_PARTY -> Volume.ThirdParty(Uri.parse(volumeKey))
                else -> null
            }

        companion object {
            fun read(input: DataInputStream): FileRecord {
                val uri = input.readUTF()
                val volumeType = input.readByte().toInt()
                val volumeKey = input.readUTF()
                val count = input.readInt()
                val components = ArrayList<String>(count)
                repeat(count) { components.add(input.readUTF()) }
                val modifiedMs = input.readLong()
                val mimeType = input.readUTF()
                val size = input.readLong()
                val addedMs = input.readLong()
                return FileRecord(
                    uri = uri,
                    volumeType = volumeType,
                    volumeKey = volumeKey,
                    pathComponents = components,
                    modifiedMs = modifiedMs,
                    mimeType = mimeType,
                    size = size,
                    addedMs = addedMs,
                )
            }

            suspend fun fromFile(file: File): FileRecord {
                val volume = file.path.volume
                val volumeType =
                    when (volume) {
                        is Volume.Internal -> VOLUME_INTERNAL
                        is Volume.External -> VOLUME_EXTERNAL
                        is Volume.ThirdParty -> VOLUME_THIRD_PARTY
                    }
                val volumeKey =
                    when (volume) {
                        is Volume.Internal -> volume.mediaStoreName.orEmpty()
                        is Volume.External -> volume.mediaStoreName.orEmpty()
                        is Volume.ThirdParty -> volume.uri.toString()
                    }
                return FileRecord(
                    uri = file.uri.toString(),
                    volumeType = volumeType,
                    volumeKey = volumeKey,
                    pathComponents = file.path.components.components,
                    modifiedMs = file.modifiedMs,
                    mimeType = file.mimeType,
                    size = file.size,
                    addedMs = file.addedMs.resolve() ?: NO_ADDED_MS,
                )
            }

            private const val NO_ADDED_MS = -1L
        }
    }

    private class CachedAddedMs(private val ms: Long) : AddedMs {
        override suspend fun resolve(): Long? = if (ms < 0) null else ms
    }

    private class RecordingFS(
        private val delegate: FS,
        private val outFile: JFile,
        private val revision: UUID,
    ) : FS {
        override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> =
            coroutineScope {
                val intermediate = Channel<File>(Channel.UNLIMITED)
                val delegateAsync =
                    async(Dispatchers.IO) { delegate.explore(intermediate).await().getOrThrow() }
                tryAsyncWith(files, Dispatchers.IO) { channel ->
                    val records = mutableListOf<FileRecord>()
                    for (file in intermediate) {
                        records += FileRecord.fromFile(file)
                        channel.send(file)
                    }
                    delegateAsync.await()
                    // Persistence failure must not fail the whole index run; the library is
                    // already produced and the next startup will fall through to phase 1.
                    try {
                        writeRecords(outFile, revision, records)
                    } catch (_: Throwable) {}
                }
            }

        override fun track(): Flow<FSUpdate> = delegate.track()
    }

    private class ReplayFS(
        private val records: List<FileRecord>,
        private val volumeManager: VolumeManager,
    ) : FS {
        override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> =
            coroutineScope {
                tryAsyncWith(files, Dispatchers.IO) { channel ->
                    for (record in records) {
                        val file = record.toFile(volumeManager) ?: continue
                        channel.send(file)
                    }
                }
            }

        override fun track(): Flow<FSUpdate> = emptyFlow()
    }
}
