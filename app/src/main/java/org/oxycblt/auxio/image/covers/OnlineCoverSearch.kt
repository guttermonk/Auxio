/*
 * Copyright (c) 2025 Auxio Project
 * OnlineCoverSearch.kt is part of Auxio.
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

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import timber.log.Timber as L

/**
 * Fetches album cover art URLs from three free, no-key-required online sources: iTunes Search API,
 * Deezer API, and the MusicBrainz/Cover Art Archive.
 *
 * All methods are blocking and must be called from a background thread.
 */
object OnlineCoverSearch {

    data class Result(val thumbnailUrl: String, val fullUrl: String, val source: String)

    fun fetchItunes(albumName: String, artistName: String): Result? =
        try {
            val q = URLEncoder.encode("$albumName $artistName", "UTF-8")
            val json =
                JSONObject(get("https://itunes.apple.com/search?term=$q&entity=album&limit=5"))
            val results = json.optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val thumb = results.getJSONObject(i).optString("artworkUrl100")
                if (thumb.isNotEmpty()) {
                    val full = thumb.replace("100x100bb", "10000x10000bb")
                    return Result(thumb, full, "iTunes")
                }
            }
            null
        } catch (e: Exception) {
            L.w(e, "iTunes cover search failed")
            null
        }

    fun fetchDeezer(albumName: String, artistName: String): Result? =
        try {
            val q = URLEncoder.encode("$albumName $artistName", "UTF-8")
            val json = JSONObject(get("https://api.deezer.com/search/album?q=$q&limit=5"))
            val data = json.optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val coverXl = data.getJSONObject(i).optString("cover_xl")
                if (coverXl.isNotEmpty()) return Result(coverXl, coverXl, "Deezer")
            }
            null
        } catch (e: Exception) {
            L.w(e, "Deezer cover search failed")
            null
        }

    fun fetchCoverArtArchive(albumName: String, artistName: String): Result? =
        try {
            val query =
                URLEncoder.encode("release:\"$albumName\" AND artist:\"$artistName\"", "UTF-8")
            val mbUrl = "https://musicbrainz.org/ws/2/release/?query=$query&limit=5&fmt=json"
            // MusicBrainz API policy requires a descriptive User-Agent.
            val json =
                JSONObject(get(mbUrl, userAgent = "Auxio/4.0 (github.com/OxygenCobalt/Auxio)"))
            val releases = json.optJSONArray("releases") ?: return null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val hasArt =
                    release.optJSONObject("cover-art-archive")?.optBoolean("artwork", false)
                        ?: false
                if (hasArt) {
                    val mbid = release.optString("id")
                    if (mbid.isNotEmpty()) {
                        return Result(
                            "https://coverartarchive.org/release/$mbid/front-250",
                            "https://coverartarchive.org/release/$mbid/front",
                            "MusicBrainz",
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            L.w(e, "MusicBrainz/Cover Art Archive search failed")
            null
        }

    private fun get(url: String, userAgent: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
