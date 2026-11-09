package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.SettingsSyncPolicy
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.parseCompositeAttSettings
import me.kavishdevar.librepods.data.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.data.parseTransparencySettingsResponse
import java.util.Locale
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Progress changes recompose this component, not the main settings state. */
@Composable
fun SettingsSyncStatusCard(statusFlow: StateFlow<Map<String, SettingsSyncPolicy.ItemSnapshot>>) {
    val status by statusFlow.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    val confirmed = status.values.count { it.phase == SettingsSyncPolicy.Phase.CONFIRMED }
    val sentUnverified = status.values.count { it.phase == SettingsSyncPolicy.Phase.SENT_UNVERIFIED }
    val failed = status.values.count { it.phase == SettingsSyncPolicy.Phase.FAILED }
    val pending = status.size - confirmed - sentUnverified - failed
    StyledListItem(
        name = stringResource(R.string.settings_sync_title),
        description = if (status.isEmpty()) stringResource(R.string.settings_sync_preparing)
            else stringResource(R.string.settings_sync_summary, confirmed, sentUnverified, pending, failed),
        onClick = { showDetails = true }
    )
    WindowDialog(
        show = showDetails,
        title = stringResource(R.string.settings_sync_title),
        onDismissRequest = { showDetails = false }
    ) {
        Column {
            Text(stringResource(R.string.settings_sync_explanation), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status.isEmpty()) Text(stringResource(R.string.settings_sync_preparing))
                status.toSortedMap().forEach { (key, item) ->
                    Column {
                        Text(syncItemLabel(key), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.settings_sync_local_value, syncValue(key, item.desired, false)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.settings_sync_actual_value, item.actual?.let { syncValue(key, it, true) }
                            ?: stringResource(R.string.settings_sync_not_observed)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.settings_sync_attempts, phaseLabel(item), item.attempts), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                MiuixTextButton(text = stringResource(R.string.settings_sync_close), onClick = { showDetails = false })
            }
        }
    }
}

@Composable
private fun failureLabel(failure: SettingsSyncPolicy.Failure?): String = stringResource(when (failure) {
    SettingsSyncPolicy.Failure.TRANSPORT_FAILURE -> R.string.settings_sync_transport_failed
    SettingsSyncPolicy.Failure.DEVICE_REJECTED -> R.string.settings_sync_rejected
    SettingsSyncPolicy.Failure.MISMATCH -> R.string.settings_sync_mismatch
    SettingsSyncPolicy.Failure.UNCONFIRMED, null -> R.string.settings_sync_unconfirmed
})

@Composable
private fun phaseLabel(item: SettingsSyncPolicy.ItemSnapshot): String = when (item.phase) {
    SettingsSyncPolicy.Phase.PREPARING -> stringResource(R.string.settings_sync_preparing)
    SettingsSyncPolicy.Phase.PENDING, SettingsSyncPolicy.Phase.RESERVED, SettingsSyncPolicy.Phase.SENDING -> stringResource(R.string.settings_sync_sending)
    SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION -> stringResource(R.string.settings_sync_waiting_confirmation)
    SettingsSyncPolicy.Phase.WAITING_FOR_WEAR -> stringResource(R.string.settings_sync_waiting_for_wear)
    SettingsSyncPolicy.Phase.BACKOFF -> stringResource(R.string.settings_sync_retrying) + " · " + failureLabel(item.failure)
    SettingsSyncPolicy.Phase.CONFIRMED -> stringResource(R.string.settings_sync_observed_consistent)
    SettingsSyncPolicy.Phase.SENT_UNVERIFIED -> stringResource(R.string.settings_sync_sent_unverified)
    SettingsSyncPolicy.Phase.FAILED -> failureLabel(item.failure)
}

@Composable
private fun syncItemLabel(key: String): String {
    val resource = when (key) {
        "name" -> R.string.settings_sync_device_name
        "eq" -> R.string.equalizer
        "att:1b" -> R.string.loud_sound_reduction
        "att:18" -> R.string.customize_transparency_mode
        "att:2a" -> R.string.hearing_aid
        "aacp:01" -> R.string.microphone_mode
        "aacp:0a" -> R.string.ear_detection
        "aacp:0d" -> R.string.listening_mode
        "aacp:17" -> R.string.press_speed
        "aacp:18" -> R.string.press_and_hold_duration
        "aacp:1a" -> R.string.settings_sync_cycle_modes
        "aacp:1b" -> R.string.noise_cancellation_single_airpod
        "aacp:1f" -> R.string.tone_volume
        "aacp:20" -> R.string.automatically_connect
        "aacp:23" -> R.string.volume_swipe_speed
        "aacp:24" -> R.string.call_controls
        "aacp:25" -> R.string.volume_control
        "aacp:26" -> R.string.personalized_volume
        "aacp:28" -> R.string.conversation_awareness
        "aacp:2c" -> R.string.settings_sync_hearing_aid_state
        "aacp:33" -> R.string.settings_sync_hearing_assist
        "aacp:2e" -> R.string.adaptive_audio
        "aacp:2f" -> R.string.swipe_to_control_amplification
        "aacp:34" -> R.string.off_listening_mode
        "aacp:35" -> R.string.sleep_detection
        "aacp:37" -> R.string.ppe
        "aacp:3b" -> R.string.optimized_charging
        else -> null
    }
    return if (resource != null) stringResource(resource) else stringResource(R.string.settings_sync_other_item, key)
}

@Composable
private fun syncValue(key: String, value: ByteArray, actual: Boolean): String {
    if (key == "name") return value.toString(Charsets.UTF_8)
    // Decode ordinary owned settings, but never expose device-owned hearing measurements.
    if (key == "att:18" || key == "att:2a") return compositeValue(key, value, actual)
    if (key == "eq" && value.size >= 4) {
        if ((value[0].toInt() and 0xff) !in 1..2 || value.sliceArray(1..3).any { (it.toInt() and 0xff) !in 0..100 })
            return stringResource(R.string.settings_sync_value_unavailable)
        val mode = when (value[0].toInt() and 0xff) {
            1 -> stringResource(R.string.recommended)
            2 -> stringResource(R.string.custom)
            else -> stringResource(R.string.settings_sync_value_unavailable)
        }
        return stringResource(R.string.settings_sync_eq_value, mode,
            value[1].toInt() and 0xff, value[2].toInt() and 0xff, value[3].toInt() and 0xff)
    }
    val first = value.firstOrNull()?.toInt()?.and(0xff)
    if (key == "att:1b" && first != null && first in 0..1) return stringResource(if (first == 1) R.string.on else R.string.off)
    if (key in setOf("aacp:0a", "aacp:1b", "aacp:20", "aacp:25", "aacp:26", "aacp:28", "aacp:2f", "aacp:33", "aacp:34", "aacp:35", "aacp:37", "aacp:3b") && first != null && first in 1..2)
        return stringResource(if (first == 1) R.string.on else R.string.off)
    if (key == "aacp:1f" && first != null && first in 0..100) return first.toString()
    if (key == "aacp:2e" && first != null && first in 0..100) return (100 - first).toString()
    if (key == "aacp:1a" && first != null && first in 0..15 && Integer.bitCount(first) >= 2) {
        return listOf(
            0x01 to stringResource(R.string.off), 0x02 to stringResource(R.string.noise_cancellation),
            0x04 to stringResource(R.string.transparency), 0x08 to stringResource(R.string.adaptive)
        ).filter { (bit, _) -> (first and bit) != 0 }.joinToString(", ") { it.second }
    }
    if (key == "aacp:23") return stringResource(when (first) {
        1 -> R.string.default_option
        2 -> R.string.longer
        3 -> R.string.longest
        else -> R.string.settings_sync_value_unavailable
    })
    if (key == "aacp:24" && value.size >= 2 && first == 0 && value[1].toInt() in 2..3) {
        val flipped = value[1].toInt() == 2
        val once = stringResource(R.string.press_once)
        val twice = stringResource(R.string.press_twice)
        return stringResource(R.string.settings_sync_call_value, stringResource(R.string.mute_unmute),
            if (flipped) twice else once, stringResource(R.string.hang_up), if (flipped) once else twice)
    }
    if (key == "aacp:2c" && value.size >= 2 && first == 1 && value[1].toInt() in 1..2)
        return stringResource(if (value[1].toInt() == 1) R.string.on else R.string.off)
    if (key == "aacp:17" || key == "aacp:18") return stringResource(when (first) {
        0 -> R.string.default_option
        1 -> R.string.slower
        2 -> R.string.slowest
        else -> R.string.settings_sync_value_unavailable
    })
    if (key == "aacp:0d") return stringResource(when (first) {
        1 -> R.string.off
        2 -> R.string.noise_cancellation
        3 -> R.string.transparency
        4 -> R.string.adaptive
        else -> R.string.settings_sync_value_unavailable
    })
    if (key == "aacp:01") return stringResource(when (first) {
        0 -> R.string.microphone_automatic
        1 -> R.string.microphone_always_right
        2 -> R.string.microphone_always_left
        else -> R.string.settings_sync_value_unavailable
    })
    return stringResource(R.string.settings_sync_value_unavailable)
}

private data class CompositeSummary(
    val leftGain: Float, val rightGain: Float,
    val leftTone: Float, val rightTone: Float,
    val leftReduction: Float, val rightReduction: Float,
    val leftBoost: Boolean, val rightBoost: Boolean,
    val enabled: Boolean? = null,
    val leftEq: List<Float>? = null, val rightEq: List<Float>? = null,
)

@Composable
private fun compositeValue(key: String, value: ByteArray, actual: Boolean): String {
    val handle = if (key == "att:18") ATTHandles.TRANSPARENCY else ATTHandles.HEARING_AID
    val summary = (if (!actual) {
        parseCompositeAttSettings(handle, value.toString(Charsets.UTF_8))?.let { local ->
            CompositeSummary(
                local.amplification - local.balance / 2f, local.amplification + local.balance / 2f,
                local.tone, local.tone, local.ambientNoiseReduction, local.ambientNoiseReduction,
                local.conversationBoost, local.conversationBoost,
                local.enabled.takeIf { handle == ATTHandles.TRANSPARENCY },
                local.transparencyEq.takeIf { handle == ATTHandles.TRANSPARENCY },
                local.transparencyEq.takeIf { handle == ATTHandles.TRANSPARENCY },
            )
        }
    } else if (handle == ATTHandles.TRANSPARENCY) {
        parseTransparencySettingsResponse(value)?.let { observed ->
            CompositeSummary(observed.leftAmplification, observed.rightAmplification,
                observed.leftTone, observed.rightTone, observed.leftAmbientNoiseReduction, observed.rightAmbientNoiseReduction,
                observed.leftConversationBoost, observed.rightConversationBoost, observed.enabled,
                observed.leftEQ.toList(), observed.rightEQ.toList())
        }
    } else {
        parseHearingAidSettingsResponse(value)?.let { observed ->
            CompositeSummary(observed.leftAmplification, observed.rightAmplification,
                observed.leftTone, observed.rightTone, observed.leftAmbientNoiseReduction, observed.rightAmbientNoiseReduction,
                observed.leftConversationBoost, observed.rightConversationBoost)
        }
    }) ?: return stringResource(R.string.settings_sync_value_unavailable)
    fun number(value: Float) = String.format(Locale.ROOT, "%.2f", value)
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)
    val detail = stringResource(R.string.settings_sync_composite_detail,
        number(summary.leftGain), number(summary.rightGain), number(summary.leftTone), number(summary.rightTone),
        number(summary.leftReduction), number(summary.rightReduction),
        if (summary.leftBoost) on else off, if (summary.rightBoost) on else off)
    if (summary.enabled == null) return detail + " · " + stringResource(R.string.settings_sync_private_fields_hidden)
    val leftEq = summary.leftEq?.joinToString(", ") { number(it) } ?: ""
    val rightEq = summary.rightEq?.joinToString(", ") { number(it) } ?: ""
    return stringResource(R.string.settings_sync_transparency_detail,
        if (summary.enabled) on else off, detail, leftEq, rightEq)
}
