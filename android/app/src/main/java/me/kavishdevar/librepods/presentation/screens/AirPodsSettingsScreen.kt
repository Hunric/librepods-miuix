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

// import me.kavishdevar.librepods.utils.RadareOffsetFinder
import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.AirPodsPro3
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.presentation.MaterialIcons
import me.kavishdevar.librepods.presentation.components.AboutCard
import me.kavishdevar.librepods.presentation.components.AudioSettings
import me.kavishdevar.librepods.presentation.components.BatteryView
import me.kavishdevar.librepods.presentation.components.CallControlSettings
import me.kavishdevar.librepods.presentation.components.HearingHealthSettings
import me.kavishdevar.librepods.presentation.components.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.SettingsSyncStatusCard
import me.kavishdevar.librepods.data.SettingsSyncPolicy
import kotlinx.coroutines.flow.StateFlow
import me.kavishdevar.librepods.presentation.components.PressAndHoldSettings
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.demoState
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Composable
fun AirPodsSettingsRoute(
    viewModel: AirPodsViewModel,
    navigateToRename: () -> Unit,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit,
    navigateToPurchase: () -> Unit,
    navigateToAdaptiveStrength: () -> Unit,
    navigateToEqualizer: () -> Unit,
    navigateToHeadTracking: () -> Unit,
    navigateToAccessibility: () -> Unit,
    navigateToVersion: () -> Unit,
    navigateToTroubleshooting: () -> Unit,
    navigateToCallControlScreen: (action: String) -> Unit,
    navigateToMicrophoneSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Box (
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AirPodsSettingsScreen(
            state = state,
            syncStatus = viewModel.settingsSyncStatus(),

            topPadding = topPadding,
            bottomPadding = bottomPadding,

            setControlCommandInt = viewModel::setControlCommandInt,
            setControlCommandBoolean = viewModel::setControlCommandBoolean,
//        setControlCommandValue = viewModel::setControlCommandValue,
            setControlCommandByte = viewModel::setControlCommandByte,

            setATTCharacteristicValue = viewModel::setATTCharacteristicValue,

            onAutomaticEarDetectionChanged = viewModel::setAutomaticEarDetectionEnabled,
            onAutomaticConnectionChanged = viewModel::setAutomaticConnectionEnabled,
            setDynamicEndOfCharge = viewModel::setDynamicEndOfCharge,
            setOffListeningMode = viewModel::setOffListeningMode,
            disconnect = viewModel::disconnect,

            navigateToRename = navigateToRename,
            navigateToHearingProtection = navigateToHearingProtection,
            navigateToHearingAid = navigateToHearingAid,
            navigateToLeftLongPress = navigateToLeftLongPress,
            navigateToRightLongPress = navigateToRightLongPress,
            navigateToPurchase = navigateToPurchase,
            navigateToAdaptiveStrength = navigateToAdaptiveStrength,
            navigateToEqualizer = navigateToEqualizer,
            navigateToHeadTracking = navigateToHeadTracking,
            navigateToAccessibility = navigateToAccessibility,
            navigateToVersion = navigateToVersion,
            navigateToTroubleshooting = navigateToTroubleshooting,
            navigateToCallControlScreen = navigateToCallControlScreen,
            navigateToMicrophoneSettings = navigateToMicrophoneSettings,

            activateDemoMode = viewModel::activateDemoMode,
            reconnectFromSavedMac = viewModel::reconnectFromSavedMac
        )
    }
}

    @OptIn(ExperimentalHazeMaterialsApi::class)
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AirPodsSettingsScreen(
        state: AirPodsUiState,
        syncStatus: StateFlow<Map<String, SettingsSyncPolicy.ItemSnapshot>>? = null,

        topPadding: Dp = 16.dp,
        bottomPadding: Dp = 16.dp,

        setControlCommandInt: (AACPManager.Companion.ControlCommandIdentifiers, Int) -> Unit,
        setControlCommandBoolean: (AACPManager.Companion.ControlCommandIdentifiers, Boolean) -> Unit,
//        setControlCommandValue: (AACPManager.Companion.ControlCommandIdentifiers, ByteArray) -> Unit,
        setControlCommandByte: (AACPManager.Companion.ControlCommandIdentifiers, Byte) -> Unit,
        setATTCharacteristicValue: (ATTHandles, ByteArray) -> Unit,

        onAutomaticEarDetectionChanged: (Boolean) -> Unit,
        onAutomaticConnectionChanged: (Boolean) -> Unit,
        setDynamicEndOfCharge: (Boolean) -> Unit,
        setOffListeningMode: (Boolean) -> Unit,
        disconnect: () -> Unit,

        navigateToRename: () -> Unit,
        navigateToHearingProtection: () -> Unit,
        navigateToHearingAid: () -> Unit,
        navigateToLeftLongPress: () -> Unit,
        navigateToRightLongPress: () -> Unit,
        navigateToPurchase: () -> Unit,
        navigateToAdaptiveStrength: () -> Unit,
        navigateToEqualizer: () -> Unit,
        navigateToHeadTracking: () -> Unit,
        navigateToAccessibility: () -> Unit,
        navigateToVersion: () -> Unit,
        navigateToTroubleshooting: () -> Unit,
        navigateToCallControlScreen: (action: String) -> Unit,
        navigateToMicrophoneSettings: () -> Unit,

        activateDemoMode: () -> Unit,
        reconnectFromSavedMac: () -> Unit,
) {
    val sharedPreferences = LocalContext.current.getSharedPreferences("settings", MODE_PRIVATE)
    if (state.isLocallyConnected) {
        val capabilities = state.capabilities
        LazyColumn(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp)
        ) {
            item(key = "top_padding") { Spacer(modifier = Modifier.height(topPadding)) }
            item(key = "play_update_banner") {
                if (state.timeUntilFOSSPremiumExpiry > 0L) {
                    val context = LocalContext.current
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = "mailto:".toUri()
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf("billing@kavish.xyz"))
                                    putExtra(Intent.EXTRA_SUBJECT, "LibrePods Play billing error")
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Please enter your GitHub username to restore your premium access:\n\nGitHub username: "
                                    )
                                }
                                context.startActivity(emailIntent)
                            }) {
                        Text(
                            text = stringResource(
                                R.string.play_foss_premium_banner,
                                maxOf(
                                    1,
                                    TimeUnit.MILLISECONDS.toDays(state.timeUntilFOSSPremiumExpiry)
                                        .toInt()
                                )
                            ),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            item(key = "battery") {
                BatteryView(
                    batteryList = state.battery,
                    budsRes = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_buds,
                    caseRes = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case
                )
            }
            item(key = "spacer_battery") {
                Spacer(modifier = Modifier.height(32.dp))
            }

            item(key = "name") {
                StyledListItem(
                    name = stringResource(R.string.name),
                    description = state.deviceName,
                    onClick = navigateToRename,
                )
            }

            val hasHearingAidCapability =
                state.instance?.model?.capabilities?.contains(Capability.HEARING_AID) == true
            val hasPPECapability =
                state.instance?.model?.capabilities?.contains(Capability.PPE) == true

            if (hasHearingAidCapability || hasPPECapability) {
                if (hasPPECapability || state.vendorIdHook) {
                    item(key = "spacer_hearing_health") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                item(key = "hearing_health") {
                    HearingHealthSettings(
                        hasPPECapability = hasPPECapability,
                        hasHearingAidCapability = hasHearingAidCapability,
                        vendorIdHook = state.vendorIdHook,
                        navigateToHearingProtection = navigateToHearingProtection,
                        navigateToHearingAid = navigateToHearingAid
                    )
                }
            }

            if (capabilities.contains(Capability.LISTENING_MODE)) {
                item(key = "spacer_noise") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "listening_mode") {
                    NoiseControlSettings(
                        showOffListeningMode = state.offListeningMode,
                        showAdaptive = state.supportsAdaptiveAudio,
                        noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(
                            0
                        )?.toInt(),
                        onNoiseControlModeChanged = {
                            setControlCommandInt(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE, it
                            )
                        },
                    )
                }
            }

            if (capabilities.contains(Capability.STEM_CONFIG)) {
                item(key = "spacer_press_hold") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "press_hold") {
                    PressAndHoldSettings(
                        leftAction = state.leftAction,
                        rightAction = state.rightAction,
                        navigateToLeftLongPress = navigateToLeftLongPress,
                        navigateToRightLongPress = navigateToRightLongPress
                    )
                }
            }

            item(key = "spacer_call") {
                Spacer(modifier = Modifier.height(16.dp))
            }
            item(key = "call_control") {
                val bytes =
                    state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(
                        2
                    )?.toByteArray() ?: byteArrayOf(0x00, 0x00)
                val flipped = try {
                    bytes[1] == 0x02.toByte()
                } catch (_: Exception) {
                    false
                }
                // The component keeps a remembered label; recreate it when the saved selection changes.
                key(flipped) {
                    CallControlSettings(
                        flipped = flipped,
                        navigateToCallControlScreen = navigateToCallControlScreen
                    )
                }
            }

//                if (capabilities.contains(Capability.STEM_CONFIG) && !BuildConfig.PLAY_BUILD) {
//                    item(key = "spacer_camera") { Spacer(modifier = Modifier.height(16.dp)) }
//                    item(key = "camera_control") {
//                        StyledListItem(
//                            to = "camera_control",
//                            name = stringResource(R.string.camera_remote),
//                            descriptionRes = stringResource(R.string.camera_control_description),
//                            titleRes = stringResource(R.string.camera_control),
//                            navController = navController
//                        )
//                    }
//                }

            item(key = "upgrade_button") {
                if (!state.isPremium) {
                    Spacer(modifier = Modifier.height(28.dp))
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
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item(key = "spacer_audio") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "audio") {
                val model = state.instance?.model ?: AirPodsPro3()
                val adaptiveVolumeCapability =
                    model.capabilities.contains(Capability.ADAPTIVE_VOLUME)
                val conversationAwarenessCapability =
                    model.capabilities.contains(Capability.CONVERSATION_AWARENESS)
                val loudSoundReductionCapability =
                    model.capabilities.contains(Capability.LOUD_SOUND_REDUCTION)
                val adaptiveAudioCapability = state.supportsAdaptiveAudio

                val adaptiveVolumeChecked =
                    state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]
                        ?.firstOrNull()?.toInt()?.takeIf { it in 1..2 }?.let { it == 1 }
                val conversationAwarenessChecked =
                    state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]
                        ?.firstOrNull()?.toInt()?.takeIf { it in 1..2 }?.let { it == 1 }

                AudioSettings(
                    adaptiveVolumeCapability = adaptiveVolumeCapability,
                    conversationAwarenessCapability = conversationAwarenessCapability,
                    loudSoundReductionCapability = loudSoundReductionCapability,
                    adaptiveAudioCapability = adaptiveAudioCapability,
                    customEqCapability = true,
                    adaptiveVolumeChecked = adaptiveVolumeChecked,
                    onAdaptiveVolumeCheckedChange = { checked ->
                        setControlCommandBoolean(
                            AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
                            checked
                        )
                    },
                    conversationAwarenessChecked = conversationAwarenessChecked,
                    onConversationAwarenessCheckedChange = { checked ->
                        setControlCommandBoolean(
                            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                            checked
                        )
                    },
                    loudSoundReductionChecked = state.loudSoundReductionEnabled.takeIf { state.loudSoundReductionAvailable },
                    onLoudSoundReductionCheckedChange = { checked ->
                        setATTCharacteristicValue(
                            ATTHandles.LOUD_SOUND_REDUCTION,
                            byteArrayOf(if (checked) 0x01.toByte() else 0x00.toByte())
                        )
                    },
                    navigateToAdaptiveStrength = navigateToAdaptiveStrength,
                    navigateToEqualizer = navigateToEqualizer,
                    vendorIdHook = state.vendorIdHook,
                    isPremium = state.isPremium
                )
            }

            item(key = "spacer_connection") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "connection") {
                StyledList {
                    StyledToggle(
                        label = stringResource(R.string.ear_detection),
                        checked = state.automaticEarDetectionEnabled,
                        onCheckedChange = onAutomaticEarDetectionChanged
                    )
                    StyledToggle(
                        label = stringResource(R.string.automatically_connect),
                        description = stringResource(R.string.automatically_connect_description),
                        checked = state.automaticConnectionEnabled,
                        onCheckedChange = onAutomaticConnectionChanged
                    )
                }
            }

            item(key = "spacer_microphone") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "microphone") {
                val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE

                val selectedModeText = when (state.controlStates[id]?.firstOrNull()) {
                    0x00.toByte() -> stringResource(R.string.microphone_automatic)
                    0x01.toByte() -> stringResource(R.string.microphone_always_right)
                    0x02.toByte() -> stringResource(R.string.microphone_always_left)
                    else -> stringResource(R.string.microphone_automatic)
                }

                StyledListItem(
                    name = stringResource(R.string.microphone_mode),
                    description = selectedModeText,
                    onClick = navigateToMicrophoneSettings
                )
            }

            if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                item(key = "spacer_sleep") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "sleep_detection") {
                    val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                    StyledToggle(
                        label = stringResource(R.string.sleep_detection),
                        checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                        onCheckedChange = { setControlCommandBoolean(id, it) },
                        enabled = state.isPremium
                    )
                }
            }

            if (capabilities.contains(Capability.HEAD_GESTURES)) {
                item(key = "spacer_head_tracking") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "head_tracking") {
                    StyledListItem(
                        name = stringResource(R.string.head_gestures),
                        description = if (sharedPreferences.getBoolean(
                                "head_gestures", false
                            )
                        ) stringResource(R.string.on) else stringResource(R.string.off),
                        onClick = navigateToHeadTracking
                    )
                }
            }

            item(key = "spacer_dynamic_end_of_charge") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "dynamic_end_of_charge") {
                StyledToggle(
                    label = stringResource(R.string.optimized_charging),
                    description = stringResource(R.string.optimized_charging_description),
                    checked = state.dynamicEndOfCharge,
                    onCheckedChange = setDynamicEndOfCharge
                )
            }

            item(key = "spacer_accessibility") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "accessibility") {
                StyledListItem(
                    name = stringResource(R.string.accessibility), onClick = navigateToAccessibility
                )
            }

            if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION) ||
                (BuildConfig.SETTINGS_VERIFICATION_CONTROLS && capabilities.contains(Capability.LISTENING_MODE))
            ) {
                item(key = "spacer_off_listening") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "off_listening") {
                    StyledToggle(
                        label = stringResource(R.string.off_listening_mode),
                        description = stringResource(
                            if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                                R.string.off_listening_mode_description
                            } else {
                                R.string.off_listening_mode_verification_description
                            }
                        ),
                        checked = state.offListeningMode,
                        onCheckedChange = setOffListeningMode
                    )
                }
            }

            if (syncStatus != null && BuildConfig.SHOW_SETTINGS_SYNC) {
                item(key = "sync_status") {
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSyncStatusCard(syncStatus)
                }
            }
            item(key = "spacer_about") { Spacer(modifier = Modifier.height(32.dp)) }
            item(key = "about") {
                AboutCard(
                    modelName = state.modelName,
                    actualModel = state.actualModel,
                    serialNumbers = state.serialNumbers,
                    version = state.version3,
                    navigateToVersion = navigateToVersion
                )
            }

            item(key = "spacer_disconnect") { Spacer(modifier = Modifier.height(28.dp)) }
            item(key = "disconnect_button") {
                StyledButton(
                    onClick = disconnect,
                    backdrop = rememberLayerBackdrop(),
                    isInteractive = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.disconnect),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

//                item(key = "spacer_debug") { Spacer(modifier = Modifier.height(16.dp)) }
//                item(key = "debug") { StyledListItem("debug", "Debug", navController) }

            item(key = "bottom_padding") { Spacer(modifier = Modifier.height(bottomPadding)) }
        }
    } else {
        val backdrop = rememberLayerBackdrop()
        Box(
            modifier = Modifier
                .drawBackdrop(
                    backdrop = rememberLayerBackdrop(),
                    exportedBackdrop = backdrop,
                    shape = { RoundedCornerShape(0.dp) },
                    highlight = {
                        Highlight.Ambient.copy(alpha = 0f)
                    },
                    effects = {}
                )
                .fillMaxSize()
                .padding(start = 8.dp, end = 8.dp, bottom = bottomPadding),
            contentAlignment = Alignment.Center
        ) {
            val tapCount = remember { mutableIntStateOf(0) }
            val lastTapTime = remember { mutableLongStateOf(0L) }

            var reconnecting by remember { mutableStateOf(false) }

            LaunchedEffect(reconnecting) {
                if (reconnecting) {
                    delay(5.seconds)
                    reconnecting = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime.longValue > 400) tapCount.intValue = 0
                            tapCount.intValue++
                            lastTapTime.longValue = now
                            if (tapCount.intValue >= 5) {
                                tapCount.intValue = 0
                                activateDemoMode()
                            }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (reconnecting) MaterialIcons.bluetooth_searching else MaterialIcons.headset_off,
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = if (reconnecting) stringResource(R.string.reconnecting) else stringResource(R.string.airpods_not_connected),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    if (!state.connectionSuccessful) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.airpods_not_connected_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        if (sharedPreferences.getBoolean("bypass_device_check.v2", false)) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.compatibility_check_bypassed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    if (state.connectionSuccessful) {
                        Spacer(Modifier.height(20.dp))
                        StyledButton(
                            onClick = {
                                reconnecting = true
                                reconnectFromSavedMac()
                            },
                            backdrop = rememberLayerBackdrop(),
                            enabled = !reconnecting
                        ) {
                            Text(stringResource(R.string.reconnect_to_last_device))
                        }
                    }
                }
                if (!BuildConfig.PLAY_BUILD) {
                    StyledButton(
                        onClick = navigateToTroubleshooting,
                        backdrop = rememberLayerBackdrop(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp)
                    ) {
                        Text(stringResource(R.string.troubleshooting))
                    }
                }
            }
        }
    }
}

