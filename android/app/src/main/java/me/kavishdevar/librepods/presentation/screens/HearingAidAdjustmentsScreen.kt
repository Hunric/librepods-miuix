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

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "HearingAidAdjustments"

@SuppressLint("DefaultLocale")
@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun HearingAidAdjustmentsScreen(viewModel: AirPodsViewModel) {
    val verticalScrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()

    val sessionToken = state.nameConnectionToken
    val sessionGeneration = state.controlServiceGeneration
    val draft = remember(sessionToken, sessionGeneration) { mutableStateOf(state.hearingAidSettings) }
    var pending by remember(sessionToken, sessionGeneration) { mutableStateOf(false) }
    var debounceJob by remember(sessionToken, sessionGeneration) { mutableStateOf<Job?>(null) }
    val saveScope = rememberCoroutineScope()
    fun flushDraft() {
        debounceJob?.cancel()
        debounceJob = null
        if (!pending) return
        pending = false
        val current = viewModel.uiState.value
        if (current.nameConnectionToken == sessionToken && current.controlServiceGeneration == sessionGeneration && current.isLocallyConnected) {
            viewModel.setCompositeAttSettings(draft.value, sessionToken)
        }
    }
    LaunchedEffect(state.hearingAidSettings, pending) { if (!pending) draft.value = state.hearingAidSettings }
    DisposableEffect(sessionToken, sessionGeneration) { onDispose { flushDraft() } }
    fun editSettings(change: (me.kavishdevar.librepods.data.CompositeAttSettings) -> me.kavishdevar.librepods.data.CompositeAttSettings) {
        val current = viewModel.uiState.value
        if (current.nameConnectionToken != sessionToken || current.controlServiceGeneration != sessionGeneration || !current.isLocallyConnected) return
        val updated = change(draft.value)
        if (updated == draft.value) return
        draft.value = updated
        pending = true
        debounceJob?.cancel()
        debounceJob = saveScope.launch { delay(150); flushDraft() }
    }
    val settings = draft.value
    val amplificationLimit = 1f - kotlin.math.abs(settings.balance) / 2f
    val balanceLimit = minOf(1f, 2f * (1f - kotlin.math.abs(settings.amplification))).coerceAtLeast(0f)

    val isMiuix = LocalDesignSystem.current == DesignSystem.Miuix
    val topPadding = if (isMiuix) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (isMiuix) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(verticalScrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        run {
            StyledSlider(
                label = stringResource(R.string.amplification),
                valueRange = -amplificationLimit..amplificationLimit,
                value = settings.amplification,
                onValueChange = { value -> editSettings { it.copy(amplification = value.coerceIn(-amplificationLimit, amplificationLimit)) } },
                startIcon = "􀊥",
                endIcon = "􀊩",
                independent = true,
                onValueChangeFinished = { flushDraft() },
            )

            StyledToggle(
                label = stringResource(R.string.swipe_to_control_amplification),
                checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.HPS_GAIN_SWIPE]?.getOrNull(0) == 0x01.toByte(),
                onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.HPS_GAIN_SWIPE, it) },
                description = stringResource(R.string.swipe_amplification_description)
            )

            StyledSlider(
                label = stringResource(R.string.balance),
                valueRange = if (balanceLimit > 0f) -balanceLimit..balanceLimit else -1f..1f,
                enabled = balanceLimit > 0f,
                value = settings.balance,
                onValueChange = { value -> editSettings { it.copy(balance = value.coerceIn(-balanceLimit, balanceLimit)) } },
                snapPoints = listOf(-1f, 0f, 1f),
                startLabel = stringResource(R.string.left),
                endLabel = stringResource(R.string.right),
                independent = true,
                onValueChangeFinished = { flushDraft() },
            )

            StyledSlider(
                label = stringResource(R.string.tone),
                valueRange = -1f..1f,
                value = settings.tone,
                onValueChange = { value -> editSettings { it.copy(tone = value) } },
                startLabel = stringResource(R.string.darker),
                endLabel = stringResource(R.string.brighter),
                independent = true,
                onValueChangeFinished = { flushDraft() },
            )

            StyledSlider(
                label = stringResource(R.string.ambient_noise_reduction),
                valueRange = 0f..1f,
                value = settings.ambientNoiseReduction,
                onValueChange = { value -> editSettings { it.copy(ambientNoiseReduction = value) } },
                startLabel = stringResource(R.string.less),
                endLabel = stringResource(R.string.more),
                independent = true,
                onValueChangeFinished = { flushDraft() },
            )

            StyledToggle(
                label = stringResource(R.string.conversation_boost),
                checked = settings.conversationBoost,
                onCheckedChange = { value -> editSettings { it.copy(conversationBoost = value) } },
                description = stringResource(R.string.conversation_boost_description)
            )

        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
