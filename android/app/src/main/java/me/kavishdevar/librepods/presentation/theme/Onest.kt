/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import me.kavishdevar.librepods.R

/**
 * Onest (SIL OFL 1.1), a single variable file pinned to the two weights this app uses.
 * Bold requests resolve to the 500 instance on purpose: the previous bundled font only
 * shipped Regular, so every "bold" in the UI was Android's synthetic emboldening; 500 is
 * the real weight closest to that look.
 */
val OnestFontFamily: FontFamily = FontFamily(
    Font(
        resId = R.font.onest,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.onest,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.onest,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)
