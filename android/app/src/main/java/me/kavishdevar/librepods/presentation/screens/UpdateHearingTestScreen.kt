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

package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import top.yukonga.miuix.kmp.basic.TextField
import me.kavishdevar.librepods.presentation.viewmodel.demoState

private const val TAG = "UpdateHearingTestScreen"

@Composable
fun UpdateHearingTestRoute(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val isMiuix = LocalDesignSystem.current == DesignSystem.Miuix
    val topPadding = if (isMiuix) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (isMiuix) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        UpdateHearingTestScreen(
            state = state,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            setHearingValue = { left, index, value, token, generation ->
                val current = viewModel.uiState.value
                if (current.nameConnectionToken == token && current.controlServiceGeneration == generation) {
                    val settings = current.hearingAidSettings
                    val values = (if (left) settings.leftHearingLoss else settings.rightHearingLoss)
                        .toMutableList().also { it[index] = value }
                    viewModel.setCompositeAttSettings(
                        if (left) settings.copy(leftHearingLoss = values) else settings.copy(rightHearingLoss = values), token
                    )
                }
            }
        )
    }
}

@Composable
fun UpdateHearingTestScreen(
    state: AirPodsUiState,
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 16.dp,
    setHearingValue: (Boolean, Int, Float?, Long?, Long) -> Unit
) {
    val verticalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .verticalScroll(verticalScrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Text(
            text = stringResource(R.string.hearing_test_value_instruction),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        // A null entry is not app-owned. Keep it empty and label it as unset; never
        // initialize it from the device or turn it into a synthetic zero.
        val leftDraft = remember(state.nameConnectionToken, state.controlServiceGeneration) {
            mutableStateListOf(*state.hearingAidSettings.leftHearingLoss.map { it?.toString() ?: "" }.toTypedArray())
        }
        val rightDraft = remember(state.nameConnectionToken, state.controlServiceGeneration) {
            mutableStateListOf(*state.hearingAidSettings.rightHearingLoss.map { it?.toString() ?: "" }.toTypedArray())
        }
        val token = state.nameConnectionToken
        val generation = state.controlServiceGeneration
        val frequencies = listOf("250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(modifier = Modifier.width(60.dp))
            Text(stringResource(R.string.left), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMediumEmphasized)
            Text(stringResource(R.string.right), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMediumEmphasized)
        }
        frequencies.forEachIndexed { index, frequency ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(frequency, modifier = Modifier.width(60.dp).align(Alignment.CenterVertically), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall)
                listOf(true, false).forEach { left ->
                    val drafts = if (left) leftDraft else rightDraft
                    val ownedValue = if (left) state.hearingAidSettings.leftHearingLoss[index] else state.hearingAidSettings.rightHearingLoss[index]
                    Column(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = drafts[index],
                            onValueChange = { text ->
                                drafts[index] = text
                                val parsed = text.toFloatOrNull()?.takeIf { it.isFinite() }
                                if (text.isBlank() || parsed != null) setHearingValue(left, index, parsed, token, generation)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (ownedValue == null && drafts[index].isBlank()) {
                            Text(stringResource(R.string.hearing_value_unset), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Preview(name = "Miuix")
@Composable
fun UpdateHearingTestScreenPreviewMiuix() {
    LibrePodsTheme() {
        Box (
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                state = demoState,
                setHearingValue = { _, _, _, _, _ -> }
            )
        }
    }
}

@Preview(name = "Miuix sample")
@Composable
fun UpdateHearingTestScreenPreviewMiuixSample() {
    LibrePodsTheme() {
        Box (
            modifier = Modifier
                .wrapContentHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                state = demoState,
                setHearingValue = { _, _, _, _, _ -> }
            )
        }
    }
}

