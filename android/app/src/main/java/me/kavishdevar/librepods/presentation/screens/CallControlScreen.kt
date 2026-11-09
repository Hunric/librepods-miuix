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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallControlScreen(viewModel: AirPodsViewModel, action: String, onCallControlValueChanged: (Boolean) -> Unit) {
    val state by viewModel.uiState.collectAsState()

    val isMiuix = LocalDesignSystem.current == DesignSystem.Miuix
    val topPadding = if (isMiuix) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (isMiuix) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    val scrollState = rememberScrollState()

    val callConfig = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]
    val flipped = callConfig?.takeIf {
        it.size >= 2 && it[0] == 0x00.toByte() && it[1] in listOf(0x02.toByte(), 0x03.toByte())
    }?.let { it[1] == 0x02.toByte() }

    val pressOnceText = stringResource(R.string.press_once)
    val pressTwiceText = stringResource(R.string.press_twice)
    val muteUnmuteText = stringResource(R.string.mute_unmute)

    val pressOnceIsAction = flipped?.let { (action == muteUnmuteText) == !it } ?: false
    val flippedValue = action != muteUnmuteText

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(scrollState)
            .padding(top = 8.dp)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledList {
            StyledListItem(
                name = pressOnceText,
                selected = flipped != null && pressOnceIsAction,
                enabled = flipped != null,
                onClick = {
                    onCallControlValueChanged(flippedValue)
                }
            )

            StyledListItem(
                name = pressTwiceText,
                selected = flipped != null && !pressOnceIsAction,
                enabled = flipped != null,
                onClick = {
                    onCallControlValueChanged(!flippedValue)
                }
            )
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
