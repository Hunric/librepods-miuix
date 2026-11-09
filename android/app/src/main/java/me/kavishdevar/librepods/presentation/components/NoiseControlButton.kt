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

package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NoiseControlButton(
    icon: ImageBitmap,
    onClick: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
    usePadding: Boolean = true
) {
    MiuixIconButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .then(if (usePadding) Modifier.padding(horizontal = 4.dp, vertical = 4.dp) else Modifier),
        backgroundColor = Color.Transparent,
    ) {
        MiuixIcon(
            bitmap = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Preview
@Composable
fun NoiseControlButtonPreview() {
    LibrePodsTheme() {
        NoiseControlButton(
            icon = ImageBitmap.imageResource(R.drawable.noise_cancellation),
            onClick = {},
            textColor = MiuixTheme.colorScheme.onSurface,
        )
    }
}
