/*
 * Copyright (c) 2025 Auxio Project
 * BrowserLayout.kt is part of Auxio.
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

import org.oxycblt.auxio.IntegerTable

enum class BrowserLayout(val intCode: Int) {
    LIST(IntegerTable.BROWSER_LAYOUT_LIST),

    SMALL_GRID(IntegerTable.BROWSER_LAYOUT_SMALL_GRID),

    MEDIUM_GRID(IntegerTable.BROWSER_LAYOUT_MEDIUM_GRID),

    LARGE_GRID(IntegerTable.BROWSER_LAYOUT_LARGE_GRID),
    ;

    val spanCount: Int
        get() =
            when (this) {
                LIST -> 1
                SMALL_GRID -> 4
                MEDIUM_GRID -> 3
                LARGE_GRID -> 2
            }

    companion object {
        fun fromIntCode(intCode: Int) =
            when (intCode) {
                IntegerTable.BROWSER_LAYOUT_LIST -> LIST
                IntegerTable.BROWSER_LAYOUT_SMALL_GRID -> SMALL_GRID
                IntegerTable.BROWSER_LAYOUT_MEDIUM_GRID -> MEDIUM_GRID
                IntegerTable.BROWSER_LAYOUT_LARGE_GRID -> LARGE_GRID
                else -> LIST
            }
    }
}
