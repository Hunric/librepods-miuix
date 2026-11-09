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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.presentation.components.StyledInputField
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi


@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun RenameScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val token = state.nameConnectionToken
    val generation = state.controlServiceGeneration
    val textFieldState = remember(token, generation) { TextFieldState(initialText = state.deviceName) }
    var displayedName by remember(token, generation) { mutableStateOf(state.deviceName) }
    var userEdited by remember(token, generation) { mutableStateOf(false) }

    LaunchedEffect(state.deviceName, token, generation) {
        if (!userEdited) {
            displayedName = state.deviceName
            textFieldState.setTextAndPlaceCursorAtEnd(state.deviceName)
        }
    }
    fun saveNameIfCurrent(text: String) {
        val current = viewModel.uiState.value
        if (!userEdited || text.isBlank() || token == null || !current.isLocallyConnected ||
            current.nameConnectionToken != token || current.controlServiceGeneration != generation) return
        if (text != current.deviceName && viewModel.setName(text, token)) {
            displayedName = text
            userEdited = false
        }
    }
    LaunchedEffect(textFieldState, token, generation) {
        snapshotFlow { textFieldState.text.toString() }.collectLatest { text ->
            if (text == displayedName) return@collectLatest
            userEdited = true
            if (text.isBlank() || token == null) return@collectLatest
            delay(600)
            saveNameIfCurrent(text)
        }
    }
    DisposableEffect(token, generation) {
        onDispose { saveNameIfCurrent(textFieldState.text.toString()) }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val isMiuix = LocalDesignSystem.current == DesignSystem.Miuix
    val topPadding = if (isMiuix) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (isMiuix) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledInputField(
            textFieldState,
            focusRequester
        )

        Spacer(modifier = Modifier.height(bottomPadding))

    }
}
