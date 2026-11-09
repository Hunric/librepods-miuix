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

package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.QuickSettingsDialogActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.NoiseControlMode
import kotlin.io.encoding.ExperimentalEncodingApi

@RequiresApi(Build.VERSION_CODES.Q)
class AirPodsQSService : TileService() {

    private var currentAncMode: Int? = null
    private var isAirPodsConnected: Boolean = false

    private val ancStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AirPodsNotifications.ANC_DATA) {
                val newMode = intent.getIntExtra("data", NoiseControlMode.OFF.ordinal + 1)
                Log.d("AirPodsQSService", "Received ANC update: $newMode")
                refreshControls()
                updateTile()
            }
        }
    }

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPodsNotifications.AIRPODS_CONNECTED -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_CONNECTED")
                    isAirPodsConnected = true
                    refreshControls()
                    updateTile()
                }
                AirPodsNotifications.AIRPODS_INFORMATION_UPDATED,
                "me.kavishdevar.librepods.CONTROL_SETTINGS_READY" -> { refreshControls(); updateTile() }
                AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                    Log.d("AirPodsQSService", "Received AIRPODS_DISCONNECTED")
                    isAirPodsConnected = false
                    currentAncMode = null
                    updateTile()
                }
            }
        }
    }

    private var observedService: AirPodsService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val controlIdentifiers = listOf(
        AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE,
        AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
    )
    private val controlListener = object : AACPManager.ControlCommandListener {
        override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
            handler.post { if (observedService != null) { refreshControls(); updateTile() } }
        }
    }

    private fun refreshControls() {
        val service = ServiceManager.getService()
        if (service !== observedService) {
            controlIdentifiers.forEach { observedService?.aacpManager?.unregisterControlCommandListener(it, controlListener) }
            observedService = service
            controlIdentifiers.forEach { service?.aacpManager?.registerControlCommandListener(it, controlListener) }
        }
        currentAncMode = controlValues()[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]
            ?.firstOrNull()?.toInt()?.takeIf { it in 1..4 }
    }

    private fun controlValues(): Map<AACPManager.Companion.ControlCommandIdentifiers, ByteArray> {
        val service = ServiceManager.getService() ?: return emptyMap()
        val token = service.getNameConnectionToken() ?: return emptyMap()
        val values = service.getLocalDesiredControlValues()
        return if (service.getNameConnectionToken() == token) values else emptyMap()
    }

    @SuppressLint("InlinedApi", "UnspecifiedRegisterReceiverFlag")
    override fun onStartListening() {
        super.onStartListening()
        Log.d("AirPodsQSService", "onStartListening")

        isAirPodsConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
        refreshControls()

        val ancIntentFilter = IntentFilter(AirPodsNotifications.ANC_DATA)
        val availabilityIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
            addAction("me.kavishdevar.librepods.CONTROL_SETTINGS_READY")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(ancStatusReceiver, ancIntentFilter, RECEIVER_EXPORTED)
                registerReceiver(availabilityReceiver, availabilityIntentFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(ancStatusReceiver, ancIntentFilter)
                registerReceiver(availabilityReceiver, availabilityIntentFilter)
            }
            Log.d("AirPodsQSService", "Receivers registered")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error registering receivers: $e")
        }

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d("AirPodsQSService", "onStopListening")
        controlIdentifiers.forEach { observedService?.aacpManager?.unregisterControlCommandListener(it, controlListener) }
        observedService = null
        try {
            unregisterReceiver(ancStatusReceiver)
            unregisterReceiver(availabilityReceiver)
            Log.d("AirPodsQSService", "Receivers unregistered")
        } catch (e: IllegalArgumentException) {
            Log.e("AirPodsQSService", "Receiver not registered or already unregistered: $e")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error unregistering receivers: $e")
        }
    }

    override fun onClick() {
        super.onClick()
        Log.d("AirPodsQSService", "onClick - Current state: $isAirPodsConnected, Current mode: $currentAncMode")
        if (!isAirPodsConnected) {
            Log.d("AirPodsQSService", "Tile clicked but AirPods not connected.")
            return
        }

        val clickBehavior = "cycle"

        if (clickBehavior == "dialog") {
            launchDialogActivity()
        } else {
            cycleAncMode()
        }
    }

    private fun launchDialogActivity() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, QuickSettingsDialogActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                val intent = Intent(this, QuickSettingsDialogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                @Suppress("DEPRECATION")
                @SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            Log.d("AirPodsQSService", "Called startActivityAndCollapse for QuickSettingsDialogActivity")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error launching QuickSettingsDialogActivity: $e")
        }
    }

    private fun cycleAncMode() {
        val service = ServiceManager.getService()
        if (service == null) {
            Log.d("AirPodsQSService", "Tile clicked (cycle mode) but service is null.")
            return
        }
        val token = service.getNameConnectionToken() ?: return
        val nextMode = getNextAncMode()
        Log.d("AirPodsQSService", "Cycling ANC mode to: $nextMode")
        service.setUserControlValue(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE,
            byteArrayOf(nextMode.toByte()), token
        )
        refreshControls()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        Log.d("AirPodsQSService", "updateTile - Connected: $isAirPodsConnected, Mode: $currentAncMode")

        val deviceName = ServiceManager.getService()?.getCurrentDeviceName() ?: "AirPods"

        if (isAirPodsConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getModeLabel(currentAncMode)
            tile.subtitle = deviceName
            tile.icon = Icon.createWithResource(this, getModeIcon(currentAncMode))
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "AirPods"
            tile.subtitle = "Disconnected"
            tile.icon = Icon.createWithResource(this, R.drawable.airpods)
        }

        try {
            tile.updateTile()
            Log.d("AirPodsQSService", "Tile updated successfully")
        } catch (e: Exception) {
            Log.e("AirPodsQSService", "Error updating tile: $e")
        }
    }

    private fun isOffModeEnabled(): Boolean {
        return controlValues()[AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION]
            ?.firstOrNull()?.toInt() == 1
    }

    private fun getAvailableModes(): List<Int> {
        val modes = mutableListOf(NoiseControlMode.TRANSPARENCY.ordinal + 1)
        if (ServiceManager.getService()?.supportsAdaptiveAudioForCurrentSession() == true) {
            modes.add(NoiseControlMode.ADAPTIVE.ordinal + 1)
        }
        modes.add(NoiseControlMode.NOISE_CANCELLATION.ordinal + 1)
        if (isOffModeEnabled()) {
            modes.add(0, NoiseControlMode.OFF.ordinal + 1)
        }
        return modes
    }

    private fun getNextAncMode(): Int {
        val availableModes = getAvailableModes()
        Log.d("AirPodsQSService", "availableModes: $availableModes, currentAncMode: $currentAncMode")
        val currentIndex = currentAncMode?.let { availableModes.indexOf(it) } ?: -1
        val nextIndex = (currentIndex + 1) % availableModes.size
        Log.d("AirPodsQSService", "nextIndex: $nextIndex")
        return availableModes[nextIndex]
    }

    private fun getModeLabel(mode: Int?): String {
        return when (mode) {
            NoiseControlMode.OFF.ordinal + 1 -> "Off"
            NoiseControlMode.TRANSPARENCY.ordinal + 1 -> "Transparency"
            NoiseControlMode.ADAPTIVE.ordinal + 1 -> "Adaptive"
            NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> "Noise Cancellation"
            else -> ""
        }
    }

     private fun getModeIcon(mode: Int?): Int {
         return when (mode) {
             NoiseControlMode.OFF.ordinal + 1 -> R.drawable.noise_cancellation
             NoiseControlMode.TRANSPARENCY.ordinal + 1 -> R.drawable.transparency
             NoiseControlMode.ADAPTIVE.ordinal + 1 -> R.drawable.adaptive
             NoiseControlMode.NOISE_CANCELLATION.ordinal + 1 -> R.drawable.noise_cancellation
             else -> R.drawable.airpods
         }
     }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d("AirPodsQSService", "Tile added")
    }
}
