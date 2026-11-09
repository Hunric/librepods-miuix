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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.theme.TextStyles as MiuixTextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/** Material 3 typography derived from the active Miuix text styles. */
internal fun materialTypographyFromMiuix(styles: MiuixTextStyles): Typography {
    val base = Typography().copy(
        displayLarge = styles.title1,
        displayMedium = styles.title2,
        displaySmall = styles.title3,
        headlineLarge = styles.title2,
        headlineMedium = styles.title3,
        headlineSmall = styles.title4,
        titleLarge = styles.title3,
        titleMedium = styles.title4,
        titleSmall = styles.subtitle,
        bodyLarge = styles.paragraph,
        bodyMedium = styles.main,
        bodySmall = styles.body2,
        labelLarge = styles.button,
        labelMedium = styles.body2,
        labelSmall = styles.footnote1,
    )

    return base.copy(
        displayLargeEmphasized = base.displayLarge.emphasized(),
        displayMediumEmphasized = base.displayMedium.emphasized(),
        displaySmallEmphasized = base.displaySmall.emphasized(),
        titleLargeEmphasized = base.titleLarge.emphasized(),
        titleMediumEmphasized = base.titleMedium.emphasized(),
        titleSmallEmphasized = base.titleSmall.emphasized(),
        bodyLargeEmphasized = base.bodyLarge.emphasized(),
        bodyMediumEmphasized = base.bodyMedium.emphasized(),
        bodySmallEmphasized = base.bodySmall.emphasized(),
        labelLargeEmphasized = base.labelLarge.emphasized(),
        labelMediumEmphasized = base.labelMedium.emphasized(),
        labelSmallEmphasized = base.labelSmall.emphasized(),
    )
}

private fun TextStyle.emphasized(): TextStyle = copy(fontWeight = FontWeight.SemiBold)

/** Kept for existing call sites that need a standalone M3 style outside the theme scope. */
val MaterialTypography: Typography = materialTypographyFromMiuix(defaultTextStyles())
