/*
 * Copyright (c) 2023 Auxio Project
 * HomeSettings.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.util.unlikelyToBeNull
import timber.log.Timber as L

/**
 * User configuration specific to the home UI.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface HomeSettings : Settings<HomeSettings.Listener> {
    /** The tabs to show in the home UI. */
    var homeTabs: Array<Tab>
    /** Whether to hide artists considered "collaborators" from the home UI. */
    val shouldHideCollaborators: Boolean
    /** The layout mode for the song browser tab. */
    val songBrowserLayout: BrowserLayout
    /** The layout mode for the album browser tab. */
    val albumBrowserLayout: BrowserLayout
    /** The layout mode for the artist browser tab. */
    val artistBrowserLayout: BrowserLayout
    /** The layout mode for the genre browser tab. */
    val genreBrowserLayout: BrowserLayout
    /** The layout mode for the playlist browser tab. */
    val playlistBrowserLayout: BrowserLayout

    interface Listener {
        /** Called when the [homeTabs] configuration changes. */
        fun onTabsChanged() {}

        /** Called when the [shouldHideCollaborators] configuration changes. */
        fun onHideCollaboratorsChanged() {}

        /** Called when any browser layout configuration changes. */
        fun onBrowserLayoutChanged() {}
    }
}

class HomeSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<HomeSettings.Listener>(context), HomeSettings {
    override var homeTabs: Array<Tab>
        get() =
            Tab.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_home_tabs),
                    Tab.SEQUENCE_DEFAULT,
                )
            ) ?: unlikelyToBeNull(Tab.fromIntCode(Tab.SEQUENCE_DEFAULT))
        set(value) {
            sharedPreferences.edit {
                putInt(getString(R.string.set_key_home_tabs), Tab.toIntCode(value))
                apply()
            }
        }

    override val shouldHideCollaborators: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_hide_collaborators), false)

    override val songBrowserLayout: BrowserLayout
        get() =
            BrowserLayout.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_song_browser_layout),
                    IntegerTable.BROWSER_LAYOUT_LIST,
                )
            )

    override val albumBrowserLayout: BrowserLayout
        get() =
            BrowserLayout.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_album_browser_layout),
                    IntegerTable.BROWSER_LAYOUT_LIST,
                )
            )

    override val artistBrowserLayout: BrowserLayout
        get() =
            BrowserLayout.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_artist_browser_layout),
                    IntegerTable.BROWSER_LAYOUT_LIST,
                )
            )

    override val genreBrowserLayout: BrowserLayout
        get() =
            BrowserLayout.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_genre_browser_layout),
                    IntegerTable.BROWSER_LAYOUT_LIST,
                )
            )

    override val playlistBrowserLayout: BrowserLayout
        get() =
            BrowserLayout.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_playlist_browser_layout),
                    IntegerTable.BROWSER_LAYOUT_LIST,
                )
            )

    override fun migrate() {
        if (sharedPreferences.contains(OLD_KEY_LIB_TABS)) {
            L.d("Migrating tab setting")
            val oldTabs =
                Tab.fromIntCode(sharedPreferences.getInt(OLD_KEY_LIB_TABS, Tab.SEQUENCE_DEFAULT))
                    ?: unlikelyToBeNull(Tab.fromIntCode(Tab.SEQUENCE_DEFAULT))
            L.d("Old tabs: $oldTabs")

            // The playlist tab is now parsed, but it needs to be made visible.
            val playlistIndex = oldTabs.indexOfFirst { it.type == MusicType.PLAYLISTS }
            check(playlistIndex > -1) // This should exist, otherwise we are in big trouble
            oldTabs[playlistIndex] = Tab.Visible(MusicType.PLAYLISTS)
            L.d("New tabs: $oldTabs")

            sharedPreferences.edit {
                putInt(getString(R.string.set_key_home_tabs), Tab.toIntCode(oldTabs))
                remove(OLD_KEY_LIB_TABS)
            }
        }
    }

    override fun onSettingChanged(key: String, listener: HomeSettings.Listener) {
        when (key) {
            getString(R.string.set_key_home_tabs) -> {
                L.d("Dispatching tab setting change")
                listener.onTabsChanged()
            }
            getString(R.string.set_key_hide_collaborators) -> {
                L.d("Dispatching collaborator setting change")
                listener.onHideCollaboratorsChanged()
            }
            getString(R.string.set_key_song_browser_layout),
            getString(R.string.set_key_album_browser_layout),
            getString(R.string.set_key_artist_browser_layout),
            getString(R.string.set_key_genre_browser_layout),
            getString(R.string.set_key_playlist_browser_layout) -> {
                L.d("Dispatching browser layout change")
                listener.onBrowserLayoutChanged()
            }
        }
    }

    companion object {
        const val OLD_KEY_LIB_TABS = "auxio_lib_tabs"
    }
}