@Preview(name = "Miuix connected")
@Composable
fun AirPodsSettingsScreenPreviewMiuixConnected() {
    LibrePodsTheme {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            AirPodsSettingsScreen(
                state = demoState,

                setControlCommandInt = { _, _ -> },
                setControlCommandBoolean = { _, _ -> },
                setControlCommandByte = { _, _ -> },
                setATTCharacteristicValue = { _, _ -> },

                onAutomaticEarDetectionChanged = {},
                onAutomaticConnectionChanged = {},
                setDynamicEndOfCharge = {},
                setOffListeningMode = {},
                disconnect = {},

                navigateToRename = {},
                navigateToHearingProtection = {},
                navigateToHearingAid = {},
                navigateToLeftLongPress = {},
                navigateToRightLongPress = {},
                navigateToPurchase = {},
                navigateToAdaptiveStrength = {},
                navigateToEqualizer = {},
                navigateToHeadTracking = {},
                navigateToAccessibility = {},
                navigateToVersion = {},
                navigateToTroubleshooting = {},
                navigateToCallControlScreen = {},
                navigateToMicrophoneSettings = {},

                activateDemoMode = {},
                reconnectFromSavedMac = {}
            )
        }
    }
}


@Preview(name = "Miuix disconnected")
@Composable
fun AirPodsSettingsScreenPreviewDisconnected() {
    LibrePodsTheme {
        Box (
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            AirPodsSettingsScreen(
                state = demoState.copy(isLocallyConnected = false, connectionSuccessful = false),

                setControlCommandInt = { _, _ -> },
                setControlCommandBoolean = { _, _ -> },
                setControlCommandByte = { _, _ -> },
                setATTCharacteristicValue = { _, _ -> },

                onAutomaticEarDetectionChanged = {},
                onAutomaticConnectionChanged = {},
                setDynamicEndOfCharge = {},
                setOffListeningMode = {},
                disconnect = {},

                navigateToRename = {},
                navigateToHearingProtection = {},
                navigateToHearingAid = {},
                navigateToLeftLongPress = {},
                navigateToRightLongPress = {},
                navigateToPurchase = {},
                navigateToAdaptiveStrength = {},
                navigateToEqualizer = {},
                navigateToHeadTracking = {},
                navigateToAccessibility = {},
                navigateToVersion = {},
                navigateToTroubleshooting = {},
                navigateToCallControlScreen = {},
                navigateToMicrophoneSettings = {},

                activateDemoMode = {},
                reconnectFromSavedMac = {}
            )
        }
    }
}
