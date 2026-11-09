package me.kavishdevar.librepods.presentation.navigation

import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.MaterialIcons
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.screens.StartupScreen
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.ConnectionTransitionGate
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class NavigationUiState(
    val isLocallyConnected: Boolean,
    val hasCurrentSessionInformation: Boolean,
    val deviceName: String,
    val headTrackingActive: Boolean,
)

private fun AirPodsUiState.toNavigationUiState() = NavigationUiState(
    isLocallyConnected = isLocallyConnected,
    hasCurrentSessionInformation = hasCurrentSessionInformation,
    deviceName = deviceName,
    headTrackingActive = headTrackingActive,
)

@Composable
fun NavigationRoot(
    showReleaseNotes: Boolean = false,
    updatesShown: () -> Unit = {},
    showOnboarding: Boolean = false,
    onboardingComplete: () -> Unit = {},
    airPodsViewModel: AirPodsViewModel
) {
    val backStack = remember {
        mutableStateListOf(
            when {
                showOnboarding -> Screen.Onboarding
                showReleaseNotes -> Screen.ReleaseNotes
                else -> Screen.AirPodsSettings
            }
        )
    }

    val currentScreen = backStack.last()

    val navigationStateFlow = remember(airPodsViewModel) {
        airPodsViewModel.uiState
            .map { it.toNavigationUiState() }
            .distinctUntilChanged()
    }
    val state by navigationStateFlow.collectAsState(
        initial = airPodsViewModel.uiState.value.toNavigationUiState()
    )

    if (ConnectionTransitionGate.shouldShowInterstitial(
            atRoot = currentScreen == Screen.AirPodsSettings,
            locallyConnected = state.isLocallyConnected,
            informationReady = state.hasCurrentSessionInformation,
        )
    ) {
        StartupScreen()
        return
    }

    val title = when (currentScreen) {
        Screen.Onboarding -> ""
        Screen.AirPodsSettings -> if (state.isLocallyConnected) state.deviceName else stringResource(R.string.app_name)
        Screen.Accessibility -> stringResource(R.string.accessibility)
        Screen.AdaptiveStrength -> stringResource(R.string.customize_adaptive_audio)
        Screen.AppSettings -> stringResource(R.string.settings)
//        Screen.CameraControl -> stringResource(R.string.camera_control)
        Screen.Equalizer -> stringResource(R.string.equalizer)
        Screen.HeadTracking -> stringResource(R.string.head_tracking)
        Screen.HearingAid -> stringResource(R.string.hearing_aid)
        Screen.HearingAidAdjustments -> stringResource(R.string.adjustments)
        Screen.HearingProtection -> stringResource(R.string.hearing_protection)
        is Screen.LongPress -> currentScreen.bud
        Screen.OpenSourceLicenses -> stringResource(R.string.open_source_licenses)
        Screen.Purchase -> stringResource(R.string.unlock_advanced_features)
        Screen.Rename -> stringResource(R.string.name)
        Screen.TransparencyCustomization -> stringResource(R.string.customize_transparency_mode)
        Screen.Troubleshooting -> stringResource(R.string.troubleshooting)
        Screen.UpdateHearingTest -> stringResource(R.string.update_hearing_test)
        Screen.VersionInfo -> stringResource(R.string.version)
        is Screen.CallControl -> currentScreen.action
        Screen.MicrophoneSettings -> stringResource(R.string.microphone_mode)
        Screen.ReleaseNotes -> ""
    }

    // is this a bad idea? probably. I can't think of a better way without having to pass around a shouldShowBackButton to each screen to pass to each scaffold
    val actionButtons = when (currentScreen) {
        Screen.AirPodsSettings -> listOf<@Composable (backdrop: LayerBackdrop) -> Unit>(
            {
                MiuixIconButton(
                    onClick = { backStack.add(Screen.AppSettings) },
                    modifier = Modifier.size(48.dp),
                    backgroundColor = MiuixTheme.colorScheme.secondaryVariant
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSecondaryVariant
                    )
                }
            }
        )
        Screen.HeadTracking -> listOf<@Composable (backdrop: LayerBackdrop) -> Unit>(
            {
                MiuixIconButton(
                    onClick = {
                        if (state.headTrackingActive) {
                            airPodsViewModel.stopHeadTracking()
                            Log.d("HeadTrackingScreen", "Head tracking stopped")
                        } else {
                            airPodsViewModel.startHeadTracking()
                            Log.d("HeadTrackingScreen", "Head tracking started")
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    backgroundColor = if (state.headTrackingActive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondaryVariant
                ) {
                    Icon(
                        imageVector = if (state.headTrackingActive) MaterialIcons.pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.headTrackingActive) "Pause head tracking" else "Start head tracking",
                        modifier = Modifier.size(20.dp),
                        tint = if (state.headTrackingActive) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondaryVariant
                    )
                }
            }
        )
        else -> listOf()
    }

    StyledScaffold(
        visible = currentScreen.showTopBar,
        title = title,
        showBackButton = backStack.size > 1,
        onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
        actionButtons = actionButtons
    ) {
        AppNavGraph(
            showReleaseNotes = showReleaseNotes,
            updatesShown = updatesShown,
            showOnboarding = showOnboarding,
            onboardingComplete = onboardingComplete,
            backStack = backStack,
            airPodsViewModel = airPodsViewModel,
        )
    }
}
