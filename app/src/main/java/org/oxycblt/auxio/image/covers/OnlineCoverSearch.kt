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
 * Fetches album cover art URLs from free, no-key-required online sources: iTunes Search API, Deezer
 * API, MusicBrainz/Cover Art Archive, TheAudioDB, and Wikipedia.
 *
 * All methods are blocking and must be called from a background thread.
 */
object OnlineCoverSearch {

    data class Result(val thumbnailUrl: String, val fullUrl: String, val source: String)

    fun fetchItunes(albumName: String, artistName: String): List<Result> =
        try {
            val terms = if (artistName.isNotEmpty()) "$albumName $artistName" else albumName
            val q = URLEncoder.encode(terms, "UTF-8")
            val json =
                JSONObject(get("https://itunes.apple.com/search?term=$q&entity=album&limit=5"))
            val results = json.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val thumb = results.getJSONObject(i).optString("artworkUrl100")
                if (thumb.isEmpty()) return@mapNotNull null
                Result(thumb, thumb.replace("100x100bb", "10000x10000bb"), "iTunes")
            }
        } catch (e: Exception) {
            L.w(e, "iTunes cover search failed")
            emptyList()
        }

    fun fetchDeezer(albumName: String, artistName: String): List<Result> =
        try {
            val terms = if (artistName.isNotEmpty()) "$albumName $artistName" else albumName
            val q = URLEncoder.encode(terms, "UTF-8")
            val json = JSONObject(get("https://api.deezer.com/search/album?q=$q&limit=5"))
            val data = json.optJSONArray("data") ?: return emptyList()
            (0 until data.length()).mapNotNull { i ->
                val coverXl = data.getJSONObject(i).optString("cover_xl")
                if (coverXl.isEmpty()) return@mapNotNull null
                Result(coverXl, coverXl, "Deezer")
            }
        } catch (e: Exception) {
            L.w(e, "Deezer cover search failed")
            emptyList()
        }

    fun fetchCoverArtArchive(albumName: String, artistName: String): List<Result> =
        try {
            val raw =
                if (artistName.isNotEmpty()) {
                    "release:\"$albumName\" AND artist:\"$artistName\""
                } else {
                    "release:\"$albumName\""
                }
            val query = URLEncoder.encode(raw, "UTF-8")
            val mbUrl = "https://musicbrainz.org/ws/2/release/?query=$query&limit=5&fmt=json"
            val json =
                JSONObject(get(mbUrl, userAgent = "Auxio/4.0 (github.com/OxygenCobalt/Auxio)"))
            val releases = json.optJSONArray("releases") ?: return emptyList()
            (0 until releases.length()).mapNotNull { i ->
                val release = releases.getJSONObject(i)
                val hasArt =
                    release.optJSONObject("cover-art-archive")?.optBoolean("artwork", false)
                        ?: false
                if (!hasArt) return@mapNotNull null
                val mbid = release.optString("id")
                if (mbid.isEmpty()) return@mapNotNull null
                Result(
                    "https://coverartarchive.org/release/$mbid/front-250",
                    "https://coverartarchive.org/release/$mbid/front",
                    "MusicBrainz",
                )
            }
        } catch (e: Exception) {
            L.w(e, "MusicBrainz/Cover Art Archive search failed")
            emptyList()
        }

    fun fetchTheAudioDB(albumName: String, artistName: String): List<Result> =
        try {
            // TheAudioDB requires an artist name; skip in fallback queries
            if (artistName.isEmpty()) return emptyList()
            val s = URLEncoder.encode(artistName, "UTF-8")
            val a = URLEncoder.encode(albumName, "UTF-8")
            val url = "https://theaudiodb.com/api/v1/json/2/searchalbum.php?s=$s&a=$a"
            val json = JSONObject(get(url))
            val albums = json.optJSONArray("album") ?: return emptyList()
            (0 until albums.length()).mapNotNull { i ->
                val thumb = albums.getJSONObject(i).optString("strAlbumThumb")
                if (thumb.isEmpty()) return@mapNotNull null
                Result(thumb, thumb, "TheAudioDB")
            }
        } catch (e: Exception) {
            L.w(e, "TheAudioDB cover search failed")
            emptyList()
        }

    fun fetchWikipedia(albumName: String, artistName: String): List<Result> =
        try {
            val terms =
                if (artistName.isNotEmpty()) "$albumName $artistName album cover art"
                else "$albumName album cover art"
            val q = URLEncoder.encode(terms, "UTF-8")
            val url =
                "https://en.wikipedia.org/w/api.php?action=query&generator=search" +
                    "&gsrsearch=$q&gsrlimit=5&prop=pageimages" +
                    "&piprop=thumbnail|original&pithumbsize=250&format=json"
            val ua = "Auxio/4.0 (github.com/OxygenCobalt/Auxio)"
            val json = JSONObject(get(url, userAgent = ua))
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            pages
                .keys()
                .asSequence()
                .mapNotNull { key ->
                    val page = pages.getJSONObject(key)
                    val thumb =
                        page.optJSONObject("thumbnail")?.optString("source")
                            ?: return@mapNotNull null
                    val original = page.optJSONObject("original")?.optString("source") ?: thumb
                    Result(thumb, original, "Wikipedia")
                }
                .toList()
        } catch (e: Exception) {
            L.w(e, "Wikipedia cover search failed")
            emptyList()
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
