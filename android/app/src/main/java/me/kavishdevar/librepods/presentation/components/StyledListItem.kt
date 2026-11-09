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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.sectionHeader
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StyledListItem(
    modifier: Modifier = Modifier,
    title: String? = null,
    name: String,
    onClick: (() -> Unit)?,
    description: String? = null,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    orientation: ListItemOrientation = ListItemOrientation.Horizontal,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    if (LocalDesignSystem.current == DesignSystem.Miuix) {
        val isInStyledListCard = LocalIsInStyledListCard.current
        val itemContent: @Composable () -> Unit = {
            StyledListItemContent(
                modifier = if (isInStyledListCard) modifier else Modifier,
                name = name,
                onClick = onClick,
                description = description,
                height = height,
                enabled = enabled,
                index = 0,
                count = 1,
                orientation = orientation,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
            )
        }
        Column {
            title?.let {
                MiuixText(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            if (isInStyledListCard) {
                itemContent()
            } else {
                MiuixCard(
                    modifier = modifier.fillMaxWidth(),
                    insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    itemContent()
                }
            }
        }
        return
    }
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Miuix
    Column {
        title?.let {
            Box(
                modifier = Modifier
                    .background(if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = if (m3eEnabled) 8.dp else 4.dp)
            ) {
                Text(
                    text = it,
                    color = if (m3eEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    style = MaterialTheme.typography.labelSmallEmphasized
                )
            }
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    if (m3eEnabled) Color.Transparent else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(if (m3eEnabled) 16.dp else 28.dp)
                )
                .clip(RoundedCornerShape(if (m3eEnabled) 16.dp else 28.dp))
        ) {
            StyledListItemContent(
                name = name,
                onClick = onClick,
                description = description,
                height = height,
                enabled = enabled,
                index = 0,
                count = 1,
                orientation = orientation,
                leadingContent = leadingContent,
                trailingContent = trailingContent
            )
        }
    }
}

@Composable
fun StyledListScope.StyledListItem(
    modifier: Modifier = Modifier,
    name: String,
    onClick: (() -> Unit)? = null,
    description: String? = null,
    enabled: Boolean = onClick != null,
    orientation: ListItemOrientation = ListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    item { index, count ->
        StyledListItemContent(
            name = name,
            onClick = onClick,
            description = description,
            enabled = enabled,
            index = index,
            count = count,
            orientation = orientation,
            modifier = modifier,
            selected = selected,
            leadingContent = leadingContent,
            trailingContent = trailingContent
        )
    }
}

enum class ListItemOrientation{
    Horizontal,
    Vertical
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyledListItemContent(
    modifier: Modifier = Modifier,
    name: String,
    onClick: (() -> Unit)?,
    description: String? = null,
    height: Dp = 58.dp,
    enabled: Boolean = true,
    index: Int,
    count: Int,
    orientation: ListItemOrientation = ListItemOrientation.Horizontal,
    selected: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val isDarkTheme = isSystemInDarkTheme()
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceDimColor = MaterialTheme.colorScheme.surfaceDim
    var backgroundColor by remember { mutableStateOf(surfaceColor) }
    val animatedBackgroundColor by animateColorAsState(targetValue = backgroundColor, animationSpec = tween(durationMillis = 500))
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    when (LocalDesignSystem.current) {
        DesignSystem.Apple -> {
            val trailingContentDefault: @Composable () -> Unit = {
                if (trailingContent == null) {
                    if (onClick != null) {
                        if (selected != null) {
                            val floatAnimateState by animateFloatAsState(
                                targetValue = if (selected) 1f else 0f,
                                animationSpec = tween(durationMillis = 300)
                            )

                            Text(
                                text = "􀆅",
                                style = TextStyle(
                                    fontSize = 20.sp,
                                    fontFamily = FontFamily(Font(R.font.sf_pro)),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = floatAnimateState),
                                ),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        } else {
                            Text(
                                text = "􀯻",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                                modifier = Modifier
                                    .padding(start = if (description != null) 6.dp else 0.dp)
                            )
                        }
                    }
                } else {
                    trailingContent()
                }
            }
            Column (
                modifier = Modifier
                    .background(
                        animatedBackgroundColor,
                        when {
                            (index == 0 && count == 1) -> {
                                RoundedCornerShape(28.dp)
                            }

                            (index == 0) -> {
                                RoundedCornerShape(
                                    topStart = 28.dp,
                                    topEnd = 28.dp,
                                    bottomStart = 0.dp,
                                    bottomEnd = 0.dp
                                )
                            }

                            (index + 1 == count) -> {
                                RoundedCornerShape(
                                    topStart = 0.dp,
                                    topEnd = 0.dp,
                                    bottomStart = 28.dp,
                                    bottomEnd = 28.dp
                                )
                            }

                            else -> {
                                RectangleShape
                            }
                        }
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (enabled) {
                                    backgroundColor = surfaceDimColor
                                    tryAwaitRelease()
                                    backgroundColor = surfaceColor
                                }
                            },
                            onTap = {
                                if (enabled) {
                                    scope.launch {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.ContextClick
                                        )
                                    }
                                    onClick?.invoke()
                                }
                            }
                        )
                    }
                    .heightIn(min = height)
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = height)
                        .padding(vertical = if (orientation == ListItemOrientation.Vertical) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingContent != null) {
                        leadingContent()
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Column (verticalArrangement = Arrangement.Center) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (description != null && orientation == ListItemOrientation.Vertical) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(if (isDarkTheme) 0.6f else 0.8f), // TODO: move to color scheme
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (orientation == ListItemOrientation.Horizontal && description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(if (isDarkTheme) 0.6f else 0.8f) // TODO: move to color scheme
                        )
                    }

                    trailingContentDefault()
                }
                if (index+1 != count) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier
                            .padding(start = if (leadingContent != null) 12.dp else 0.dp)
                    )
                }
            }
        }

        DesignSystem.Miuix -> {
            val trailing: (@Composable () -> Unit)? = when {
                trailingContent != null -> trailingContent
                onClick != null && selected == true -> {
                    {
                        MiuixIcon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
                onClick != null && selected == null -> {
                    {
                        MiuixIcon(
                            imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                }
                else -> null
            }
            val selectionModifier = if (selected != null) {
                Modifier.semantics { this.selected = selected }
            } else {
                Modifier
            }
            if (orientation == ListItemOrientation.Vertical) {
                MiuixSettingsRow(
                    title = name,
                    summary = description,
                    titleStyle = MiuixTheme.textStyles.headline2,
                    modifier = modifier.heightIn(min = height).fillMaxWidth().then(selectionModifier),
                    leading = leadingContent,
                    trailing = if (trailingContent == null) trailing else null,
                    bottom = trailingContent,
                    onClick = onClick,
                    enabled = enabled,
                )
            } else {
                MiuixSettingsRow(
                    title = name,
                    summary = description,
                    modifier = modifier.heightIn(min = height).fillMaxWidth().then(selectionModifier),
                    leading = leadingContent,
                    trailing = trailing,
                    onClick = onClick,
                    enabled = enabled,
                )
            }
            if (index + 1 != count) Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Preview(name = "Miuix selection states", showBackground = true)
@Composable
private fun StyledListItemSelectionPreview() {
    LibrePodsTheme {
        var horizontalSelected by remember { mutableStateOf(false) }

        Column(modifier = Modifier.padding(16.dp)) {
            StyledList(
                title = "Vertical selection states",
            ) {
                StyledListItem(
                    name = "Selected",
                    description = "This item is selected",
                    onClick = {},
                    orientation = ListItemOrientation.Vertical,
                    selected = true,
                )
                StyledListItem(
                    name = "Not selected",
                    description = "This item is not selected",
                    onClick = {},
                    orientation = ListItemOrientation.Vertical,
                    selected = false,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledList(
                title = "Horizontal selection",
            ) {
                StyledListItem(
                    name = "Tap to toggle",
                    onClick = { horizontalSelected = !horizontalSelected },
                    selected = horizontalSelected,
                )
            }
        }
    }
}
