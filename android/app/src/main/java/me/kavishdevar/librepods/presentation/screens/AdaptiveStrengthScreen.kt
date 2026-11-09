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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun AdaptiveStrengthScreen(viewModel: AirPodsViewModel, navigateToPurchase: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val backdrop = rememberLayerBackdrop()

    val isMiuix = LocalDesignSystem.current == DesignSystem.Miuix
    val topPadding = if (isMiuix) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (isMiuix) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))
        if (!state.isPremium) {
            StyledButton(
                onClick = navigateToPurchase,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f,
                surfaceColor = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    stringResource(R.string.unlock_advanced_features),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        val localStrength = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH]
            ?.getOrNull(0)?.toInt()?.takeIf { it in 0..100 }?.let { 100f - it } ?: 50f
        val sliderValue = remember(state.nameConnectionToken, state.controlServiceGeneration) { mutableFloatStateOf(localStrength) }
        var sliderDragging by remember(state.nameConnectionToken, state.controlServiceGeneration) { mutableStateOf(false) }
        var sliderPending by remember(state.nameConnectionToken, state.controlServiceGeneration) { mutableStateOf(false) }
        var job by remember(state.nameConnectionToken, state.controlServiceGeneration) { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val token = state.nameConnectionToken
        val generation = state.controlServiceGeneration
        fun flushSlider() {
            job?.cancel()
            job = null
            if (!sliderPending) return
            sliderPending = false
            val current = viewModel.uiState.value
            if (current.nameConnectionToken == token && current.controlServiceGeneration == generation && current.isLocallyConnected) {
                viewModel.setControlCommandValue(
                    AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
                    byteArrayOf((100 - sliderValue.floatValue).toInt().toByte()), token
                )
            }
        }
        DisposableEffect(token, generation) { onDispose { flushSlider() } }
        LaunchedEffect(localStrength, sliderDragging, sliderPending, token, generation) {
            if (!sliderDragging && !sliderPending) sliderValue.floatValue = localStrength
        }
        run {
            Box(
                modifier = Modifier.fillMaxWidth().pointerInput(state.nameConnectionToken) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        sliderDragging = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            sliderDragging = false
                        }
                    }
                }
            ) {
                StyledSlider(
                    label = stringResource(R.string.customize_adaptive_audio),
                    value = sliderValue.floatValue,
                    onValueChange = {
                        sliderValue.floatValue = it
                        sliderPending = true
                        job?.cancel()
                        job = scope.launch { delay(150); flushSlider() }
                    },
                    valueRange = 0f..100f,
                    snapPoints = listOf(0f, 50f, 100f),
                    startIcon = "􀊥",
                    endIcon = "􀊩",
                    independent = true,
                    description = stringResource(R.string.adaptive_audio_description),
                    onValueChangeFinished = { flushSlider() },
                    enabled = state.isPremium
                )
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
