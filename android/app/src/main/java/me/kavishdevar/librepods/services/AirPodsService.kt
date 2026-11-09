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

//import me.kavishdevar.librepods.utils.CrossDevice
//import me.kavishdevar.librepods.utils.CrossDevicePackets
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTCCCDHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.ATTResult
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.createBluetoothSocket
import me.kavishdevar.librepods.data.AirPodsBase
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.ControlDisplaySnapshot
import me.kavishdevar.librepods.data.ControlDisplayState
import me.kavishdevar.librepods.data.ControlDisplayVersion
import me.kavishdevar.librepods.data.ControlSettingsPolicy
import me.kavishdevar.librepods.data.CompositeAttSettings
import me.kavishdevar.librepods.data.CompositeAttPatchResult
import me.kavishdevar.librepods.data.AttReportOrder
import me.kavishdevar.librepods.data.DeviceSettingsBackend
import me.kavishdevar.librepods.data.DeviceSettingsStore
import me.kavishdevar.librepods.data.EarWearingState
import me.kavishdevar.librepods.data.SavedSettingsDefaults
import me.kavishdevar.librepods.data.SettingsSyncPolicy
import me.kavishdevar.librepods.data.StartupConnectAttempt
import me.kavishdevar.librepods.data.StartupDiscoveryGate
import me.kavishdevar.librepods.data.StartupDiscoveryPolicy
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.isHeadTrackingData
import me.kavishdevar.librepods.data.compositeAttSettingsFromDevice
import me.kavishdevar.librepods.data.compositeAttSettingsFromLegacy
import me.kavishdevar.librepods.data.defaultCompositeAttSettings
import me.kavishdevar.librepods.data.matchesOwned
import me.kavishdevar.librepods.data.parseCompositeAttSettings
import me.kavishdevar.librepods.data.patchFromDesired
import me.kavishdevar.librepods.data.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.data.parseEarWearingState
import me.kavishdevar.librepods.data.parseTransparencySettingsResponse
import me.kavishdevar.librepods.data.serializeCompositeAttSettings
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import java.io.FileDescriptor
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AirPodsService"
private const val SYNC_LOG_TAG = "SettingsSync"
private const val STARTUP_RESPONSE_TIMEOUT_MS = 3_000L

object ServiceManager {
    private var service: AirPodsService? = null

    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }
}

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    var macAddress = ""
    var localMac = ""
    lateinit var aacpManager: AACPManager
    lateinit var attManager: ATTManagerv2
    var airpodsInstance: AirPodsInstance? = null
    var cameraActive = false
    private var disconnectedBecauseReversed = false
    private var otherDeviceTookOver = false
    private val connectionAttemptLock = Any()
    private val connectionPublishLock = Any()
    private val syncDiagnosticLock = Any()
    private val syncDiagnosticEvents = java.util.ArrayDeque<String>()

    private fun logSyncDiagnostic(message: String) {
        synchronized(syncDiagnosticLock) {
            if (syncDiagnosticEvents.size >= 1024) syncDiagnosticEvents.removeFirst()
            syncDiagnosticEvents.addLast("elapsedMs=${SystemClock.elapsedRealtime()} $message")
        }
        Log.i(SYNC_LOG_TAG, message)
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        if (args?.contains("--settings-sync") != true) {
            super.dump(fd, writer, args)
            return
        }
        val snapshot = synchronized(syncDiagnosticLock) { syncDiagnosticEvents.toList() }
        writer.println(" SettingsSync buffer_count=${snapshot.size} elapsedMs=${SystemClock.elapsedRealtime()}")
        snapshot.forEach { writer.println(" SettingsSync $it") }
    }

    private enum class ConversationAwarenessEvent { START, STOP, OTHER }
    private enum class DiscoveryReason { ON_CREATE, ON_START, ACL_CONNECTED, A2DP_CONNECTED, UUID, BROADCAST, BLE_STATUS, FOLLOW_UP }
    private enum class DiscoveryFinish { NO_ADAPTER, REJECTED, ERROR, TIMEOUT, DISCONNECTED, INVALID_PROFILE, COMPLETED }
    private enum class StartupRecoveryTrigger { HANDSHAKE_85, INITIALIZATION_TIMEOUT }
    private val startupDiscoveryGate = StartupDiscoveryGate()
    private val discoverySdpLock = Any()
    private val discoverySdpRequested = mutableSetOf<String>()
    private val controlServiceUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
    private class PendingControlSocket(val socket: BluetoothSocket, val attempt: StartupConnectAttempt)
    @Volatile private var controlServiceDestroyRequested = false
    @Volatile private var pendingControlSocket: PendingControlSocket? = null

    private fun cancelPendingControlSocket(pending: PendingControlSocket) {
        if (pending.attempt.cancelPending()) try { pending.socket.close() } catch (_: Exception) { }
    }

    private fun closeCandidateOnDestroyOrFailure(pending: PendingControlSocket) {
        if (pending.attempt.cancelPending() || pending.attempt.abandonClaimed()) {
            try { pending.socket.close() } catch (_: Exception) { }
        }
    }

    private fun requestStartupDiscovery(reason: DiscoveryReason) {
        val request = startupDiscoveryGate.request()
        logSyncDiagnostic("discovery_request generation=${request.generation} reason=$reason outcome=${request.admission}")
        if (request.admission == StartupDiscoveryGate.Admission.STARTED) startStartupDiscovery(request.generation)
    }

    private fun finishStartupDiscovery(generation: Long, waitingOnly: Boolean, outcome: DiscoveryFinish) {
        val completion = startupDiscoveryGate.finish(generation, waitingOnly)
        if (!completion.accepted) return
        logSyncDiagnostic("discovery_finish generation=$generation outcome=$outcome")
        completion.nextGeneration?.let { next ->
            logSyncDiagnostic("discovery_request generation=$next reason=FOLLOW_UP outcome=STARTED")
            startStartupDiscovery(next)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startStartupDiscovery(generation: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(3000)
            finishStartupDiscovery(generation, waitingOnly = true, DiscoveryFinish.TIMEOUT)
        }
        CoroutineScope(Dispatchers.IO).launch {
            if (!startupDiscoveryGate.isWaiting(generation)) return@launch
            val adapter = try { getSystemService(BluetoothManager::class.java)?.adapter } catch (_: Exception) { null }
            if (adapter == null) {
                finishStartupDiscovery(generation, waitingOnly = true, DiscoveryFinish.NO_ADAPTER)
                return@launch
            }
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.A2DP || !startupDiscoveryGate.acceptCallback(generation)) {
                        try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) { }
                        if (profile != BluetoothProfile.A2DP) {
                            finishStartupDiscovery(generation, waitingOnly = true, DiscoveryFinish.INVALID_PROFILE)
                        } else {
                            logSyncDiagnostic("discovery_profile generation=$generation outcome=LATE")
                        }
                        return
                    }
                    logSyncDiagnostic("discovery_profile generation=$generation outcome=CALLBACK")
                    val cleaned = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun closeAndFinish() {
                        if (!cleaned.compareAndSet(false, true)) return
                        try { adapter.closeProfileProxy(profile, proxy) } catch (_: Exception) { }
                        finishStartupDiscovery(generation, waitingOnly = false, DiscoveryFinish.COMPLETED)
                    }
                    val job = CoroutineScope(Dispatchers.IO).launch discoveryWorker@ {
                        try {
                            if (!startupDiscoveryGate.mayConnect(generation, candidateConnected = true)) return@discoveryWorker
                            val connected = proxy.connectedDevices
                            val successful = validControlAddress(sharedPreferences.getString("last_successful_aacp_address", null))
                            val preferred = successful ?: validControlAddress(sharedPreferences.getString("mac_address", null))
                            val seen = mutableSetOf<String>()
                            val candidates = connected.mapNotNull { candidate ->
                                val address = try { validControlAddress(candidate.address) } catch (_: Exception) { null }
                                    ?: return@mapNotNull null
                                if (!seen.add(address)) return@mapNotNull null
                                val uuids = try { candidate.uuids } catch (_: Exception) { null }
                                val hasUuid = uuids?.contains(controlServiceUuid) == true
                                val hasReports = if (address == preferred) {
                                    try { deviceSettingsStore.getReportedIdentifiers(address).isNotEmpty() } catch (_: Exception) { false }
                                } else false
                                val hasSuccessfulConnection = address == successful
                                if (!hasUuid && !hasReports && !hasSuccessfulConnection) {
                                    val shouldRequest = synchronized(discoverySdpLock) { discoverySdpRequested.add(address) }
                                    if (shouldRequest) {
                                        val requested = try { candidate.fetchUuidsWithSdp() } catch (_: Exception) { false }
                                        if (!requested) synchronized(discoverySdpLock) { discoverySdpRequested.remove(address) }
                                    }
                                }
                                StartupDiscoveryPolicy.Candidate(candidate, address == preferred, hasUuid, hasReports, hasSuccessfulConnection)
                            }
                            val selection = StartupDiscoveryPolicy.select(candidates)
                            logSyncDiagnostic("discovery_candidates generation=$generation connected=${connected.size} " +
                                "eligible=${candidates.size} uuidMatches=${candidates.count { it.hasUuid }} outcome=${selection.outcome}")
                            val candidate = selection.candidate?.device ?: return@discoveryWorker
                            synchronized(connectionAttemptLock) {
                                if (!startupDiscoveryGate.mayConnect(generation, candidateConnected = true) || controlServiceDestroyed) {
                                    logSyncDiagnostic("discovery_connect generation=$generation outcome=STALE")
                                    return@synchronized
                                }
                                val connectedNow = try {
                                    proxy.getConnectionState(candidate) == BluetoothProfile.STATE_CONNECTED
                                } catch (_: Exception) { false }
                                if (!startupDiscoveryGate.mayConnect(generation, connectedNow) || controlServiceDestroyed) {
                                    val outcome = if (!connectedNow) "DISCONNECTED" else "STALE"
                                    logSyncDiagnostic("discovery_connect generation=$generation outcome=$outcome")
                                } else if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                                    logSyncDiagnostic("discovery_connect generation=$generation outcome=ALREADY_CONNECTED")
                                } else {
                                    logSyncDiagnostic("discovery_connect generation=$generation outcome=STARTED")
                                    connectToSocketInternal(adapter, candidate, manual = false, allowHandshakeRecovery = true)
                                    logSyncDiagnostic("discovery_connect generation=$generation outcome=RETURNED " +
                                        "connected=${BluetoothConnectionManager.aacpSocket?.isConnected == true}")
                                }
                            }
                        } catch (_: Exception) {
                            logSyncDiagnostic("discovery_profile generation=$generation outcome=ERROR")
                        } finally {
                            closeAndFinish()
                        }
                    }
                    job.invokeOnCompletion { closeAndFinish() }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        finishStartupDiscovery(generation, waitingOnly = true, DiscoveryFinish.DISCONNECTED)
                    }
                }
            }
            try {
                if (!adapter.getProfileProxy(this@AirPodsService, listener, BluetoothProfile.A2DP)) {
                    finishStartupDiscovery(generation, waitingOnly = true, DiscoveryFinish.REJECTED)
                }
            } catch (_: Exception) {
                finishStartupDiscovery(generation, waitingOnly = true, DiscoveryFinish.ERROR)
            }
        }
    }

    private class ControlDeviceState(val address: String) {
        val desiredControls = mutableMapOf<Int, ByteArray>()
        var desiredCustomEq: CustomEq? = null
        var desiredName: String? = null
        val desiredAtt = mutableMapOf<ATTHandles, ByteArray>()
        val desiredComposites = mutableMapOf<ATTHandles, CompositeAttSettings>()
    }
    private class AttSession(val socket: BluetoothSocket, val epoch: Long) {
        val reports = AttReportOrder<ATTHandles>()
    }
    private sealed interface AttWork {
        data object Bootstrap : AttWork
        data class Write(val request: SettingsSyncPolicy.WriteRequest) : AttWork
    }
    private class ControlSession(
        val state: ControlDeviceState, val socket: BluetoothSocket, val device: BluetoothDevice,
        initialAttSocket: BluetoothSocket?, val connectionToken: Long,
        val sync: SettingsSyncPolicy
    ) {
        @Volatile var attEpoch = if (initialAttSocket == null) 0L else 1L
        @Volatile var att: AttSession? = initialAttSocket?.let { AttSession(it, attEpoch) }
        @Volatile var connectingAttSocket: BluetoothSocket? = null
        val attQueue = Channel<AttWork>(Channel.UNLIMITED)
        // Used only as an actual-report cache; display snapshots are built from local settings.
        val display = ControlDisplayState<ControlCommandIdentifiers>(connectionToken)
        var localRevision = 0L
        var syncRef: SettingsSyncPolicy.SessionRef? = null
        // This observation belongs only to this control connection.
        var wearState = EarWearingState.UNKNOWN
        @Volatile var informationReceived = false
        // Reports cached before these user edits cannot confirm their new desired revisions.
        val editedBeforeModel = mutableSetOf<String>()
        // Keys the user expressed local intent for in this session; device reports never override them.
        val userEditedKeys = mutableSetOf<String>()
        // Keys already taken from a device report in this session.
        val adoptedKeys = mutableSetOf<String>()
        val syncWake = Channel<Unit>(Channel.CONFLATED)
        var model: AirPodsBase? = null
        var reportedName: String? = null
        var handshakeAcknowledged = false
        var featuresAcknowledged = false
        var notificationsRequested = false
        var restoreStartedAt = 0L
        var lastControlAt = 0L
        var restoreJob: Job? = null
        var ready = false
        var reportedCustomEq: CustomEq? = null
        val reportedAttValues = mutableMapOf<ATTHandles, ByteArray>()
        // Accessed only while holding connectionAttemptLock.
        var persistedNameReport = false
        val persistedAttReportHandles = mutableSetOf<ATTHandles>()
        var attJob: Job? = null
    }

    private val controlDevices = mutableMapOf<String, ControlDeviceState>()
    @Volatile private var controlSession: ControlSession? = null
    private var lastControlDevice: ControlDeviceState? = null
    private var startupLegacyAddress: String? = null
    private var startupSavedDeviceName: Pair<String, String>? = null
    private var startupLegacyMask: Int? = null
    private var firstControlConnection = true
    private var controlConnectionToken = 0L
    private var controlServiceDestroyed = false
    private val attWorkJob = SupervisorJob()
    private val attWorkScope = CoroutineScope(Dispatchers.IO + attWorkJob)
    private val _settingsSyncStatus = MutableStateFlow<Map<String, SettingsSyncPolicy.ItemSnapshot>>(emptyMap())
    val settingsSyncStatus: StateFlow<Map<String, SettingsSyncPolicy.ItemSnapshot>> = _settingsSyncStatus.asStateFlow()
    private val deviceSettingsStore by lazy {
        DeviceSettingsStore(object : DeviceSettingsBackend {
            override fun getString(key: String) = sharedPreferences.getString(key, null)
            override fun getStringSet(key: String): Set<String> =
                sharedPreferences.getStringSet(key, emptySet())?.toSet() ?: emptySet()
            override fun putString(key: String, value: String) {
                check(sharedPreferences.edit().putString(key, value).commit()) { "Settings storage failed" }
            }
            override fun remove(key: String) {
                check(sharedPreferences.edit().remove(key).commit()) { "Settings storage failed" }
            }
            override fun putStringSet(key: String, value: Set<String>) {
                check(sharedPreferences.edit().putStringSet(key, value.toSet()).commit()) { "Settings storage failed" }
            }
        }, { it in ControlSettingsPolicy.SUPPORTED_IDENTIFIERS })
    }

    private fun validControlAddress(address: String?): String? = address?.takeIf {
        it.matches(Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))
    }?.uppercase(java.util.Locale.ROOT)

    private fun isCurrentControlSession(session: ControlSession): Boolean =
        controlSession === session && BluetoothConnectionManager.aacpSocket === session.socket && session.socket.isConnected

    /** Caller holds connectionAttemptLock; keep per-device intent after closing the session. */
    private fun endControlSession() {
        val hadSession = controlSession != null
        controlSession?.let {
            logSyncDiagnostic("session_end token=${it.connectionToken}")
            it.restoreJob?.cancel()
            it.syncWake.close()
            it.attJob?.cancel()
            it.attQueue.close()
            it.syncRef?.let(it.sync::end)
            if (::attManager.isInitialized) attManager.setOnRemoteValueReceived(null)
            it.attEpoch++
            try { it.att?.socket?.close() } catch (_: Exception) { Log.w(TAG, "Unable to close ATT connection") }
            try { it.connectingAttSocket?.close() } catch (_: Exception) { Log.w(TAG, "Unable to cancel ATT connect") }
            it.att = null
            it.connectingAttSocket = null
            if (::attManager.isInitialized) attManager.stopReaderAndAwait(1000)
            it.display.close()
            it.reportedCustomEq = null
            it.reportedAttValues.clear()
        }
        controlSession = null
        _settingsSyncStatus.value = emptyMap()
        if (hadSession) updateNoiseControlWidget()
    }

    private fun controlKey(identifier: Int) = "aacp:${identifier.toString(16).padStart(2, '0')}"
    private fun attKey(handle: ATTHandles) = "att:${handle.value.toString(16).padStart(2, '0')}"

    private fun canonicalControl(value: ByteArray): ByteArray {
        var length = value.size
        while (length > 1 && value[length - 1] == 0.toByte()) length--
        return value.copyOf(length)
    }

    /** One matcher for both the sync policy and device-value adoption; never compares raw ATT ACKs. */
    private fun matchesSyncValue(key: String, desired: ByteArray, actual: ByteArray): Boolean = when (key) {
        "att:18", "att:2a" -> {
            val handle = if (key == "att:18") ATTHandles.TRANSPARENCY else ATTHandles.HEARING_AID
            parseCompositeAttSettings(handle, desired.toString(Charsets.UTF_8))?.let { matchesOwned(it, actual) } == true
        }
        else -> if (key.startsWith("aacp:")) canonicalControl(desired).contentEquals(canonicalControl(actual))
        else desired.contentEquals(actual)
    }

    private fun newSettingsSyncPolicy() = SettingsSyncPolicy(matcher = ::matchesSyncValue)

    @SuppressLint("MissingPermission")
    private fun beginControlSession(socket: BluetoothSocket, device: BluetoothDevice) {
        endControlSession()
        controlConnectionToken++
        val address = validControlAddress(device.address) ?: return
        // Migrate the older single-device long-press value before defaults are seeded.
        if (firstControlConnection) {
            firstControlConnection = false
            val mask = startupLegacyMask
            if (address == startupLegacyAddress && mask != null &&
                ControlSettingsPolicy.isValidUserValue(0x1A, byteArrayOf(mask.toByte()))) {
                if (!sharedPreferences.getBoolean("device_settings.v1.legacy_1a_migrated", false) &&
                    deviceSettingsStore.getDesired(address, 0x1A) == null) {
                    check(deviceSettingsStore.saveExplicitSelection(address, 0x1A, byteArrayOf(mask.toByte())))
                }
                check(sharedPreferences.edit().putBoolean("device_settings.v1.legacy_1a_migrated", true).commit())
            }
        }
        check(SavedSettingsDefaults.seedMissing(deviceSettingsStore, address, getSystemDeviceNameOrNull(device))) {
            "Unable to initialize saved device settings"
        }
        val state = ControlDeviceState(address)
        deviceSettingsStore.listDesired(address).forEach { (id, value) ->
            if (ControlSettingsPolicy.isValidUserValue(id, value)) state.desiredControls[id] = value.copyOf()
        }
        deviceSettingsStore.getDesiredCustomEq(address)?.let { state.desiredCustomEq = customEqFromBytes(it) }
        state.desiredName = deviceSettingsStore.getDesiredName(address)
        deviceSettingsStore.getDesiredAtt(address, ATTHandles.LOUD_SOUND_REDUCTION.value)?.let { payload ->
            if (isValidAttValue(ATTHandles.LOUD_SOUND_REDUCTION, payload))
                state.desiredAtt[ATTHandles.LOUD_SOUND_REDUCTION] = payload.copyOf()
        }
        for (handle in listOf(ATTHandles.TRANSPARENCY, ATTHandles.HEARING_AID)) {
            val stored = deviceSettingsStore.getCompositeAttSettings(address, handle.value)
            val parsed = stored?.let { parseCompositeAttSettings(handle, it) }
            // The legacy raw slot was written only for explicit user intent; it has no
            // per-frequency provenance, so the helper never claims hearing thresholds.
            val selected = parsed ?: if (stored == null) {
                val legacy = deviceSettingsStore.getDesiredAtt(address, handle.value)
                val migrated = compositeAttSettingsFromLegacy(handle, legacy)
                if (legacy != null && migrated == null) Log.w(TAG, "Legacy ATT ${handle.value} could not be migrated without guessing; retained raw entry")
                migrated ?: requireNotNull(defaultCompositeAttSettings(handle))
            } else requireNotNull(defaultCompositeAttSettings(handle))
            if (stored == null) check(deviceSettingsStore.saveCompositeAttSettings(address, handle.value,
                requireNotNull(serializeCompositeAttSettings(selected))))
            state.desiredComposites[handle] = selected
        }
        controlDevices[address] = state
        lastControlDevice = state
        resetDeviceName(device)
        val session = ControlSession(state, socket, device, BluetoothConnectionManager.attSocket,
            controlConnectionToken, newSettingsSyncPolicy())
        controlSession = session
        logSyncDiagnostic("wear_state token=${session.connectionToken} source=SESSION state=UNKNOWN")
        initializeAttSession(session)
        aacpManager.disconnected()
        state.desiredControls.forEach { (identifier, value) -> applyLocalControlBehavior(state, identifier, value) }
        sendBroadcast(Intent("me.kavishdevar.librepods.CONTROL_SETTINGS_READY").setPackage(packageName))
    }

    private fun isValidAttValue(handle: ATTHandles, value: ByteArray): Boolean = when (handle) {
        ATTHandles.LOUD_SOUND_REDUCTION -> value.size == 1 && value[0].toInt() in 0..1
        ATTHandles.TRANSPARENCY -> (value.size == 100 || value.size in 104..512) &&
            parseTransparencySettingsResponse(value) != null
        ATTHandles.HEARING_AID -> value.size in 104..512 && parseHearingAidSettingsResponse(value) != null
    }

    private fun isCurrentAttSession(session: ControlSession, att: AttSession): Boolean =
        isCurrentControlSession(session) && session.att === att && session.attEpoch == att.epoch &&
            BluetoothConnectionManager.attSocket === att.socket && att.socket.isConnected

    private fun supportsAtt(model: AirPodsBase, handle: ATTHandles): Boolean = when (handle) {
        ATTHandles.LOUD_SOUND_REDUCTION -> Capability.LOUD_SOUND_REDUCTION in model.capabilities
        // There is no separate customized-transparency capability in the current model table.
        ATTHandles.TRANSPARENCY, ATTHandles.HEARING_AID -> Capability.HEARING_AID in model.capabilities
    }

    private fun hasAttAccess(): Boolean = try {
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false)
    } catch (_: Exception) { false }

    /** Caller holds connectionAttemptLock. Only remote data enters reportedAttValues. */
    private fun commitAttReport(session: ControlSession, att: AttSession, handle: ATTHandles,
        snapshot: AttReportOrder.Report, request: SettingsSyncPolicy.WriteRequest? = null) {
        if (!isCurrentAttSession(session, att) || att.reports.latest(handle) !== snapshot) return
        val value = snapshot.value
        session.reportedAttValues[handle] = value
        if (handle !in session.persistedAttReportHandles) {
            val recorded = try {
                deviceSettingsStore.recordDeviceAttReport(session.state.address, handle.value)
            } catch (_: Exception) {
                false
            }
            if (recorded) session.persistedAttReportHandles.add(handle)
            else Log.e(TAG, "Unable to record ATT report metadata")
        }
        val ref = session.syncRef
        if (ref != null) {
            // Even a value too old to confirm this revision proves the handle reports.
            session.sync.noteRemoteObservation(ref, attKey(handle))
            val item = session.sync.snapshot(ref)[attKey(handle)]
            val phase = item?.phase
            // Reader-side receipt order, not coroutine dispatch order, determines freshness.
            val fresh = if (request != null) request.session === ref && request.key == attKey(handle) &&
                item?.revision == request.revision && phase == SettingsSyncPolicy.Phase.SENDING &&
                att.reports.isFreshForWrite(handle, snapshot, request.revision, request.sequence)
            else phase != SettingsSyncPolicy.Phase.SENDING && att.reports.isFresh(handle, snapshot)
            if (fresh) {
                if (request == null) {
                    observeReportedValue(session, session.model, attKey(handle), value, SystemClock.elapsedRealtime())
                } else {
                    session.sync.observeActual(ref, attKey(handle), value, SystemClock.elapsedRealtime())
                }
            }
            publishSyncStatus(session)
            wakeSync(session)
        }
    }

    /** The reader callback must never wait for the Service lock or perform storage. */
    private fun attachAttReader(session: ControlSession, att: AttSession) {
        attManager.setOnRemoteValueReceived { handle, value, source ->
            if (source === att.socket && controlSession === session && session.att === att &&
                session.attEpoch == att.epoch && isValidAttValue(handle, value)) {
                val snapshot = att.reports.record(handle, value)
                attWorkScope.launch {
                    synchronized(connectionAttemptLock) { commitAttReport(session, att, handle, snapshot) }
                }
            }
        }
        attManager.startReader()
    }

    private fun initializeAttSession(session: ControlSession) {
        session.att?.let { attachAttReader(session, it) }
        session.attJob = attWorkScope.launch {
            for (work in session.attQueue) {
                try {
                    when (work) {
                        AttWork.Bootstrap -> {
                            ensureAttSession(session)?.let { bootstrapAtt(session, it) }
                        }
                        is AttWork.Write -> performAttWrite(session, work.request)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Log.w(TAG, "ATT worker operation failed")
                    if (work is AttWork.Write) synchronized(connectionAttemptLock) {
                        val ref = session.syncRef
                        if (isCurrentControlSession(session) && ref != null && session.sync.startWrite(ref, work.request)) {
                            session.sync.completeWrite(ref, work.request, SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, SystemClock.elapsedRealtime())
                            publishSyncStatus(session)
                            wakeSync(session)
                        }
                    }
                }
            }
        }
    }

    /** A timed-out or superseded ATT stream is never reused, and AACP remains intact. */
    @SuppressLint("MissingPermission")
    private suspend fun ensureAttSession(session: ControlSession): AttSession? {
        if (!hasAttAccess()) return null
        val current = synchronized(connectionAttemptLock) {
            if (!isCurrentControlSession(session)) return null
            session.att?.takeIf { isCurrentAttSession(session, it) && attManager.isSessionUsable(it.socket) }
        }
        if (current != null) return current
        val retired = synchronized(connectionAttemptLock) {
            if (!isCurrentControlSession(session) || validControlAddress(session.device.address) != session.state.address) return null
            val old = session.att
            session.attEpoch++
            val epoch = session.attEpoch
            session.att = null
            if (old != null && BluetoothConnectionManager.attSocket === old.socket) BluetoothConnectionManager.attSocket = null
            attManager.setOnRemoteValueReceived(null)
            session.reportedAttValues.clear()
            session.syncRef?.let { ref -> ATTHandles.entries.forEach { session.sync.forgetActual(ref, attKey(it)) } }
            publishSyncStatus(session)
            wakeSync(session)
            old to epoch
        }
        val old = retired.first
        val epoch = retired.second
        try { old?.socket?.close() } catch (_: Exception) { Log.w(TAG, "Unable to close retired ATT socket") }
        if (old != null && !attManager.stopReaderAndAwaitForSocket(old.socket, 1000L)) return null
        if (attManager.hasRunningReader()) return null
        if (!hasAttAccess()) return null
        val candidate = try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            createBluetoothSocket(adapter, session.device, ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"), 31)
        } catch (_: Exception) {
            Log.w(TAG, "Unable to create replacement ATT socket")
            return null
        }
        val mayConnect = synchronized(connectionAttemptLock) {
            if (!isCurrentControlSession(session) || session.attEpoch != epoch || session.att != null ||
                validControlAddress(session.device.address) != session.state.address) false
            else { session.connectingAttSocket = candidate; true }
        }
        if (!mayConnect) {
            try { candidate.close() } catch (_: Exception) { }
            return null
        }
        val result = CompletableDeferred<Boolean>()
        val connectLive = java.util.concurrent.atomic.AtomicBoolean(true)
        Thread {
            val connected = try { candidate.connect(); true } catch (_: Exception) { false }
            if (!connectLive.get() || !connected) try { candidate.close() } catch (_: Exception) { }
            result.complete(connected && connectLive.get())
        }.apply { name = "ATT-Connect"; isDaemon = true; start() }
        var connected = false
        try {
            connected = withTimeoutOrNull(5000L) { result.await() } == true
        } finally {
            if (!connected) {
                connectLive.set(false)
                try { candidate.close() } catch (_: Exception) { }
            }
            synchronized(connectionAttemptLock) {
                if (session.connectingAttSocket === candidate) session.connectingAttSocket = null
            }
        }
        if (!connected) return null
        val permitted = hasAttAccess()
        val replacement = synchronized(connectionAttemptLock) {
            if (!permitted || !isCurrentControlSession(session) || session.attEpoch != epoch || session.att != null ||
                validControlAddress(session.device.address) != session.state.address || !candidate.isConnected || attManager.hasRunningReader()) null
            else {
                AttSession(candidate, epoch).also {
                    session.att = it
                    BluetoothConnectionManager.attSocket = candidate
                    attachAttReader(session, it)
                }
            }
        }
        if (replacement == null) {
            connectLive.set(false)
            try { candidate.close() } catch (_: Exception) { }
            return null
        }
        return replacement.takeIf { attManager.isSessionUsable(it.socket) }
    }

    /** ATT requests run only on the per-session queue; no socket IO holds the Service lock. */
    private suspend fun performAttWrite(session: ControlSession, request: SettingsSyncPolicy.WriteRequest) {
        val handle = ATTHandles.entries.firstOrNull { attKey(it) == request.key } ?: return
        val stillReserved = synchronized(connectionAttemptLock) {
            val ref = session.syncRef
            isCurrentControlSession(session) && ref != null && request.session === ref &&
                session.sync.snapshot(ref)[request.key]?.let {
                    it.revision == request.revision && it.phase == SettingsSyncPolicy.Phase.RESERVED
                } == true
        }
        if (!stillReserved) return
        var started = false
        var outcome = SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE
        var att: AttSession? = null
        fun startAttempt(): Boolean = synchronized(connectionAttemptLock) {
            val ref = session.syncRef
            if (!isCurrentControlSession(session) || ref == null || request.session !== ref) false
            else if (started) session.sync.snapshot(ref)[request.key]?.let {
                it.revision == request.revision && it.phase == SettingsSyncPolicy.Phase.SENDING
            } == true
            else session.sync.startWrite(ref, request).also { started = it }
        }
        fun requestCurrentLocked(expected: AttSession): Boolean {
            val ref = session.syncRef
            return isCurrentAttSession(session, expected) && ref != null && request.session === ref &&
                session.model?.let { supportsAtt(it, handle) } == true &&
                session.sync.snapshot(ref)[request.key]?.let { it.revision == request.revision &&
                    it.phase == if (started) SettingsSyncPolicy.Phase.SENDING else SettingsSyncPolicy.Phase.RESERVED } == true
        }
        fun requestCurrent(expected: AttSession): Boolean = hasAttAccess() && synchronized(connectionAttemptLock) {
            requestCurrentLocked(expected)
        }
        try {
            att = ensureAttSession(session)
            val active = att
            if (active == null || !requestCurrent(active)) {
                logSyncDiagnostic("att_preparation_failed token=${session.connectionToken} key=${request.key} " +
                    "revision=${request.revision} attempt=${request.attempt} reason=ATT_OR_REQUEST_UNAVAILABLE")
                outcome = SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED
                startAttempt()
                return
            }
            // Scalar writes have no opaque fields to preserve and do not require a read baseline.
            // Composite writes still require a fresh, valid packet from this exact ATT stream.
            var baseline: AttReportOrder.Report? = null
            val wire = if (handle == ATTHandles.LOUD_SOUND_REDUCTION) request.value else {
                val read = attManager.readCharacteristicResultForSocket(active.socket, handle,
                    beforeSend = { requestCurrent(active) })
                if (read !is ATTResult.Value || !isValidAttValue(handle, read.value)) {
                    val reason = when (read) {
                        is ATTResult.Value -> "INVALID_VALUE"
                        is ATTResult.RemoteError -> "REMOTE_ERROR"
                        ATTResult.Ack -> "UNEXPECTED_ACK"
                        ATTResult.TimeoutOrQuarantined -> "TIMEOUT_OR_QUARANTINED"
                        ATTResult.TransportFailure -> "TRANSPORT_FAILURE"
                        ATTResult.Cancelled -> "CANCELLED"
                    }
                    logSyncDiagnostic("att_preparation_failed token=${session.connectionToken} key=${request.key} " +
                        "revision=${request.revision} attempt=${request.attempt} reason=$reason")
                    outcome = if (read is ATTResult.RemoteError) SettingsSyncPolicy.WriteResult.DEVICE_REJECTED
                        else SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED
                    startAttempt()
                    return
                }
                baseline = synchronized(connectionAttemptLock) {
                    val latest = active.reports.latest(handle)
                    if (latest != null) commitAttReport(session, active, handle, latest)
                    if (!requestCurrentLocked(active)) null else latest
                }
                if (baseline == null) {
                    logSyncDiagnostic("att_preparation_failed token=${session.connectionToken} key=${request.key} " +
                        "revision=${request.revision} attempt=${request.attempt} reason=NO_CURRENT_BASELINE")
                    outcome = SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED
                    startAttempt()
                    return
                }
                val desired = parseCompositeAttSettings(handle, request.value.toString(Charsets.UTF_8))
                when (val patched = desired?.let { patchFromDesired(it, baseline.value) }) {
                    is CompositeAttPatchResult.Ready -> patched.bytes
                    else -> null
                }
            }
            if (wire == null) {
                logSyncDiagnostic("att_preparation_failed token=${session.connectionToken} key=${request.key} " +
                    "revision=${request.revision} attempt=${request.attempt} reason=PATCH_UNAVAILABLE")
                outcome = SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED
                startAttempt()
                return
            }
            fun beforeSend(): Boolean = hasAttAccess() && synchronized(connectionAttemptLock) {
                if (!requestCurrentLocked(active) ||
                    (handle != ATTHandles.LOUD_SOUND_REDUCTION && active.reports.latest(handle) !== baseline)) false
                else startAttempt()
            }
            fun onWriteStarted(): Boolean = hasAttAccess() && synchronized(connectionAttemptLock) {
                if (!requestCurrentLocked(active) ||
                    (handle != ATTHandles.LOUD_SOUND_REDUCTION && active.reports.latest(handle) !== baseline)) false
                else {
                    active.reports.markWrite(handle, request.revision, request.sequence)
                    true
                }
            }
            fun freshWriteValue(): ByteArray? = synchronized(connectionAttemptLock) {
                if (!requestCurrentLocked(active)) return@synchronized null
                val report = active.reports.latest(handle) ?: return@synchronized null
                if (!active.reports.isFreshForWrite(handle, report, request.revision, request.sequence)) return@synchronized null
                commitAttReport(session, active, handle, report, request)
                report.value
            }
            fun matchesRequest(actual: ByteArray): Boolean = if (handle == ATTHandles.LOUD_SOUND_REDUCTION) {
                request.value.contentEquals(actual)
            } else {
                parseCompositeAttSettings(handle, request.value.toString(Charsets.UTF_8))?.let { matchesOwned(it, actual) } == true
            }
            val write = attManager.writeCharacteristicResultForSocket(active.socket, handle, wire,
                beforeSend = ::beforeSend, onWriteStarted = ::onWriteStarted)
            outcome = when (write) {
                ATTResult.Ack -> {
                    // The ATT write response proves this write completed, not the device value.
                    val notified = freshWriteValue()
                    val reportsValues = synchronized(connectionAttemptLock) {
                        val ref = session.syncRef
                        ref != null && requestCurrentLocked(active) &&
                            session.sync.hasRemoteObservation(ref, request.key)
                    }
                    if ((notified == null || !matchesRequest(notified)) && reportsValues) {
                        // A previously observed setting requires a fresh report for consistency.
                        // A failed read does not undo the already received write response.
                        try {
                            attManager.readCharacteristicResultForSocket(active.socket, handle,
                                beforeSend = { requestCurrent(active) })
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Log.w(TAG, "Unable to read ATT setting after acknowledged write")
                        }
                    }
                    SettingsSyncPolicy.WriteResult.SENT
                }
                is ATTResult.RemoteError -> SettingsSyncPolicy.WriteResult.DEVICE_REJECTED
                ATTResult.TimeoutOrQuarantined -> SettingsSyncPolicy.WriteResult.UNCONFIRMED
                ATTResult.TransportFailure -> SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE
                ATTResult.Cancelled -> if (attManager.isSessionUsable(active.socket))
                    SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED else SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE
                is ATTResult.Value -> SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE
            }
            if (!started) startAttempt()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            outcome = SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE
            if (!started) startAttempt()
        } finally {
            if (!started) logSyncDiagnostic("att_write_not_started token=${session.connectionToken} key=${request.key} " +
                "revision=${request.revision} attempt=${request.attempt} outcome=$outcome started=$started")
            if (started) synchronized(connectionAttemptLock) {
                // An ATT epoch may be retired after startWrite. Finish the old attempt as
                // transport failure as long as the AACP session and revision are current.
                val ref = session.syncRef
                if (isCurrentControlSession(session) && ref != null && request.session === ref) {
                    val finalOutcome = if (att != null && !isCurrentAttSession(session, att))
                        SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE else outcome
                    logSyncDiagnostic("att_write_finished token=${session.connectionToken} key=${request.key} " +
                        "revision=${request.revision} attempt=${request.attempt} outcome=$finalOutcome started=$started")
                    if ((finalOutcome == SettingsSyncPolicy.WriteResult.SENT || finalOutcome == SettingsSyncPolicy.WriteResult.UNCONFIRMED) &&
                        att != null && isCurrentAttSession(session, att)) {
                        att.reports.latest(handle)?.let { commitAttReport(session, att, handle, it, request) }
                    }
                    session.sync.completeWrite(ref, request, finalOutcome, SystemClock.elapsedRealtime())
                    publishSyncStatus(session)
                    wakeSync(session)
                } else {
                    logSyncDiagnostic("att_write_abandoned token=${session.connectionToken} key=${request.key} " +
                        "revision=${request.revision} attempt=${request.attempt} outcome=$outcome started=$started")
                }
            }
        }
    }

    private fun bootstrapAtt(session: ControlSession, att: AttSession) {
        if (!hasAttAccess()) return
        val handles = synchronized(connectionAttemptLock) {
            val model = session.model
            if (!isCurrentAttSession(session, att) || model == null) return
            ATTHandles.entries.filter { supportsAtt(model, it) }
        }
        fun current() = hasAttAccess() && synchronized(connectionAttemptLock) { isCurrentAttSession(session, att) }
        for (handle in handles) {
            val cccd = when (handle) {
                ATTHandles.TRANSPARENCY -> ATTCCCDHandles.TRANSPARENCY
                ATTHandles.HEARING_AID -> ATTCCCDHandles.HEARING_AID
                ATTHandles.LOUD_SOUND_REDUCTION -> null
            }
            if (cccd != null) {
                attManager.enableNotificationForSocket(att.socket, cccd, beforeSend = ::current)
                if (!attManager.isSessionUsable(att.socket)) return
            }
        }
        for (handle in handles) {
            val result = attManager.readCharacteristicResultForSocket(att.socket, handle, beforeSend = ::current)
            if (result is ATTResult.Value) synchronized(connectionAttemptLock) {
                att.reports.latest(handle)?.let { commitAttReport(session, att, handle, it) }
            }
            if (!attManager.isSessionUsable(att.socket)) return
        }
    }

    private fun copyComposite(value: CompositeAttSettings): CompositeAttSettings = value.copy(
        transparencyEq = value.transparencyEq.toList(), leftHearingLoss = value.leftHearingLoss.toList(),
        rightHearingLoss = value.rightHearingLoss.toList()
    )

    fun getCompositeAttDesired(handle: ATTHandles): CompositeAttSettings = synchronized(connectionAttemptLock) {
        val value = (controlSession?.state ?: lastControlDevice)?.desiredComposites?.get(handle)
            ?: requireNotNull(defaultCompositeAttSettings(handle))
        copyComposite(value)
    }

    /** True means the local value was durably saved and queued, not a device acknowledgement. */
    fun setUserCompositeAttSettings(settings: CompositeAttSettings, expectedConnectionToken: Long?): Boolean {
        val encoded = serializeCompositeAttSettings(settings) ?: return false
        val snapshot = parseCompositeAttSettings(settings.handle, encoded) ?: return false
        return synchronized(connectionAttemptLock) {
            val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized false
            if (expectedConnectionToken == null || session.connectionToken != expectedConnectionToken) return@synchronized false
            try {
                if (!deviceSettingsStore.saveCompositeAttSettings(session.state.address, settings.handle.value, encoded)) return@synchronized false
            } catch (_: Exception) {
                Log.e(TAG, "Unable to save composite ATT settings")
                return@synchronized false
            }
            session.att?.reports?.markEdit(settings.handle)
            session.userEditedKeys.add(attKey(settings.handle))
            session.state.desiredComposites[settings.handle] = snapshot
            if (session.syncRef == null) session.editedBeforeModel.add(attKey(settings.handle))
            if (session.model?.let { supportsAtt(it, settings.handle) } == true) {
                session.syncRef?.let { session.sync.updateDesired(it, attKey(settings.handle), encoded.toByteArray(Charsets.UTF_8), SystemClock.elapsedRealtime()) }
            }
            publishSyncStatus(session)
            wakeSync(session)
            sendBroadcast(Intent("me.kavishdevar.librepods.ATT_SETTINGS_UPDATED").setPackage(packageName))
            true
        }
    }

    /** Legacy composite packets may supply ordinary controls only, never hearing-test ownership. */
    fun setUserAttValue(handle: ATTHandles, data: ByteArray, expectedConnectionToken: Long?): Boolean {
        val value = data.copyOf()
        if (!isValidAttValue(handle, value)) return false
        if (handle != ATTHandles.LOUD_SOUND_REDUCTION) {
            val settings = compositeAttSettingsFromLegacy(handle, value) ?: return false
            return setUserCompositeAttSettings(settings, expectedConnectionToken)
        }
        return synchronized(connectionAttemptLock) {
            val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized false
            if (expectedConnectionToken == null || session.connectionToken != expectedConnectionToken) return@synchronized false
            try {
                if (!deviceSettingsStore.saveExplicitAtt(session.state.address, handle.value, value)) return@synchronized false
            } catch (_: Exception) {
                Log.e(TAG, "Unable to save ATT settings")
                return@synchronized false
            }
            session.att?.reports?.markEdit(handle)
            session.userEditedKeys.add(attKey(handle))
            session.state.desiredAtt[handle] = value
            if (session.syncRef == null) session.editedBeforeModel.add(attKey(handle))
            if (session.model?.let { supportsAtt(it, handle) } == true) {
                session.syncRef?.let { session.sync.updateDesired(it, attKey(handle), value, SystemClock.elapsedRealtime()) }
            }
            publishSyncStatus(session)
            wakeSync(session)
            sendBroadcast(Intent("me.kavishdevar.librepods.ATT_SETTINGS_UPDATED").setPackage(packageName))
            true
        }
    }

    fun getLocalDesiredAttValues(): Map<ATTHandles, ByteArray> = synchronized(connectionAttemptLock) {
        val state = controlSession?.state ?: lastControlDevice ?: return@synchronized emptyMap()
        state.desiredAtt.mapValues { it.value.copyOf() }
    }

    fun getReportedAttValues(): Map<ATTHandles, ByteArray> = synchronized(connectionAttemptLock) {
        controlSession?.takeIf(::isCurrentControlSession)?.reportedAttValues?.mapValues { it.value.copyOf() } ?: emptyMap()
    }

    /** Phone-side behavior follows the app-owned selection, never a device report. */
    private fun applyLocalControlBehavior(state: ControlDeviceState, identifier: Int, value: ByteArray) {
        if (state !== (controlSession?.state ?: lastControlDevice) || identifier != 0x0A) return
        when (value.firstOrNull()?.toInt()) {
            0x01 -> config.earDetectionEnabled = true
            0x02 -> config.earDetectionEnabled = false
        }
    }

    /** True means the selection was saved and queued, not that the device confirmed it. */
    fun setUserControlValue(identifier: ControlCommandIdentifiers, value: ByteArray,
        expectedConnectionToken: Long? = null): Boolean = synchronized(connectionAttemptLock) {
        val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized false
        if (expectedConnectionToken != null && expectedConnectionToken != session.connectionToken) return@synchronized false
        val payload = value.copyOf()
        val id = identifier.value.toInt() and 0xFF
        if (!ControlSettingsPolicy.isValidUserValue(id, payload)) return@synchronized false
        if (session.model?.let { !supportsControlValue(it, id, payload) } == true) return@synchronized false
        val state = session.state
        try {
            if (!deviceSettingsStore.saveExplicitSelection(state.address, id, payload)) return@synchronized false
        } catch (_: Exception) {
            Log.e(TAG, "Unable to save device settings")
            return@synchronized false
        }
        if (state.desiredControls[id]?.contentEquals(payload) != true) session.localRevision++
        state.desiredControls[id] = payload
        session.userEditedKeys.add(controlKey(id))
        if (session.syncRef == null) session.editedBeforeModel.add(controlKey(id))
        applyLocalControlBehavior(state, id, payload)
        if (id == 0x0D || id == 0x34) {
            sendANCBroadcast()
            updateNoiseControlWidget()
        }
        if (session.model?.let { supportsControl(it, id) } == true) {
            session.syncRef?.let { session.sync.updateDesired(it, controlKey(id), payload, SystemClock.elapsedRealtime()) }
            publishSyncStatus(session)
            wakeSync(session)
        }
        true
    }

    fun getLocalDesiredControlValues(): Map<ControlCommandIdentifiers, ByteArray> =
        synchronized(connectionAttemptLock) {
            val state = controlSession?.state ?: lastControlDevice ?: return@synchronized emptyMap()
            state.desiredControls.mapNotNull { (id, value) ->
                ControlCommandIdentifiers.fromByte(id.toByte())?.let { it to value.copyOf() }
            }.toMap()
        }

    fun isControlDeviceReported(identifier: ControlCommandIdentifiers): Boolean = synchronized(connectionAttemptLock) {
        controlSession?.takeIf(::isCurrentControlSession)?.display?.reportedValue(identifier) != null
    }

    /** Only actual values received in the active socket session, never optimistic manager state. */
    fun getReportedControlValues(): Map<ControlCommandIdentifiers, ByteArray> =
        synchronized(connectionAttemptLock) {
            controlSession?.takeIf(::isCurrentControlSession)?.display?.reportedValues() ?: emptyMap()
        }

    /** The display version changes only for a local selection or a new connection. */
    fun getControlDisplaySnapshot(): ControlDisplaySnapshot<ControlCommandIdentifiers>? =
        synchronized(connectionAttemptLock) {
            val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized null
            val desired = session.state.desiredControls.mapNotNull { (id, value) ->
                ControlCommandIdentifiers.fromByte(id.toByte())?.let { it to value.copyOf() }
            }.toMap()
            ControlDisplaySnapshot(ControlDisplayVersion(session.connectionToken, session.localRevision), desired)
        }

    fun withControlConnectionToken(action: (Long?) -> Unit) = synchronized(connectionAttemptLock) {
        action(controlSession?.takeIf(::isCurrentControlSession)?.connectionToken)
    }

    fun withCurrentControlDisplaySnapshot(
        snapshot: ControlDisplaySnapshot<ControlCommandIdentifiers>, apply: () -> Unit
    ): Boolean = synchronized(connectionAttemptLock) {
        val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized false
        if (snapshot.version != ControlDisplayVersion(session.connectionToken, session.localRevision)) return@synchronized false
        apply()
        true
    }

    private fun customEqBytes(eq: CustomEq) =
        byteArrayOf(eq.state.toByte(), eq.low.toByte(), eq.mid.toByte(), eq.high.toByte())

    private fun customEqFromBytes(value: ByteArray) = CustomEq(
        value[0].toInt() and 0xff, value[1].toInt() and 0xff,
        value[2].toInt() and 0xff, value[3].toInt() and 0xff
    )

    /** True means the app-owned EQ was saved and queued, not device confirmation. */
    fun setUserCustomEq(eq: CustomEq, expectedConnectionToken: Long? = null): Boolean = synchronized(connectionAttemptLock) {
        val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized false
        if (expectedConnectionToken != null && expectedConnectionToken != session.connectionToken) return@synchronized false
        if (eq.state !in 1..2 || eq.low !in 0..100 || eq.mid !in 0..100 || eq.high !in 0..100) return@synchronized false
        val payload = customEqBytes(eq)
        try {
            if (!deviceSettingsStore.saveExplicitCustomEq(session.state.address, payload)) return@synchronized false
        } catch (_: Exception) {
            Log.e(TAG, "Unable to save EQ settings")
            return@synchronized false
        }
        session.state.desiredCustomEq = eq.copy()
        session.userEditedKeys.add("eq")
        if (session.syncRef == null) session.editedBeforeModel.add("eq")
        session.syncRef?.let { session.sync.updateDesired(it, "eq", payload, SystemClock.elapsedRealtime()) }
        publishSyncStatus(session)
        wakeSync(session)
        true
    }

    fun getLocalDesiredCustomEq(): CustomEq? = synchronized(connectionAttemptLock) {
        (controlSession?.state ?: lastControlDevice)?.desiredCustomEq?.copy()
    }

    fun getReportedCustomEq(): CustomEq? = synchronized(connectionAttemptLock) {
        controlSession?.takeIf(::isCurrentControlSession)?.reportedCustomEq?.copy()
    }

    private fun supportsControl(model: AirPodsBase, id: Int): Boolean = when (id) {
        0x0D, 0x1A, 0x1B, 0x34 -> Capability.LISTENING_MODE in model.capabilities
        0x23, 0x25 -> Capability.SWIPE_FOR_VOLUME in model.capabilities
        0x26 -> Capability.ADAPTIVE_VOLUME in model.capabilities
        0x28 -> Capability.CONVERSATION_AWARENESS in model.capabilities
        0x2C, 0x2F, 0x33 -> Capability.HEARING_AID in model.capabilities
        0x2E -> Capability.ADAPTIVE_AUDIO in model.capabilities
        0x35 -> Capability.SLEEP_DETECTION in model.capabilities
        0x37 -> Capability.PPE in model.capabilities
        else -> id in ControlSettingsPolicy.SUPPORTED_IDENTIFIERS
    }

    /** Unknown and disconnected models never advertise adaptive audio. */
    fun supportsAdaptiveAudioForCurrentSession(): Boolean = synchronized(connectionAttemptLock) {
        controlSession?.takeIf(::isCurrentControlSession)?.model?.capabilities
            ?.contains(Capability.ADAPTIVE_AUDIO) == true
    }

    private fun supportsControlValue(model: AirPodsBase, id: Int, value: ByteArray): Boolean {
        if (Capability.ADAPTIVE_AUDIO in model.capabilities) return true
        val first = value.firstOrNull()?.toInt()?.and(0xff) ?: return false
        return when (id) {
            0x0D -> first != 4
            0x1A -> (first and 0x08) == 0
            else -> true
        }
    }

    /** Correct only mode values unsupported by this connection's verified model. */
    private fun normalizeAdaptiveSettings(session: ControlSession, model: AirPodsBase): Boolean {
        if (Capability.ADAPTIVE_AUDIO in model.capabilities) return true
        val changes = mutableListOf<Pair<Int, ByteArray>>()
        session.state.desiredControls[0x1A]?.let { original ->
            val mask = (original[0].toInt() and 0xff) and 0xF7
            val validMask = if (Integer.bitCount(mask) >= 2) mask else 0x06
            if (validMask != (original[0].toInt() and 0xff)) {
                changes += 0x1A to original.copyOf().also { it[0] = validMask.toByte() }
            }
        }
        session.state.desiredControls[0x0D]?.let { original ->
            if ((original[0].toInt() and 0xff) == 4) {
                changes += 0x0D to original.copyOf().also { it[0] = 3 }
            }
        }
        var changed = false
        try {
            changes.forEach { (id, value) ->
                check(deviceSettingsStore.saveExplicitSelection(session.state.address, id, value))
                session.state.desiredControls[id] = value
                session.localRevision++
                changed = true
            }
        } catch (_: Exception) {
            Log.e(TAG, "Unable to normalize unsupported adaptive settings")
            if (changed) sendBroadcast(Intent("me.kavishdevar.librepods.CONTROL_SETTINGS_READY").setPackage(packageName))
            return false
        }
        if (changed) {
            sendBroadcast(Intent("me.kavishdevar.librepods.CONTROL_SETTINGS_READY").setPackage(packageName))
            sendANCBroadcast()
        }
        return true
    }

    // Caller holds connectionAttemptLock. Wear reports never create a new desired revision.
    private fun applyWearingWriteGate(session: ControlSession, nowMs: Long) {
        val ref = session.syncRef ?: return
        val allowed = session.wearState.allowsSettingsWrites
        session.sync.setWriteAllowed(ref, "aacp:0d", allowed, nowMs)
        session.sync.setWriteAllowed(ref, "aacp:26", allowed, nowMs)
    }

    /**
     * Device-authoritative adoption: a value actually read from this connection replaces the
     * app-owned desired value, unless the user expressed local intent for the key in this
     * session or the value is not one the app could reproduce itself.
     */
    private fun adoptDeviceValue(session: ControlSession, model: AirPodsBase, key: String, value: ByteArray): Boolean {
        if (key in session.userEditedKeys) return false
        val state = session.state
        val address = state.address
        return when {
            key.startsWith("aacp:") -> {
                val id = key.removePrefix("aacp:").toIntOrNull(16) ?: return false
                if (!ControlSettingsPolicy.isValidUserValue(id, value)) return false
                if (!supportsControlValue(model, id, value)) return false
                val current = state.desiredControls[id]
                if (current != null && matchesSyncValue(key, current, value)) return false
                val saved = try {
                    deviceSettingsStore.saveExplicitSelection(address, id, value)
                } catch (e: Exception) {
                    Log.w(SYNC_LOG_TAG, "Unable to persist adopted $key", e)
                    false
                }
                if (!saved) return false
                state.desiredControls[id] = value.copyOf()
                applyLocalControlBehavior(state, id, value)
                session.localRevision++
                true
            }
            key == "eq" -> {
                if (value.size != 4) return false
                val eq = customEqFromBytes(value)
                if (state.desiredCustomEq == eq) return false
                val saved = try {
                    deviceSettingsStore.saveExplicitCustomEq(address, value)
                } catch (e: Exception) {
                    Log.w(SYNC_LOG_TAG, "Unable to persist adopted $key", e)
                    false
                }
                if (!saved) return false
                state.desiredCustomEq = eq
                true
            }
            key == "name" -> {
                val name = String(value, Charsets.UTF_8)
                if (name.isBlank() || name == state.desiredName) return false
                val saved = try {
                    deviceSettingsStore.saveDesiredName(address, name)
                } catch (e: Exception) {
                    Log.w(SYNC_LOG_TAG, "Unable to persist adopted $key", e)
                    false
                }
                if (!saved) return false
                state.desiredName = name
                config.deviceName = name
                config.airpodsName = name
                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(packageName))
                true
            }
            key == attKey(ATTHandles.LOUD_SOUND_REDUCTION) -> {
                val handle = ATTHandles.LOUD_SOUND_REDUCTION
                if (!isValidAttValue(handle, value)) return false
                if (state.desiredAtt[handle]?.contentEquals(value) == true) return false
                val saved = try {
                    deviceSettingsStore.saveExplicitAtt(address, handle.value, value)
                } catch (e: Exception) {
                    Log.w(SYNC_LOG_TAG, "Unable to persist adopted $key", e)
                    false
                }
                if (!saved) return false
                state.desiredAtt[handle] = value.copyOf()
                true
            }
            key == attKey(ATTHandles.TRANSPARENCY) || key == attKey(ATTHandles.HEARING_AID) -> {
                val handle = if (key == attKey(ATTHandles.TRANSPARENCY)) ATTHandles.TRANSPARENCY
                    else ATTHandles.HEARING_AID
                val candidate = compositeAttSettingsFromDevice(handle, value) ?: return false
                val encoded = serializeCompositeAttSettings(candidate) ?: return false
                // Adopt only a value the local representation reproduces exactly.
                if (!matchesSyncValue(key, encoded.toByteArray(Charsets.UTF_8), value)) return false
                val current = state.desiredComposites[handle]
                if (current != null && matchesOwned(current, value)) return false
                val saved = try {
                    deviceSettingsStore.saveCompositeAttSettings(address, handle.value, encoded)
                } catch (e: Exception) {
                    Log.w(SYNC_LOG_TAG, "Unable to persist adopted $key", e)
                    false
                }
                if (!saved) return false
                state.desiredComposites[handle] = candidate
                true
            }
            else -> false
        }
    }

    /** Reports cached earlier in this connection are device truth; adopt them before the first round. */
    private fun adoptReportedSettings(session: ControlSession, model: AirPodsBase) {
        var adopted = false
        session.display.reportedValues().forEach { (identifier, value) ->
            val key = controlKey(identifier.value.toInt() and 0xff)
            if (adoptDeviceValue(session, model, key, value)) {
                session.adoptedKeys.add(key)
                adopted = true
            }
        }
        session.reportedCustomEq?.let { eq ->
            if (adoptDeviceValue(session, model, "eq", customEqBytes(eq))) {
                session.adoptedKeys.add("eq")
                adopted = true
            }
        }
        session.reportedName?.let { name ->
            if (adoptDeviceValue(session, model, "name", name.toByteArray(Charsets.UTF_8))) {
                session.adoptedKeys.add("name")
                adopted = true
            }
        }
        session.reportedAttValues.forEach { (handle, value) ->
            if (!supportsAtt(model, handle)) return@forEach
            val key = attKey(handle)
            if (adoptDeviceValue(session, model, key, value)) {
                session.adoptedKeys.add(key)
                adopted = true
            }
        }
        if (adopted) {
            logSyncDiagnostic("device_values_adopted token=${session.connectionToken} keys=${session.adoptedKeys.size}")
            updateNoiseControlWidget()
            sendBroadcast(Intent("me.kavishdevar.librepods.CONTROL_SETTINGS_READY").setPackage(packageName))
        }
    }

    /** Encoded local value of an adopted key, in the same form [SettingsSyncPolicy.begin] tracks. */
    private fun adoptedDesiredValue(session: ControlSession, key: String): ByteArray? {
        val state = session.state
        return when {
            key.startsWith("aacp:") ->
                key.removePrefix("aacp:").toIntOrNull(16)?.let { state.desiredControls[it]?.copyOf() }
            key == "eq" -> state.desiredCustomEq?.let { customEqBytes(it) }
            key == "name" -> state.desiredName?.toByteArray(Charsets.UTF_8)
            key == attKey(ATTHandles.LOUD_SOUND_REDUCTION) ->
                state.desiredAtt[ATTHandles.LOUD_SOUND_REDUCTION]?.copyOf()
            key == attKey(ATTHandles.TRANSPARENCY) ->
                state.desiredComposites[ATTHandles.TRANSPARENCY]?.let { serializeCompositeAttSettings(it) }
                    ?.toByteArray(Charsets.UTF_8)
            key == attKey(ATTHandles.HEARING_AID) ->
                state.desiredComposites[ATTHandles.HEARING_AID]?.let { serializeCompositeAttSettings(it) }
                    ?.toByteArray(Charsets.UTF_8)
            else -> null
        }
    }

    /**
     * A live report from this connection is device truth unless a local write for the key is
     * pending or in flight. Once a write is settled the key stops being shielded, so an
     * on-device change made after a write is still adopted.
     */
    private fun observeReportedValue(session: ControlSession, model: AirPodsBase?, key: String, value: ByteArray,
        now: Long) {
        val ref = session.syncRef
        if (ref != null && model != null) {
            val item = session.sync.snapshot(ref)[key]
            val phase = item?.phase
            // A report is device truth unless a local write is pending or in flight.
            val adoptable = phase == null || phase == SettingsSyncPolicy.Phase.CONFIRMED ||
                phase == SettingsSyncPolicy.Phase.SENT_UNVERIFIED || phase == SettingsSyncPolicy.Phase.FAILED ||
                (phase == SettingsSyncPolicy.Phase.PENDING && item?.attempts == 0)
            if (phase == SettingsSyncPolicy.Phase.CONFIRMED || phase == SettingsSyncPolicy.Phase.FAILED) {
                // The local intent is settled; stop shielding this key from device truth.
                session.userEditedKeys.remove(key)
            }
            if (adoptable && adoptDeviceValue(session, model, key, value)) {
                session.adoptedKeys.add(key)
                // Never let the superseded app value be written back over the adopted device value.
                adoptedDesiredValue(session, key)?.let { session.sync.updateDesired(ref, key, it, now) }
                logSyncDiagnostic("device_value_adopted token=${session.connectionToken} key=$key")
                sendBroadcast(Intent("me.kavishdevar.librepods.CONTROL_SETTINGS_READY").setPackage(packageName))
            }
        }
        if (ref != null) session.sync.observeActual(ref, key, value, now)
    }

    /** Build exactly one round from a model reported by this connection, never a cached fallback. */
    private fun installModelSettings(session: ControlSession, model: AirPodsBase) {
        if (!isCurrentControlSession(session) || session.syncRef != null) return
        session.model = model
        if (!normalizeAdaptiveSettings(session, model)) return
        updateNoiseControlWidget()
        // Device authority: values read on this connection replace the app-owned defaults first.
        adoptReportedSettings(session, model)
        val desired = linkedMapOf<String, ByteArray>()
        session.state.desiredControls.forEach { (id, value) ->
            if (supportsControl(model, id)) desired[controlKey(id)] = value.copyOf()
        }
        session.state.desiredCustomEq?.let { desired["eq"] = customEqBytes(it) }
        session.state.desiredName?.let { desired["name"] = it.toByteArray(Charsets.UTF_8) }
        session.state.desiredAtt.forEach { (handle, value) ->
            if (supportsAtt(model, handle)) desired[attKey(handle)] = value.copyOf()
        }
        session.state.desiredComposites.forEach { (handle, value) ->
            if (supportsAtt(model, handle)) serializeCompositeAttSettings(value)?.let {
                desired[attKey(handle)] = it.toByteArray(Charsets.UTF_8)
            }
        }
        // These per-device markers record past inbound reports, not current values.
        val (reportMetadata, knownNameReport) = try {
            Triple(deviceSettingsStore.getReportedIdentifiers(session.state.address),
                deviceSettingsStore.isCustomEqReported(session.state.address),
                deviceSettingsStore.getReportedAttHandles(session.state.address)) to
                deviceSettingsStore.isDeviceNameReported(session.state.address)
        } catch (_: Exception) {
            Log.w(TAG, "Unable to read device report metadata; requiring observations for settings")
            null to false
        }
        val ref = session.sync.begin(session.state.address, session.connectionToken, desired)
        session.syncRef = ref
        val reportedControls = reportMetadata?.first ?: ControlSettingsPolicy.SUPPORTED_IDENTIFIERS
        reportedControls.filter { supportsControl(model, it) }.forEach {
            session.sync.noteRemoteObservation(ref, controlKey(it))
        }
        if (reportMetadata == null || reportMetadata.second) session.sync.noteRemoteObservation(ref, "eq")
        if (reportMetadata == null || knownNameReport) session.sync.noteRemoteObservation(ref, "name")
        ATTHandles.entries.filter { supportsAtt(model, it) &&
            (reportMetadata == null || it.value in reportMetadata.third) }.forEach {
            session.sync.noteRemoteObservation(ref, attKey(it))
        }
        logSyncDiagnostic("model_installed token=${session.connectionToken} items=${desired.size} ready=${session.ready}")
        val now = SystemClock.elapsedRealtime()
        applyWearingWriteGate(session, now)
        fun restoreReport(key: String, value: ByteArray) {
            // An edit made before model discovery supersedes the cached value, but the
            // real inbound report still proves this connection can observe the setting.
            if (key in session.editedBeforeModel) session.sync.noteRemoteObservation(ref, key)
            else session.sync.observeActual(ref, key, value, now)
        }
        session.display.reportedValues().forEach { (id, value) ->
            restoreReport(controlKey(id.value.toInt() and 0xff), value)
        }
        session.reportedCustomEq?.let { restoreReport("eq", customEqBytes(it)) }
        session.reportedName?.let { restoreReport("name", it.toByteArray(Charsets.UTF_8)) }
        session.reportedAttValues.forEach { (handle, value) ->
            if (supportsAtt(model, handle)) restoreReport(attKey(handle), value)
        }
        session.editedBeforeModel.clear()
        session.attQueue.trySend(AttWork.Bootstrap)
        if (session.ready) session.sync.ready(ref, now)
        publishSyncStatus(session)
        wakeSync(session)
    }

    private fun sameSyncStatus(a: Map<String, SettingsSyncPolicy.ItemSnapshot>,
        b: Map<String, SettingsSyncPolicy.ItemSnapshot>): Boolean = a.keys == b.keys && a.all { (key, left) ->
        val right = b[key] ?: return@all false
        val leftActual = left.actual
        val rightActual = right.actual
        left.phase == right.phase && left.revision == right.revision && left.attempts == right.attempts &&
            left.failure == right.failure && left.nextAtMs == right.nextAtMs &&
            left.desired.contentEquals(right.desired) &&
            (leftActual == null && rightActual == null ||
                leftActual != null && rightActual != null && leftActual.contentEquals(rightActual))
    }

    private fun publishSyncStatus(session: ControlSession): Map<String, SettingsSyncPolicy.ItemSnapshot> {
        val status = session.syncRef?.let(session.sync::snapshot) ?: emptyMap()
        val previous = _settingsSyncStatus.value
        if (!sameSyncStatus(previous, status)) {
            status.forEach { (key, item) ->
                val old = previous[key]
                if (old == null || old.revision != item.revision || old.phase != item.phase ||
                    old.attempts != item.attempts || old.failure != item.failure ||
                    (old.actual != null) != (item.actual != null)) {
                    logSyncDiagnostic("status token=${session.connectionToken} key=$key revision=${item.revision} " +
                        "phase=${item.phase} attempts=${item.attempts} failure=${item.failure} hasActual=${item.actual != null}")
                }
            }
            _settingsSyncStatus.value = status
        }
        return status
    }

    private fun wakeSync(session: ControlSession) { session.syncWake.trySend(Unit) }

    private fun sendSyncRequest(session: ControlSession, request: SettingsSyncPolicy.WriteRequest): Boolean {
        val payload = request.value
        return when (request.key) {
            "eq" -> aacpManager.sendCustomEqPacket(customEqFromBytes(payload))
            "name" -> payload.size <= 255 && aacpManager.sendRename(payload.toString(Charsets.UTF_8))
            else -> {
                val id = request.key.removePrefix("aacp:").toIntOrNull(16) ?: return false
                val model = session.model ?: return false
                if (!request.key.startsWith("aacp:") || !supportsControl(model, id)) return false
                aacpManager.sendControlCommand(id.toByte(), payload)
            }
        }
    }

    /** Every successful request for this socket can satisfy the notification readiness gate. */
    private fun requestNotificationsForSocket(socket: BluetoothSocket): Boolean = synchronized(connectionAttemptLock) {
        val session = controlSession?.takeIf { it.socket === socket && isCurrentControlSession(it) }
            ?: return@synchronized false
        val sent = aacpManager.sendNotificationRequest()
        if (sent) {
            session.notificationsRequested = true
            scheduleControlRestore(session)
        }
        sent
    }

    /** The watchdog never waits for connectionAttemptLock and can close only this attempt's socket. */
    private fun sendSyncRequestWithWatchdog(session: ControlSession, request: SettingsSyncPolicy.WriteRequest): Boolean {
        val socket = session.socket
        val attemptGuard = Any()
        var finished = false
        val watchdog = attWorkScope.launch {
            delay(3000L)
            synchronized(attemptGuard) {
                if (!finished && controlSession === session && BluetoothConnectionManager.aacpSocket === socket) {
                    Log.w(TAG, "Closing stalled settings socket for ${request.key}")
                    try { socket.close() } catch (_: Exception) { Log.w(TAG, "Unable to close stalled settings socket") }
                }
            }
        }
        return try { sendSyncRequest(session, request) } finally {
            synchronized(attemptGuard) { finished = true }
            watchdog.cancel()
        }
    }

    private fun scheduleControlRestore(session: ControlSession) {
        if (!isCurrentControlSession(session) || !session.handshakeAcknowledged || !session.featuresAcknowledged ||
            !session.notificationsRequested || session.restoreJob != null) return
        session.restoreStartedAt = SystemClock.elapsedRealtime()
        session.lastControlAt = session.restoreStartedAt
        session.restoreJob = attWorkScope.launch {
            val pending = java.util.ArrayDeque<SettingsSyncPolicy.WriteRequest>()
            while (true) {
                val (request, nextWakeDelay) = synchronized(connectionAttemptLock) {
                    if (!isCurrentControlSession(session)) return@launch
                    val now = SystemClock.elapsedRealtime()
                    if (!session.ready && (now - session.lastControlAt >= 1000L ||
                            now - session.restoreStartedAt >= 6000L)) {
                        session.ready = true
                        session.syncRef?.let { session.sync.ready(it, now) }
                        logSyncDiagnostic("ready token=${session.connectionToken} " +
                            "items=${session.syncRef?.let { session.sync.snapshot(it).size } ?: 0} " +
                            "hasSyncRef=${session.syncRef != null} idleGate=${now - session.lastControlAt >= 1000L} " +
                            "maxGate=${now - session.restoreStartedAt >= 6000L}")
                    }
                    val ref = session.syncRef
                    if (session.ready && ref != null) pending.addAll(session.sync.time(ref, now))
                    val status = publishSyncStatus(session)
                    val deadlines = status.values.mapNotNull { it.nextAtMs }.toMutableList()
                    if (!session.ready) deadlines += minOf(session.lastControlAt + 1000L, session.restoreStartedAt + 6000L)
                    pending.pollFirst() to deadlines.minOrNull()?.let { (it - now).coerceAtLeast(1L) }
                }
                if (request != null) {
                    if (request.key.startsWith("att:")) {
                        val accepted = synchronized(connectionAttemptLock) {
                            isCurrentControlSession(session) && session.attQueue.trySend(AttWork.Write(request)).isSuccess
                        }
                        if (!accepted) synchronized(connectionAttemptLock) {
                            val ref = session.syncRef
                            if (isCurrentControlSession(session) && ref != null && session.sync.startWrite(ref, request)) {
                                session.sync.completeWrite(ref, request, SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, SystemClock.elapsedRealtime())
                                publishSyncStatus(session)
                                wakeSync(session)
                            }
                        }
                        continue
                    }
                    val wrote = synchronized(connectionAttemptLock) {
                        val ref = session.syncRef
                        if (!isCurrentControlSession(session) || ref == null ||
                            !session.sync.startWrite(ref, request)) return@synchronized false
                        logSyncDiagnostic("aacp_write_start token=${session.connectionToken} key=${request.key} " +
                            "revision=${request.revision} attempt=${request.attempt}")
                        val sent = try { sendSyncRequestWithWatchdog(session, request) } catch (_: Exception) {
                            Log.w(TAG, "Settings write failed for ${request.key}")
                            false
                        }
                        logSyncDiagnostic("aacp_write_result token=${session.connectionToken} key=${request.key} " +
                            "revision=${request.revision} attempt=${request.attempt} transportSent=$sent")
                        val result = if (sent) SettingsSyncPolicy.WriteResult.SENT
                            else SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE
                        session.sync.completeWrite(ref, request, result, SystemClock.elapsedRealtime())
                        publishSyncStatus(session)
                        true
                    }
                    if (wrote) delay(40)
                } else if (nextWakeDelay != null) {
                    withTimeoutOrNull(nextWakeDelay) { session.syncWake.receiveCatching() }
                } else {
                    session.syncWake.receiveCatching()
                }
            }
        }
    }

    private fun withCurrentControlSocket(socket: BluetoothSocket, action: () -> Unit): Boolean =
        synchronized(connectionAttemptLock) {
            val session = controlSession?.takeIf { it.socket === socket && isCurrentControlSession(it) }
                ?: return@synchronized false
            action()
            controlSession === session
        }

    private fun controlLongPressMask(): Int? = synchronized(connectionAttemptLock) {
        val state = controlSession?.state ?: lastControlDevice ?: return@synchronized null
        state.desiredControls[0x1A]?.getOrNull(0)?.toInt()?.and(0xff)
    }

    private fun controlAllowsOffMode(): Boolean = synchronized(connectionAttemptLock) {
        val state = controlSession?.state ?: lastControlDevice ?: return@synchronized false
        state.desiredControls[0x34]?.getOrNull(0) == 0x01.toByte()
    }

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationAwarenessPauseMusic: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        var bleOnlyMode: Boolean = false,

        // AirPods state-based takeover
        var takeoverWhenDisconnected: Boolean = true,
        var takeoverWhenIdle: Boolean = true,
        var takeoverWhenMusic: Boolean = false,
        var takeoverWhenCall: Boolean = true,

        // Phone state-based takeover
        var takeoverWhenRingingCall: Boolean = true,
        var takeoverWhenMediaStart: Boolean = true,

        var leftSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,

        var leftDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,

        var leftTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,

        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,

        var cameraAction: StemPressType? = null,

        // AirPods device information
        var airpodsName: String = "",
        var airpodsModelNumber: String = "",
        var airpodsManufacturer: String = "",
        var airpodsSerialNumber: String = "",
        var airpodsLeftSerialNumber: String = "",
        var airpodsRightSerialNumber: String = "",
        var airpodsVersion1: String = "",
        var airpodsVersion2: String = "",
        var airpodsVersion3: String = "",
        var airpodsHardwareRevision: String = "",
        var airpodsUpdaterIdentifier: String = "",

        // phone's mac, needed for tipi
        var selfMacAddress: String = ""
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferencesLogs: SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: TelephonyCallback
    private val maxLogEntries = 1000
    private val inMemoryLogs = mutableSetOf<String>()

    private var handleIncomingCallOnceConnected = false

    lateinit var bleManager: BLEManager

    companion object {
        init {
            System.loadLibrary("bluetooth_socket")
        }
    }

    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {
        @SuppressLint("NewApi")
        override fun onDeviceStatusChanged(
            device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?
        ) {
            if (device.connectionState == "Disconnected" && BluetoothConnectionManager.aacpSocket?.isConnected != true) { // should never happen unless android messes up and sends us a stale broadcast
                Log.d(TAG, "Seems no device has taken over, we will.")
                requestStartupDiscovery(DiscoveryReason.BLE_STATUS)
            }
            Log.d(TAG, "Device status changed")
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
        }

        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "New address detected")
        }

        override fun onLidStateChanged(
            lidOpen: Boolean,
        ) {
            if (lidOpen) {
                Log.d(TAG, "Lid opened")
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
                val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
                val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
                val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
                val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
                val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
                val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

                batteryNotification.setBatteryDirect(
                    leftLevel = leftLevel,
                    leftCharging = leftCharging == true,
                    rightLevel = rightLevel,
                    rightCharging = rightCharging == true,
                    caseLevel = caseLevel,
                    caseCharging = caseCharging == true
                )
                sendBatteryBroadcast()
            } else {
                Log.d(TAG, "Lid closed")
            }
        }

        override fun onEarStateChanged(
            device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean
        ) {
            Log.d(TAG, "Ear state changed - Left: $leftInEar, Right: $rightInEar")

            // In BLE-only mode, ear detection is purely based on BLE data
            if (config.bleOnlyMode) {
                Log.d(TAG, "BLE-only mode: ear detection from BLE data")
            }
        }

        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
            Log.d(TAG, "Battery changed")
        }

        override fun onDeviceDisappeared() {
            Log.d(TAG, "All disappeared")
            updateNotificationContent(
                false
            )
        }
    }

    fun isBluetoothSocketExempted(): Boolean {
        return try {
            BluetoothSocket::class.java.declaredConstructors // will throw if still blocked
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag", "HardwareIds")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lib exempt worked: ${isBluetoothSocketExempted()}")

        sharedPreferencesLogs = getSharedPreferences("packet_logs", MODE_PRIVATE)

        inMemoryLogs.addAll(
            sharedPreferencesLogs.getStringSet(packetLogKey, emptySet()) ?: emptySet()
        )
        _packetLogsFlow.value = inMemoryLogs.toSet()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        // Capture provenance before discovery/connection code can replace the global address.
        startupLegacyAddress = validControlAddress(sharedPreferences.getString("mac_address", null))
        startupSavedDeviceName = startupLegacyAddress?.let { address ->
            try { deviceSettingsStore.getDesiredName(address)?.let { address to it } }
            catch (_: Exception) { Log.e(TAG, "Unable to read saved device name"); null }
        }
        startupLegacyMask = try {
            (if (sharedPreferences.contains("long_press_byte")) sharedPreferences.getInt("long_press_byte", 0) else null)
                ?.takeIf { it in 0..15 && Integer.bitCount(it) >= 2 }
        } catch (_: ClassCastException) { null }
        initializeConfig()

        aacpManager = AACPManager()
        initializeAACPManagerCallback()

        attManager = ATTManagerv2()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        localMac = config.selfMacAddress
        if (localMac.isEmpty()) {
            if (checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val bluetoothAdapter = bluetoothManager.adapter
                localMac = bluetoothAdapter.address
            } else {
                localMac = try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "settings get secure bluetooth_address")
                    )

                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        process.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error retrieving local MAC address: ${e.message}. We probably aren't rooted."
                    )
                    ""
                }
            }
            config.selfMacAddress = localMac
            sharedPreferences.edit {
                putString("self_mac_address", localMac)
            }
        }

        ServiceManager.setService(this)
        startForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initGestureDetector()
        } else {
            gestureDetector = null
            config.headGestures = false
            sharedPreferences.edit { putBoolean("head_gestures", false) }
            Log.d(TAG, "Head gestures disabled as device is running Android 9 or below")
        }

        bleManager = BLEManager(this)
        bleManager.setAirPodsStatusListener(bleStatusListener)

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        with(sharedPreferences) {
            edit {
                if (!contains("conversation_awareness_pause_music")) putBoolean(
                    "conversation_awareness_pause_music", false
                )
                if (!contains("show_phone_battery_in_widget")) putBoolean(
                    "show_phone_battery_in_widget", true
                )
                if (!contains("relative_conversation_awareness_volume")) putBoolean(
                    "relative_conversation_awareness_volume", true
                )
                if (!contains("head_gestures")) putBoolean("head_gestures", true)
                if (!contains("disconnect_when_not_wearing")) putBoolean(
                    "disconnect_when_not_wearing", false
                )

                // AirPods state-based takeover
                if (!contains("takeover_when_disconnected")) putBoolean(
                    "takeover_when_disconnected", false
                )
                if (!contains("takeover_when_idle")) putBoolean("takeover_when_idle", false)
                if (!contains("takeover_when_music")) putBoolean("takeover_when_music", false)
                if (!contains("takeover_when_call")) putBoolean("takeover_when_call", false)

                // Phone state-based takeover
                if (!contains("takeover_when_ringing_call")) putBoolean(
                    "takeover_when_ringing_call", false
                )
                if (!contains("takeover_when_media_start")) putBoolean(
                    "takeover_when_media_start", false
                )

                if (!contains("conversation_awareness_volume")) putInt(
                    "conversation_awareness_volume", 43
                )

                if (!contains("qs_click_behavior")) putString("qs_click_behavior", "cycle")

                if (!contains("left_single_press_action")) putString(
                    "left_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("right_single_press_action")) putString(
                    "right_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("left_double_press_action")) putString(
                    "left_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("right_double_press_action")) putString(
                    "right_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("left_triple_press_action")) putString(
                    "left_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("right_triple_press_action")) putString(
                    "right_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("left_long_press_action")) putString(
                    "left_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("right_long_press_action")) putString(
                    "right_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("camera_action")) putString("camera_action", "SINGLE_PRESS")

            }
        }

        initializeConfig()

        externalBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    synchronized(connectionAttemptLock) {
                        val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized
                        if (intent.hasExtra("control_connection_token") &&
                            intent.getLongExtra("control_connection_token", -1L) != session.connectionToken) return@synchronized
                        val mode = if (intent.hasExtra("mode")) {
                            intent.getIntExtra("mode", -1).takeIf { it in 1..4 } ?: return@synchronized
                        } else {
                            val currentMode = session.state.desiredControls[0x0D]?.firstOrNull()
                                ?.toInt()?.and(0xff)?.takeIf { it in 1..4 } ?: return@synchronized
                            val configByte = session.state.desiredControls[0x1A]?.firstOrNull()
                                ?.toInt()?.and(0xff) ?: return@synchronized
                            val allowOffMode = session.state.desiredControls[0x34]?.firstOrNull() == 0x01.toByte()
                            getNextMode(currentMode, configByte, allowOffMode)
                        }
                        setUserControlValue(ControlCommandIdentifiers.LISTENING_MODE,
                            byteArrayOf(mode.toByte()), session.connectionToken)
                    }
                } else  if (intent?.action == "me.kavishdevar.librepods.CONVO_DETECT") {
                    if (intent.hasExtra("enabled")) {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        setUserControlValue(
                            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                            if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalBroadcastReceiver, externalBroadcastFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                externalBroadcastReceiver, externalBroadcastFilter
            )
        }
        val audioManager = this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        MediaController.initialize(
            audioManager, this@AirPodsService.getSharedPreferences(
                "settings", MODE_PRIVATE
            )
        )
//        Log.d(TAG, "Initializing CrossDevice")
//        CoroutineScope(Dispatchers.IO).launch {
//            CrossDevice.init(this@AirPodsService)
//            Log.d(TAG, "CrossDevice initialized")
//        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(Dispatchers.IO).launch {
                        if (leAvailableForAudio) runBlocking {
                            takeOver("call")
                        }
                        if (config.headGestures) {
                            handleIncomingCall()
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(
                        if (leAvailableForAudio) CoroutineScope(
                            Dispatchers.IO
                        ).launch {
                            takeOver("call")
                        }
                        isInCall = true
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        isInCall = false
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(mainExecutor, phoneStateListener)
        }

        if (config.showPhoneBatteryInWidget) {
            widgetMobileBatteryEnabled = true
            val batteryChangedIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            batteryChangedIntentFilter.addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            batteryChangedIntentReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent) {
                    if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                        ServiceManager.getService()?.updateBattery()
                    } else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                        try {
                            this@AirPodsService.unregisterReceiver(this)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            batteryChangedReceiverRegistered = false
                            batteryChangedIntentReceiver = null
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    batteryChangedIntentReceiver, batteryChangedIntentFilter, RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                    batteryChangedIntentReceiver, batteryChangedIntentFilter
                )
            }
            batteryChangedReceiverRegistered = true
        }
        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
            addAction("android.bluetooth.device.action.UUID")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    requestStartupDiscovery(DiscoveryReason.BROADCAST)
                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    synchronized(connectionAttemptLock) {
                        if (intent.hasExtra("control_connection_token") &&
                            intent.getLongExtra("control_connection_token", -1L) != controlConnectionToken) return
                        endControlSession()
                        connectionIslandGate.onDisconnected()
                        device = null
//                    isConnectedLocally = false
                        popupShown = false
                        updateNotificationContent(false)
                        aacpManager.disconnected()
                        BluetoothConnectionManager.aacpSocket = null
                        BluetoothConnectionManager.attSocket = null
                    }
                }
            }
        }
        showIslandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(
                        this@AirPodsService,
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                                batteryNotification.getBattery()
                                    .find { it.component == BatteryComponent.RIGHT }?.level!!
                            )
                    )
                } else if (intent?.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        this@AirPodsService.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        showIslandReceiverRegistered = false
                        showIslandReceiver = null
                    }
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                showIslandReceiver, showIslandIntentFilter
            )
        }
        showIslandReceiverRegistered = true

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                connectionReceiver, deviceIntentFilter
            )
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        requestStartupDiscovery(DiscoveryReason.ON_CREATE)

//        if (!isConnectedLocally && !CrossDevice.isAvailable) {
//            clearPacketLogs()
//        }

        CoroutineScope(Dispatchers.IO).launch {
            bleManager.startScanning()
        }
    }

    @Suppress("unused")
    fun cameraOpened() {
        Log.d(TAG, "Camera opened, gonna handle stem presses and take action if visible")
        cameraActive = true
        setupStemActions()
    }

    @Suppress("unused")
    fun cameraClosed() {
        cameraActive = false
        setupStemActions()
    }

    fun isCustomAction(
        action: StemAction?, default: StemAction?
    ): Boolean {
        return action != default
    }

    fun setupStemActions() {
        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized =
            isCustomAction(config.leftSinglePressAction, singlePressDefault) || isCustomAction(
                config.rightSinglePressAction, singlePressDefault
            ) || (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized =
            isCustomAction(config.leftDoublePressAction, doublePressDefault) || isCustomAction(
                config.rightDoublePressAction, doublePressDefault
            )
        val triplePressCustomized =
            isCustomAction(config.leftTriplePressAction, triplePressDefault) || isCustomAction(
                config.rightTriplePressAction, triplePressDefault
            )
        val longPressCustomized = isCustomAction(
            config.leftLongPressAction, longPressDefault
        ) || isCustomAction(
            config.rightLongPressAction, longPressDefault
        ) || (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)
        Log.d(
            TAG,
            "Setting up stem actions: Single Press Customized: $singlePressCustomized, Double Press Customized: $doublePressCustomized, Triple Press Customized: $triplePressCustomized, Long Press Customized: $longPressCustomized"
        )
        aacpManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
    }

    @ExperimentalEncodingApi
    private fun initializeAACPManagerCallback() {
        aacpManager.setPacketCallback(object : AACPManager.PacketCallback {
            override fun onHandshakeAcknowledged() {
                val session = controlSession?.takeIf(::isCurrentControlSession) ?: return
                session.handshakeAcknowledged = true
                val featureFlagsSent = aacpManager.sendSetFeatureFlagsPacket()
                Log.d(TAG, "Handshake ACK received; feature flags sent=$featureFlagsSent")
                scheduleControlRestore(session)
            }

            override fun onFeatureFlagsAcknowledged() {
                val session = controlSession?.takeIf(::isCurrentControlSession) ?: return
                session.featuresAcknowledged = true
                val notificationsSent = requestNotificationsForSocket(session.socket)
                val proximityKeysSent = aacpManager.sendRequestProximityKeys(
                    (AACPManager.Companion.ProximityKeyType.IRK.value +
                        AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte()
                )
                Log.d(
                    TAG,
                    "Feature flags ACK received; notifications sent=$notificationsSent, proximity keys sent=$proximityKeysSent"
                )
                scheduleControlRestore(session)
            }

            override fun onReceivedControlValue(identifier: ControlCommandIdentifiers, value: ByteArray) {
                val session = controlSession?.takeIf(::isCurrentControlSession) ?: return
                val id = identifier.value.toInt() and 0xff
                val key = controlKey(id)
                logSyncDiagnostic("aacp_report token=${session.connectionToken} key=$key " +
                    "tracked=${session.syncRef?.let { key in session.sync.snapshot(it) } == true} acceptedSize=${value.size in 1..4}")
                if (value.size !in 1..4) return
                val now = SystemClock.elapsedRealtime()
                session.display.recordReport(identifier, value)
                session.lastControlAt = now
                observeReportedValue(session, session.model, controlKey(id), value, now)
                publishSyncStatus(session)
                wakeSync(session)
                if (id in ControlSettingsPolicy.SUPPORTED_IDENTIFIERS) {
                    try { deviceSettingsStore.recordDeviceReport(session.state.address, id) }
                    catch (_: Exception) { Log.e(TAG, "Unable to record device report metadata") }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
                batteryNotification.setBattery(batteryInfo)
                val validEarbudBatteries = if (batteryInfo.size == 22) {
                    batteryNotification.getBattery().filter { battery ->
                        (battery.component == BatteryComponent.LEFT || battery.component == BatteryComponent.RIGHT) &&
                            battery.level in 0..100 &&
                            battery.status in setOf(
                                BatteryStatus.CHARGING,
                                BatteryStatus.NOT_CHARGING,
                                BatteryStatus.OPTIMIZED_CHARGING
                            )
                    }
                } else {
                    emptyList()
                }
                if (connectionIslandGate.consumeIfReady(validEarbudBatteries.isNotEmpty())) {
                    showIsland(
                        this@AirPodsService,
                        validEarbudBatteries.minOf { it.level },
                        IslandType.CONNECTED,
                        connectionTransition = true
                    )
                    showPopup(this@AirPodsService, getCurrentDeviceName())
                }
                sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                    putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
                    setPackage(packageName)
                })
                updateBattery()
                updateNotificationContent(
                    true,
                    getCurrentDeviceName(),
                    batteryNotification.getBattery()
                )
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

                if (batteryNotification.getBattery()[0].status == BatteryStatus.CHARGING && batteryNotification.getBattery()[1].status == BatteryStatus.CHARGING) {
                    disconnectAudio(this@AirPodsService, device)
                } else {
                    connectAudio(this@AirPodsService, device)
                }
            }

            override fun onEarDetectionReceived(earDetection: ByteArray) {
                if (earDetection.size < 8) return
                parseEarWearingState(earDetection)?.let { state ->
                    synchronized(connectionAttemptLock) {
                        val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized
                        val changed = session.wearState != state
                        session.wearState = state
                        logSyncDiagnostic("wear_state token=${session.connectionToken} source=REPORT state=$state")
                        if (changed) {
                            applyWearingWriteGate(session, SystemClock.elapsedRealtime())
                            publishSyncStatus(session)
                            wakeSync(session)
                        }
                    }
                }
                sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                    val list = earDetectionNotification.status
                    val bytes = ByteArray(2)
                    bytes[0] = list[0]
                    bytes[1] = list[1]
                    putExtra("data", bytes)
                }.apply {
                    setPackage(packageName)
                })
                Log.d(
                    "AirPodsParser",
                    "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                )
                processEarDetectionChange(earDetection)
            }

            override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
                conversationAwarenessNotification.setData(conversationAwareness)
                if (BuildConfig.SETTINGS_VERIFICATION_CONTROLS) {
                    controlSession?.takeIf(::isCurrentControlSession)?.let { session ->
                        val event = when (conversationAwarenessNotification.status.toInt()) {
                            1, 2 -> ConversationAwarenessEvent.START
                            6, 8, 9 -> ConversationAwarenessEvent.STOP
                            else -> ConversationAwarenessEvent.OTHER
                        }
                        logSyncDiagnostic("ca_event token=${session.connectionToken} event=$event")
                    }
                }
                sendBroadcast(Intent(AirPodsNotifications.CA_DATA).apply {
                    putExtra("data", conversationAwarenessNotification.status)
                }.apply {
                    setPackage(packageName)
                })

                if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                    MediaController.startSpeaking()
                } else if (conversationAwarenessNotification.status == 6.toByte() ||conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                    MediaController.stopSpeaking()
                }

                Log.d(
                    "AirPodsParser",
                    "Conversation Awareness: ${conversationAwarenessNotification.status}"
                )
            }

            override fun onControlCommandReceived(controlCommand: ByteArray) {
                val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
                if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                    ancNotification.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }
                        ?.get(0) ?: 0x00.toByte()))
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                }
            }

            override fun onOwnershipChangeReceived(owns: Boolean) {
                if (!owns) {
                    MediaController.recentlyLostOwnership = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        MediaController.recentlyLostOwnership = false
                    }, 3000)
                    Log.d(TAG, "ownership lost")
                    MediaController.sendPause()
                    MediaController.pausedForOtherDevice = true
                    otherDeviceTookOver = true
                    disconnectAudio(
                        this@AirPodsService, device
                    )
                }
            }

            override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
                // TODO: Show a reverse button, but that's a lot of effort -- i'd have to change the UI too, which i hate doing, and handle other device's reverses too, and disconnect audio etc... so for now, just pause the audio and show the island without asking to reverse.
                // handling reverse is a problem because we'd have to disconnect the audio, but there's no option connect audio again natively, so notification would have to be changed. I wish there was a way to just "change the audio output device".
                // (20 minutes later) i've done it nonetheless :]
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                Log.d(
                    TAG,
                    "other device has hijacked the connection, reasonReverseTapped: $reasonReverseTapped"
                )
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    byteArrayOf(0x00)
                )
                otherDeviceTookOver = true
                disconnectAudio(
                    this@AirPodsService, device
                )
                if (reasonReverseTapped) {
                    Log.d(TAG, "reverse tapped, disconnecting audio")
                    disconnectedBecauseReversed = true
                    disconnectAudio(this@AirPodsService, device)
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = true,
                        otherDeviceName = senderName
                    )
                }
                if (!aacpManager.owns) {
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = reasonReverseTapped,
                        otherDeviceName = senderName
                    )
                }
                MediaController.sendPause()
            }

            override fun onShowNearbyUI(sender: String) {
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    ),
                    IslandType.MOVED_TO_OTHER_DEVICE,
                    reversed = false,
                    otherDeviceName = senderName
                )
            }

            override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
                controlSession?.takeIf(::isCurrentControlSession)?.let { session ->
                    val now = SystemClock.elapsedRealtime()
                    session.lastControlAt = now
                    if (deviceInformation.name.isNotBlank()) {
                        session.reportedName = deviceInformation.name
                        if (!session.persistedNameReport) {
                            val recorded = try {
                                deviceSettingsStore.recordDeviceNameReport(session.state.address)
                            } catch (_: Exception) {
                                false
                            }
                            if (recorded) session.persistedNameReport = true
                            else Log.e(TAG, "Unable to record device name report metadata")
                        }
                        if (session.state.desiredName == null) {
                            try {
                                if (deviceSettingsStore.saveDesiredName(session.state.address, deviceInformation.name)) {
                                    session.state.desiredName = deviceInformation.name
                                    config.deviceName = deviceInformation.name
                                    config.airpodsName = deviceInformation.name
                                }
                            } catch (_: Exception) { Log.e(TAG, "Unable to seed device name") }
                        }
                    }
                    AirPodsModels.getModelByModelNumber(deviceInformation.modelNumber)?.let {
                        installModelSettings(session, it)
                    }
                    // A first name report can seed a previously missing name. Add it once,
                    // then observe the same report while the connection lock is held.
                    if (deviceInformation.name.isNotBlank()) {
                        session.syncRef?.let { ref ->
                            if (session.sync.snapshot(ref)["name"] == null && session.state.desiredName != null) {
                                session.sync.updateDesired(ref, "name", session.state.desiredName!!.toByteArray(Charsets.UTF_8), now)
                            }
                        }
                        observeReportedValue(session, session.model, "name",
                            deviceInformation.name.toByteArray(Charsets.UTF_8), now)
                    }
                    publishSyncStatus(session)
                    wakeSync(session)
                    session.informationReceived = true
                }
                Log.d(
                    "AirPodsParser",
                    "Device Information: modelNumber: ${deviceInformation.modelNumber}, manufacturer: ${deviceInformation.manufacturer}, serialNumber: ${deviceInformation.serialNumber}, version1: ${deviceInformation.version1}, version2: ${deviceInformation.version2}, hardwareRevision: ${deviceInformation.hardwareRevision}, updaterIdentifier: ${deviceInformation.updaterIdentifier}, leftSerialNumber: ${deviceInformation.leftSerialNumber}, rightSerialNumber: ${deviceInformation.rightSerialNumber}, version3: ${deviceInformation.version3}"
                )
                // Store in SharedPreferences
                sharedPreferences.edit {
                    putString("airpods_model_number", deviceInformation.modelNumber)
                    putString("airpods_manufacturer", deviceInformation.manufacturer)
                    putString("airpods_serial_number", deviceInformation.serialNumber)
                    putString("airpods_left_serial_number", deviceInformation.leftSerialNumber)
                    putString("airpods_right_serial_number", deviceInformation.rightSerialNumber)
                    putString("airpods_version1", deviceInformation.version1)
                    putString("airpods_version2", deviceInformation.version2)
                    putString("airpods_version3", deviceInformation.version3)
                    putString("airpods_hardware_revision", deviceInformation.hardwareRevision)
                    putString("airpods_updater_identifier", deviceInformation.updaterIdentifier)
                }
                // Update config
                config.airpodsModelNumber = deviceInformation.modelNumber
                config.airpodsManufacturer = deviceInformation.manufacturer
                config.airpodsSerialNumber = deviceInformation.serialNumber
                config.airpodsLeftSerialNumber = deviceInformation.leftSerialNumber
                config.airpodsRightSerialNumber = deviceInformation.rightSerialNumber
                config.airpodsVersion1 = deviceInformation.version1
                config.airpodsVersion2 = deviceInformation.version2
                config.airpodsVersion3 = deviceInformation.version3
                config.airpodsHardwareRevision = deviceInformation.hardwareRevision
                config.airpodsUpdaterIdentifier = deviceInformation.updaterIdentifier

                val model = AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                if (model != null) {
                    airpodsInstance = AirPodsInstance(
                        name = getCurrentDeviceName(),
                        model = model,
                        actualModelNumber = config.airpodsModelNumber,
                        serialNumber = config.airpodsSerialNumber,
                        leftSerialNumber = config.airpodsLeftSerialNumber,
                        rightSerialNumber = config.airpodsRightSerialNumber,
                        version1 = config.airpodsVersion1,
                        version2 = config.airpodsVersion2,
                        version3 = config.airpodsVersion3,
                    )
                    if (device != null) setMetadatas(device!!)
                }
                sendBroadcast(
                    Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(
                        packageName
                    )
                )
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(headTracking: ByteArray) {
                if (isHeadTrackingActive) {
                    HeadTracking.processPacket(headTracking)
                    processHeadTrackingData(headTracking)
                }
            }

            override fun onProximityKeysReceived(proximityKeys: ByteArray) {
                val keys = aacpManager.parseProximityKeysResponse(proximityKeys)
                Log.d("AirPodsParser", "Proximity keys: $keys")
                sharedPreferences.edit {
                    for (key in keys) {
                        Log.d("AirPodsParser", "Proximity key: ${key.key.name} = ${key.value}")
                        putString(key.key.name, Base64.encode(key.value))
                    }
                }
            }

            override fun onStemPressReceived(stemPress: ByteArray) {

                val (stemPressType, bud) = aacpManager.parseStemPressResponse(stemPress)

                Log.d(
                    "AirPodsParser",
                    "Stem press received: $stemPressType on $bud, cameraActive: $cameraActive, cameraAction: ${config.cameraAction}"
                )
                if (cameraActive && config.cameraAction != null && stemPressType == config.cameraAction) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 27"))
                } else {
                    val action = getActionFor(bud, stemPressType)
                    Log.d("AirPodsParser", "$bud $stemPressType action: $action")
                    action?.let { executeStemAction(it) }
                }
            }

            override fun onAudioSourceReceived(audioSource: ByteArray) {
                Log.d(
                    "AirPodsParser",
                    "Audio source changed mac: ${aacpManager.audioSource?.mac}, type: ${aacpManager.audioSource?.type?.name}"
                )
                if (localMac!="" && (aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE && aacpManager.audioSource?.mac != localMac)) {
                    Log.d(
                        "AirPodsParser",
                        "Audio source is another device, better to give up aacp control"
                    )
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                        byteArrayOf(0x00)
                    )
                    // this also means that the other device has start playing the audio, and if that's true, we can again start listening for audio config changes
//                    Log.d(TAG, "Another device started playing audio, listening for audio config changes again")
//                    MediaController.pausedForOtherDevice = false
// future me: what the heck is this? this just means it will not be taking over again if audio source doesn't change???
                }
            }

            override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
                for (device in connectedDevices) {
                    Log.d(
                        "AirPodsParser",
                        "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld =
                        aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = newDevice.mac != localMac
                    notInOld && notLocal
                }

                for (device in newDevices) {
                    Log.d(
                        "AirPodsParser",
                        "New connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                    Log.d(
                        TAG,
                        "Sending new Tipi packet for device ${device.mac}, and sending media info to the device"
                    )
                    aacpManager.sendMediaInformationNewDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                    aacpManager.sendAddTiPiDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                }
            }

            override fun onHeadphoneAccommodationReceived(eqData: FloatArray) {
                sendBroadcast(
                    Intent(AirPodsNotifications.EQ_DATA).putExtra("eqData", eqData).apply {
                        setPackage(packageName)
                    })
            }

            override fun onCustomEqReceived(customEq: CustomEq) {
                // TODO
            }

            override fun onReceivedCustomEqValue(customEq: CustomEq) {
                val session = controlSession?.takeIf(::isCurrentControlSession) ?: return
                session.reportedCustomEq = customEq.copy()
                val now = SystemClock.elapsedRealtime()
                session.lastControlAt = now
                observeReportedValue(session, session.model, "eq", customEqBytes(customEq), now)
                publishSyncStatus(session)
                wakeSync(session)
                try { deviceSettingsStore.recordDeviceCustomEqReport(session.state.address) }
                catch (_: Exception) { Log.e(TAG, "Unable to record EQ report metadata") }
            }

            override fun onCapabilitiesReceived(capabilities: List<Capability>) {
                // TODO
            }

            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d(
                    "AACPManager",
                    "Unknown packet received: ${packet.joinToString(" ") { "%02X".format(it) }}"
                )
            }
        })
    }

    private fun getActionFor(
        bud: AACPManager.Companion.StemPressBudType, type: StemPressType
    ): StemAction? {
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftSinglePressAction else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftDoublePressAction else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftTriplePressAction else config.rightTriplePressAction
            StemPressType.LONG_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftLongPressAction else config.rightLongPressAction
        }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                Log.d(
                    "AirPodsParser", "Default single press action: Play/Pause, not taking action."
                )
            }

            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.DIGITAL_ASSISTANT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Log.w(
                        "AirPodsParser",
                        "Digital Assistant action is not supported on this Android version."
                    )
                }
            }

            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                Log.d("AirPodsParser", "Cycling noise control modes")
                val token = synchronized(connectionAttemptLock) {
                    controlSession?.takeIf(::isCurrentControlSession)?.connectionToken
                } ?: return
                sendBroadcast(Intent("me.kavishdevar.librepods.SET_ANC_MODE").apply {
                    putExtra("control_connection_token", token)
                    setPackage(packageName)
                })
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        var inEar: Boolean
        val inEarData = listOf(
            earDetectionNotification.status[0] == 0x00.toByte(),
            earDetectionNotification.status[1] == 0x00.toByte()
        )
        var justEnabledA2dp = false
        earDetectionNotification.setStatus(earDetection)
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

            val newInEarData = listOf(
                data[0] == 0x00.toByte(), data[1] == 0x00.toByte()
            )

            if (connectionIslandGate.shouldCloseIslandOnEarDetection(
                    leftInEar = newInEarData[0],
                    rightInEar = newInEarData[1]
                ) && islandWindow?.isVisible == true
            ) {
                islandWindow?.close()
            }

            if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
                connectAudio(this@AirPodsService, device)
                justEnabledA2dp = true
                registerA2dpConnectionReceiver()
                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
            } else if (newInEarData == listOf(false, false)) {
                MediaController.sendPause(force = true)
                if (config.disconnectWhenNotWearing) {
                    disconnectAudio(this@AirPodsService, device)
                }
            }
            val wasNone = inEarData == listOf(false, false)
            val nowSingle = newInEarData.count { it } == 1

            if (wasNone && nowSingle) {
                MediaController.sendPlay()
                MediaController.iPausedTheMedia = false
                return
            }

            if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User put in both AirPods from just one.")
                MediaController.userPlayedTheMedia = false
            }

            if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User took one of two out.")
                MediaController.userPlayedTheMedia = false
            }

            Log.d(
                "AirPodsParser",
                "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
            )

            if (newInEarData.sorted() != inEarData.sorted()) {
                if (inEar) {
                    if (!justEnabledA2dp) {
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false
                    }
                } else {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun registerA2dpConnectionReceiver() {
        val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val previousState = intent.getIntExtra(
                        BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                    Log.d(
                        "MediaController",
                        "A2DP state changed: $previousState -> $state for device: ${device?.address}"
                    )

                    if (state == BluetoothProfile.STATE_CONNECTED && previousState != BluetoothProfile.STATE_CONNECTED && device?.address == this@AirPodsService.device?.address) {

                        Log.d("MediaController", "A2DP connected, sending play command")
                        MediaController.sendPlay()
                        MediaController.iPausedTheMedia = false

                        context.unregisterReceiver(this)
                    }
                }
            }
        }

        val a2dpIntentFilter =
            IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
        }
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = getCurrentDeviceName(),
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationAwarenessPauseMusic = sharedPreferences.getBoolean(
                "conversation_awareness_pause_music", false
            ),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean(
                "show_phone_battery_in_widget", true
            ),
            relativeConversationAwarenessVolume = sharedPreferences.getBoolean(
                "relative_conversation_awareness_volume", true
            ),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean(
                "disconnect_when_not_wearing", false
            ),
            conversationAwarenessVolume = sharedPreferences.getInt(
                "conversation_awareness_volume", 43
            ),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle",

            // AirPods state-based takeover
            takeoverWhenDisconnected = sharedPreferences.getBoolean(
                "takeover_when_disconnected", false
            ),
            takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
            takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
            takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),

            // Phone state-based takeover
            takeoverWhenRingingCall = sharedPreferences.getBoolean(
                "takeover_when_ringing_call", false
            ),
            takeoverWhenMediaStart = sharedPreferences.getBoolean(
                "takeover_when_media_start", false
            ),

            // Stem actions
            leftSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,
            rightSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,

            leftDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_double_press_action", "PREVIOUS_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,
            rightDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_double_press_action", "NEXT_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,

            leftTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,
            rightTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,

            leftLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_long_press_action", "CYCLE_NOISE_CONTROL_MODES"
                ) ?: "CYCLE_NOISE_CONTROL_MODES"
            )!!,
            rightLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_long_press_action", "DIGITAL_ASSISTANT"
                ) ?: "DIGITAL_ASSISTANT"
            )!!,

            cameraAction = sharedPreferences.getString("camera_action", null)
                ?.let { StemPressType.valueOf(it) },

            // AirPods device information
            airpodsName = "",
            airpodsModelNumber = sharedPreferences.getString("airpods_model_number", "") ?: "",
            airpodsManufacturer = sharedPreferences.getString("airpods_manufacturer", "") ?: "",
            airpodsSerialNumber = sharedPreferences.getString("airpods_serial_number", "") ?: "",
            airpodsLeftSerialNumber = sharedPreferences.getString("airpods_left_serial_number", "")
                ?: "",
            airpodsRightSerialNumber = sharedPreferences.getString(
                "airpods_right_serial_number", ""
            ) ?: "",
            airpodsVersion1 = sharedPreferences.getString("airpods_version1", "") ?: "",
            airpodsVersion2 = sharedPreferences.getString("airpods_version2", "") ?: "",
            airpodsVersion3 = sharedPreferences.getString("airpods_version3", "") ?: "",
            airpodsHardwareRevision = sharedPreferences.getString("airpods_hardware_revision", "")
                ?: "",
            airpodsUpdaterIdentifier = sharedPreferences.getString("airpods_updater_identifier", "")
                ?: "",

            selfMacAddress = sharedPreferences.getString("self_mac_address", "") ?: ""
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when (key) {
            "mac_address" -> macAddress = preferences.getString(key, "") ?: ""
            "automatic_ear_detection" -> synchronized(connectionAttemptLock) {
                val state = controlSession?.state ?: lastControlDevice
                val local = state?.desiredControls?.get(0x0A)
                if (state != null && local != null) applyLocalControlBehavior(state, 0x0A, local)
                else config.earDetectionEnabled = preferences.getBoolean(key, true)
            }

            "conversation_awareness_pause_music" -> config.conversationAwarenessPauseMusic =
                preferences.getBoolean(key, false)

            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                updateBattery()
            }

            "relative_conversation_awareness_volume" -> config.relativeConversationAwarenessVolume =
                preferences.getBoolean(key, true)

            "head_gestures" -> config.headGestures = preferences.getBoolean(key, true)
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing =
                preferences.getBoolean(key, false)

            "conversation_awareness_volume" -> config.conversationAwarenessVolume =
                preferences.getInt(key, 43)

            "qs_click_behavior" -> config.qsClickBehavior =
                preferences.getString(key, "cycle") ?: "cycle"

            // AirPods state-based takeover
            "takeover_when_disconnected" -> config.takeoverWhenDisconnected =
                preferences.getBoolean(key, true)

            "takeover_when_idle" -> config.takeoverWhenIdle = preferences.getBoolean(key, true)
            "takeover_when_music" -> config.takeoverWhenMusic = preferences.getBoolean(key, false)
            "takeover_when_call" -> config.takeoverWhenCall = preferences.getBoolean(key, true)

            // Phone state-based takeover
            "takeover_when_ringing_call" -> config.takeoverWhenRingingCall =
                preferences.getBoolean(key, true)

            "takeover_when_media_start" -> config.takeoverWhenMediaStart =
                preferences.getBoolean(key, true)

            "left_single_press_action" -> {
                config.leftSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "right_single_press_action" -> {
                config.rightSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "left_double_press_action" -> {
                config.leftDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_double_press_action" -> {
                config.rightDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                )!!
                setupStemActions()
            }

            "left_triple_press_action" -> {
                config.leftTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_triple_press_action" -> {
                config.rightTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "left_long_press_action" -> {
                config.leftLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES")
                        ?: "CYCLE_NOISE_CONTROL_MODES"
                )!!
                setupStemActions()
            }

            "right_long_press_action" -> {
                config.rightLongPressAction = StemAction.fromString(
                    preferences.getString(key, "DIGITAL_ASSISTANT") ?: "DIGITAL_ASSISTANT"
                )!!
                setupStemActions()
            }

            "camera_action" -> config.cameraAction =
                preferences.getString(key, null)?.let { StemPressType.valueOf(it) }

            // AirPods device information
            "airpods_model_number" -> config.airpodsModelNumber =
                preferences.getString(key, "") ?: ""

            "airpods_manufacturer" -> config.airpodsManufacturer =
                preferences.getString(key, "") ?: ""

            "airpods_serial_number" -> config.airpodsSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_left_serial_number" -> config.airpodsLeftSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_right_serial_number" -> config.airpodsRightSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_version1" -> config.airpodsVersion1 = preferences.getString(key, "") ?: ""
            "airpods_version2" -> config.airpodsVersion2 = preferences.getString(key, "") ?: ""
            "airpods_version3" -> config.airpodsVersion3 = preferences.getString(key, "") ?: ""
            "airpods_hardware_revision" -> config.airpodsHardwareRevision =
                preferences.getString(key, "") ?: ""

            "airpods_updater_identifier" -> config.airpodsUpdaterIdentifier =
                preferences.getString(key, "") ?: ""

            "self_mac_address" -> config.selfMacAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, @Suppress("SameParameterValue") source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"

        synchronized(inMemoryLogs) {
            inMemoryLogs.add(logEntry)
            if (inMemoryLogs.size > maxLogEntries) {
                inMemoryLogs.iterator().next().let {
                    inMemoryLogs.remove(it)
                }
            }

            _packetLogsFlow.value = inMemoryLogs.toSet()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val logs =
                sharedPreferencesLogs.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet()
                    ?: mutableSetOf()
            logs.add(logEntry)

            if (logs.size > maxLogEntries) {
                val toKeep = logs.toList().takeLast(maxLogEntries).toSet()
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, toKeep) }
            } else {
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, logs) }
            }
        }
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _packetLogsFlow.value = emptySet()
        }
        sharedPreferencesLogs.edit { remove(packetLogKey) }
    }

    fun clearLogs() {
        clearPacketLogs()
        _packetLogsFlow.value = emptySet()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    private var callNumber: String? = null

    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false
    fun showPopup(service: Service, name: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.d(TAG, "Posting bottom sheet popup to main thread from ${Thread.currentThread().name}")
            Handler(Looper.getMainLooper()).post { showPopup(service, name) }
            return
        }
        if (!sharedPreferences.getBoolean("show_bottom_sheet_popup", true)) {
            Log.d(TAG, "Skipping bottom sheet popup: disabled")
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        if (popupShown) {
            Log.d(TAG, "Skipping bottom sheet popup: one is already open")
            return
        }
        val popupWindow = PopupWindow(service.applicationContext)
        popupShown = popupWindow.open(name, batteryNotification)
        if (popupShown) {
            Log.d(TAG, "Showing bottom sheet popup")
        } else {
            Log.w(TAG, "Bottom sheet popup was not added")
        }
    }

    var islandOpen = false
    var islandWindow: IslandWindow? = null
    private val connectionIslandGate = ConnectionIslandGate()

    @SuppressLint("MissingPermission")
    fun showIsland(
        service: Service,
        batteryPercentage: Int,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null,
        connectionTransition: Boolean = false
    ) {
        if (type == IslandType.CONNECTED && !connectionTransition) {
            Log.d(TAG, "Skipping connected island: no disconnected-to-connected transition")
            return
        }
        if (!sharedPreferences.getBoolean("show_island_popup", true)) {
            Log.d(TAG, "Skipping island window: popup disabled")
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "Skipping island window: no SYSTEM_ALERT_WINDOW permission")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            if (islandOpen || islandWindow?.isVisible == true) {
                Log.d(TAG, "Skipping island window: one is already open")
                if (type == IslandType.CONNECTED) {
                    connectionIslandGate.onConnectedIslandShown()
                }
                return@launch
            }
            Log.d(TAG, "Showing island window type=$type")
            val newIslandWindow = IslandWindow(service.applicationContext)
            newIslandWindow.show(
                getCurrentDeviceName(),
                batteryPercentage,
                this@AirPodsService,
                type,
                reversed,
                otherDeviceName
            )
            if (newIslandWindow.isVisible) {
                islandWindow = newIslandWindow
                if (type == IslandType.CONNECTED) {
                    connectionIslandGate.onConnectedIslandShown()
                }
            } else {
                islandOpen = false
                Log.w(TAG, "Island window did not become visible")
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    //    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver
    private var batteryChangedIntentReceiver: BroadcastReceiver? = null
    private var batteryChangedReceiverRegistered = false
    private var showIslandReceiver: BroadcastReceiver? = null
    private var showIslandReceiverRegistered = false
    var widgetMobileBatteryEnabled = false

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_NONE
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods BluetoothConnectionManager.aacpSocket? Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationSettingsIntent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
            }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods).setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("KotlinUnreachableCode")
    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        return // something causes too many notifications. turning off for now
        if (BuildConfig.FLAVOR != "xposed") {
            Log.w(
                TAG,
                "Not showing BluetoothConnectionManager.aacpSocket? error notification to user, the service shouldn't be running if it isn't supported."
            )
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods).setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP").setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. Error: $errorMessage"
                )
            ).setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", getANC())
            setPackage(packageName)
        })
    }

    fun sendBatteryBroadcast() {
        broadcastBatteryInformation()
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
            setPackage(packageName)
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getCurrentDeviceName(),
            batteryNotification.getBattery()
        )
    }

    fun setBatteryMetadata() {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            device?.let { it ->
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_CASE_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.CASE }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_CASE_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_LEFT_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_LEFT_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_RIGHT_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_RIGHT_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBatteryWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            val leftBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBattery() {
        setBatteryMetadata()
        updateBatteryWidget()
        sendBatteryBroadcast()
        sendBatteryNotification()
    }

    fun updateNoiseControlWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoiseControlWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        val remoteViews = RemoteViews(packageName, R.layout.listening_mode_widget).also { it ->
            val ancStatus = getANC()
            val allowOffMode = controlAllowsOffMode()
            it.setInt(
                R.id.widget_off_button,
                "setBackgroundResource",
                if (ancStatus == 1) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
            )
            it.setInt(
                R.id.widget_transparency_button,
                "setBackgroundResource",
                if (ancStatus == 3) (if (allowOffMode) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_checked_shape_start) else (if (allowOffMode) R.drawable.widget_button_shape_middle else R.drawable.widget_button_shape_start)
            )
            it.setInt(
                R.id.widget_adaptive_button,
                "setBackgroundResource",
                if (ancStatus == 4) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            )
            it.setInt(
                R.id.widget_anc_button,
                "setBackgroundResource",
                if (ancStatus == 2) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
            )
            it.setViewVisibility(
                R.id.widget_off_button, if (allowOffMode) View.VISIBLE else View.GONE
            )
            val supportsAdaptive = supportsAdaptiveAudioForCurrentSession()
            it.setViewVisibility(R.id.widget_adaptive_button, if (supportsAdaptive) View.VISIBLE else View.GONE)
            it.setBoolean(R.id.widget_adaptive_button, "setEnabled", supportsAdaptive)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setViewLayoutMargin(
                    R.id.widget_transparency_button,
                    RemoteViews.MARGIN_START,
                    if (allowOffMode) 2f else 12f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                it.setViewPadding(
                    R.id.widget_transparency_button,
                    if (allowOffMode) 2.dpToPx() else 12.dpToPx(),
                    12.dpToPx(),
                    2.dpToPx(),
                    12.dpToPx()
                )
            }
        }

        appWidgetManager.partiallyUpdateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (BluetoothConnectionManager.aacpSocket == null) {
            return
        }
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            val updatedNotificationBuilder =
                NotificationCompat.Builder(this, "airpods_connection_status")
                    .setSmallIcon(R.drawable.airpods)
                    .setContentTitle(airpodsName ?: config.deviceName).setContentText(
                        """${
                        batteryList?.find { it.component == BatteryComponent.LEFT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "L: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.RIGHT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "R: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.CASE }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "Case: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    }""").setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)

            if (disconnectedBecauseReversed) {
                updatedNotificationBuilder.addAction(
                    R.drawable.ic_bluetooth, "Reconnect", PendingIntent.getService(
                        this, 0, Intent(this, AirPodsService::class.java).apply {
                            action = "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE"
                        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val updatedNotification = updatedNotificationBuilder.build()

            notificationManager.notify(2, updatedNotification)
            notificationManager.cancel(1)
        } else if (!connected) {
            notificationManager.cancel(2)
        } else if (!config.bleOnlyMode && BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            showSocketConnectionFailureNotification("BluetoothConnectionManager.aacpSocket? created, but not connected. Check logs")
        }
    }

    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            startHeadTracking()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                    handleIncomingCallOnceConnected = false
                } else {
                    rejectCall()
                    handleIncomingCallOnceConnected = false
                }
            }

        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            gestureDetector?.startDetection(doNotStop = true) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) { _, _, _ ->
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
    }

    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod =
                    telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        try {
            gestureDetector?.processHeadOrientation(horizontal, vertical)
        } catch (e: Exception) {
            Log.w(TAG, "gesture detector on ${data.toHexString()}: ${e.message}")
        }
    }

    private lateinit var connectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId)).build()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1

    @Suppress("PrivatePropertyName")
    private val APPLE = 0x004C

    @Suppress("PrivatePropertyName")
    private val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    @Suppress("PrivatePropertyName")
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @Suppress("PrivatePropertyName")
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"

    @Suppress("PrivatePropertyName")
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @SuppressLint("MissingPermission")
    fun broadcastBatteryInformation() {
        if (device == null || checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") != PackageManager.PERMISSION_GRANTED) return

        val batteryList = batteryNotification.getBattery()
        val leftBattery = batteryList.find { it.component == BatteryComponent.LEFT }
        val rightBattery = batteryList.find { it.component == BatteryComponent.RIGHT }

        // Calculate unified battery level (minimum of left and right)
        val batteryUnified = minOf(
            leftBattery?.level ?: 100, rightBattery?.level ?: 100
        )

        // Check charging status
        val isLeftCharging = leftBattery?.status == BatteryStatus.CHARGING
        val isRightCharging = rightBattery?.status == BatteryStatus.CHARGING
        isLeftCharging && isRightCharging

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            batteryUnified // Battery Level
        )

        // Broadcast vendor-specific event
        val intent = Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD,
                VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV
            )
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                BluetoothHeadset.AT_CMD_TYPE_SET
            )
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcastAsUser(
                    intent,
                    UserHandle.getUserHandleForUid(-1),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send vendor-specific event: ${e.message}")
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d(TAG, "Broadcast battery level $batteryUnified% to system")
    }

    private fun setMetadatas(d: BluetoothDevice) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "no permission BLUETOOTH_PRIVILEGED, returning")
            return
        }
        Log.d(TAG, "has permission BLUETOOTH_PRIVILEGED, proceeding")
        d.let { device ->
            val instance = airpodsInstance
            if (instance != null) {
                val metadataSet = SystemApisUtils.setMetadata(
                    device, device.METADATA_MODEL_NAME, instance.model.name.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_DEVICE_TYPE,
                    device.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_ICON,
                    resToUri(instance.model.caseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_ICON,
                    resToUri(instance.model.rightBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_ICON,
                    resToUri(instance.model.leftBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MANUFACTURER_NAME,
                    instance.model.manufacturer.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                )
                Log.d(TAG, "Metadata set: $metadataSet")
            } else {
                Log.w(
                    TAG,
                    "AirPods demoInstance is not of type AirPodsInstance, skipping metadata setting"
                )
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val remote = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice?
                    }
                    val address = try { validControlAddress(remote?.address) } catch (_: Exception) { null }
                    if (address != null) synchronized(discoverySdpLock) { discoverySdpRequested.remove(address) }
                    requestStartupDiscovery(DiscoveryReason.ACL_CONNECTED)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) == BluetoothProfile.STATE_CONNECTED) {
                        requestStartupDiscovery(DiscoveryReason.A2DP_CONNECTED)
                    }
                }
                BluetoothDevice.ACTION_UUID -> requestStartupDiscovery(DiscoveryReason.UUID)
            }
        }
    }

    val externalBroadcastFilter = IntentFilter().apply {
        addAction("me.kavishdevar.librepods.SET_ANC_MODE")
        addAction("me.kavishdevar.librepods.CONVO_DETECT")
    }
    var externalBroadcastReceiver: BroadcastReceiver? = null

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent action: ${intent?.action}")

        if (intent?.action == "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE") {
            Log.d(TAG, "reconnect after reversed received, taking over")
            disconnectedBecauseReversed = false
            otherDeviceTookOver = false
            takeOver("music", manualTakeOverAfterReversed = true)
        } else {
            requestStartupDiscovery(DiscoveryReason.ON_START)
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "HardwareIds")
    fun takeOver(
        takingOverFor: String,
        manualTakeOverAfterReversed: Boolean = false,
        startHeadTrackingAgain: Boolean = false
    ) {
        if (takingOverFor == "reverse") {
            aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
            )
            aacpManager.sendMediaInformataion(
                localMac
            )
            aacpManager.sendHijackReversed(
                localMac
            )
            connectAudio(
                this@AirPodsService, device
            )
            otherDeviceTookOver = false
        }
        val ownsConnection = aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value?.get(0)?.toInt()
        Log.d(
            TAG, "owns connection: $ownsConnection"
        )
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            if (!XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false) || ownsConnection == 0) {
                Log.d(TAG, "not taking over, vendorid is probably not set to apple")
                return
            }
            if (aacpManager.getControlCommandStatus(AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value[0]?.toInt() != 1 || (aacpManager.audioSource?.mac != localMac && aacpManager.audioSource?.type != AACPManager.Companion.AudioSourceType.NONE)) {
                if (disconnectedBecauseReversed) {
                    if (manualTakeOverAfterReversed) {
                        Log.d(TAG, "forcefully taking over despite reverse as user requested")
                        disconnectedBecauseReversed = false
                    } else {
                        Log.d(
                            TAG,
                            "connected locally, but can not hijack as other device had reversed"
                        )
                        return
                    }
                }

                Log.d(TAG, "already connected locally, hijacking connection by asking AirPods")
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value, 1
                )
                aacpManager.sendMediaInformataion(
                    localMac
                )
                aacpManager.sendSmartRoutingShowUI(
                    localMac
                )
                aacpManager.sendHijackRequest(
                    localMac
                )
                otherDeviceTookOver = false
                connectAudio(this, device)
                showIsland(
                    this,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level!!
                        ),
                    IslandType.CONNECTED
                )

                CoroutineScope(Dispatchers.IO).launch {
                    delay(500) // a2dp takes time, and so does taking control + AirPods pause it for no reason after connecting
                    if (takingOverFor == "music") {
                        Log.d(TAG, "Resuming music after taking control")
                        MediaController.sendPlay(replayWhenPaused = true)
                    } else if (startHeadTrackingAgain) {
                        Log.d(TAG, "Starting head tracking again after taking control")
                        Handler(Looper.getMainLooper()).postDelayed({
                            startHeadTracking()
                        }, 500)
                    }
                    delay(1000) // should ideally have a callback when it's taken over because for some reason android doesn't dispatch when it's paused
                    if (takingOverFor == "music") {
                        Log.d(TAG, "resuming again just in case")
                        MediaController.sendPlay(force = true)
                    }
                }
            } else {
                Log.d(
                    TAG, "Already connected locally and already own connection, skipping takeover"
                )
            }
            return
        }

//        if (CrossDevice.isAvailable) {
//            Log.d(TAG, "CrossDevice is available, continuing")
//        }
//        else if (bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true) {
//            Log.d(TAG, "At least one AirPod is in ear, continuing")
//        }
//        else {
//            Log.d(TAG, "CrossDevice not available and AirPods not in ear, skipping")
//            return
//        }

        if (bleManager.getMostRecentStatus()?.isLeftInEar == false && bleManager.getMostRecentStatus()?.isRightInEar == false) {
            Log.d(TAG, "Both AirPods are out of ear, not taking over audio")
            return
        }

        val shouldTakeOverPState = when (takingOverFor) {
            "music" -> config.takeoverWhenMediaStart
            "call" -> config.takeoverWhenRingingCall
            else -> false
        }

        if (!shouldTakeOverPState) {
            Log.d(TAG, "Not taking over audio, phone state takeover disabled")
            return
        }

        val shouldTakeOver = when (bleManager.getMostRecentStatus()?.connectionState) {
            "Disconnected" -> config.takeoverWhenDisconnected
            "Idle" -> config.takeoverWhenIdle
            "Music" -> config.takeoverWhenMusic
            "Call" -> config.takeoverWhenCall
            "Ringing" -> config.takeoverWhenCall
            "Hanging Up" -> config.takeoverWhenCall
            else -> false
        }

        if (!shouldTakeOver) {
            Log.d(TAG, "Not taking over audio, airpods state takeover disabled")
            return
        }

        if (takingOverFor == "music") {
            Log.d(TAG, "Pausing music so that it doesn't play through speakers")
            MediaController.pausedWhileTakingOver = true
            MediaController.sendPause(true)
        } else {
            handleIncomingCallOnceConnected = true
        }

        Log.d(TAG, "Taking over audio")
//        CrossDevice.sendRemotePacket(CrossDevicePackets.REQUEST_DISCONNECT.packet)
        Log.d(TAG, macAddress)

//        sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false) }
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }

        if (device != null) {
            if (config.bleOnlyMode) {
                // In BLE-only mode, just show connecting status without actual L2CAP connection
                Log.d(TAG, "BLE-only mode: showing connecting status without L2CAP connection")
                updateNotificationContent(
                    true, config.deviceName, batteryNotification.getBattery()
                )
                // Set a temporary connecting state
//                isConnectedLocally = false // Keep as false since we're not actually connecting to L2CAP
            } else {
                connectToSocket(bluetoothAdapter, device!!)
                connectAudio(this, device)
//                isConnectedLocally = true
            }
        }
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.TAKING_OVER
        )

//        CrossDevice.isAvailable = false
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(
        adapter: BluetoothAdapter, device: BluetoothDevice, manual: Boolean = false
    ) {
        synchronized(connectionAttemptLock) {
            connectToSocketInternal(adapter, device, manual, allowHandshakeRecovery = true)
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun connectToSocketInternal(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        manual: Boolean,
        allowHandshakeRecovery: Boolean,
        allowTimeoutRecovery: Boolean = allowHandshakeRecovery
    ) {
        if (controlServiceDestroyed || controlServiceDestroyRequested) return
        if (BluetoothConnectionManager.aacpSocket != null && BluetoothConnectionManager.aacpSocket?.isConnected == true) return
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
//        if (!isConnectedLocally) {
        val socket = try {
            createBluetoothSocket(adapter, device, uuid, 4097)
        } catch (e: Exception) {
            if (!controlServiceDestroyRequested) {
                Log.e(TAG, "Failed to create BluetoothSocket: ${e.message}")
                showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.localizedMessage}")
            }
            return
        }

        val pending = PendingControlSocket(socket, StartupConnectAttempt())
        pendingControlSocket = pending
        val connectWatchdog = CoroutineScope(Dispatchers.IO).launch {
            delay(5000)
            cancelPendingControlSocket(pending)
        }
        var sessionInstalled = false
        try {
            if (controlServiceDestroyRequested) return
            runBlocking {
                withTimeout(5000.milliseconds) {
                    try {
                        if (controlServiceDestroyRequested) return@withTimeout
                        socket.connect()
                        if (!pending.attempt.claimConnected()) return@withTimeout
                        if (controlServiceDestroyRequested) {
                            closeCandidateOnDestroyOrFailure(pending)
                            return@withTimeout
                        }
                        this@AirPodsService.device = device
                        BluetoothConnectionManager.aacpSocket = socket
                        // ATT is connected by its worker after model discovery; it must not
                        // delay the independent AACP handshake or close AACP on failure.
                        BluetoothConnectionManager.attSocket = null
                        beginControlSession(socket, device)
                        sessionInstalled = controlSession?.let { it.socket === socket && isCurrentControlSession(it) } == true
                        if (!sessionInstalled || controlServiceDestroyRequested) {
                            closeCandidateOnDestroyOrFailure(pending)
                            return@withTimeout
                        }

                        // Create AirPodsInstance from stored config if available
                        if (airpodsInstance == null && config.airpodsModelNumber.isNotEmpty()) {
                            val model =
                                AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                            if (model != null) {
                                airpodsInstance = AirPodsInstance(
                                    name = getCurrentDeviceName(),
                                    model = model,
                                    actualModelNumber = config.airpodsModelNumber,
                                    serialNumber = config.airpodsSerialNumber,
                                    leftSerialNumber = config.airpodsLeftSerialNumber,
                                    rightSerialNumber = config.airpodsRightSerialNumber,
                                    version1 = config.airpodsVersion1,
                                    version2 = config.airpodsVersion2,
                                    version3 = config.airpodsVersion3,
                                )
                                setMetadatas(device)
                            }
                        }

                        synchronized(connectionPublishLock) {
                            if (controlServiceDestroyRequested || !socket.isConnected ||
                                controlSession?.let { it.socket === socket && isCurrentControlSession(it) } != true) {
                                sessionInstalled = false
                                return@withTimeout
                            }
                            updateNotificationContent(
                                true, config.deviceName, batteryNotification.getBattery()
                            )
                            Log.d(TAG, "<LogCollector:Complete:Success> Socket connected")
                            macAddress = device.address
                            sharedPreferences.edit {
                                putString("mac_address", macAddress)
                                putString("last_successful_aacp_address", macAddress)
                                putBoolean("connection_successful", true)
                            }
                            if (!sharedPreferences.contains("first_connection_successful_time")) {
                                sharedPreferences.edit {
                                    putLong(
                                        "first_connection_successful_time",
                                        System.currentTimeMillis()
                                    )
                                }
                            }
                            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_L2CAP_CONNECTED))
                        }
                    } catch (e: Exception) {
//                        sharedPreferences.edit { putBoolean("connection_successful", false) }
                        if (!controlServiceDestroyRequested) {
                            Log.d(
                                TAG, "<LogCollector:Complete:Failed> Socket not connected, ${e.message}"
                            )
                            if (manual) {
                                sendToast(
                                    "Couldn't connect to socket: ${e.localizedMessage}"
                                )
                            } else {
                                showSocketConnectionFailureNotification("Couldn't connect to socket: ${e.localizedMessage}")
                            }
                        }
                        return@withTimeout
//                            throw e // lol how did i not catch this before... gonna comment this line instead of removing to preserve history
                    }
                }
            }
            if (controlServiceDestroyRequested) return
            if (!socket.isConnected) {
                Log.d(TAG, "<LogCollector:Complete:Failed> socket not connected")
                if (manual) {
                    sendToast(
                        "Couldn't connect to socket: timeout."
                    )
                } else {
                    showSocketConnectionFailureNotification("Couldn't connect to socket: Timeout")
                }
                return
            }
            if (!sessionInstalled) return
            this@AirPodsService.device = device
            if (BluetoothConnectionManager.aacpSocket === socket && socket.isConnected) {
                aacpManager.resetStartupAcknowledgements()
                aacpManager.sendPacket(aacpManager.createHandshakePacket())
                aacpManager.sendSetFeatureFlagsPacket()
                requestNotificationsForSocket(socket)
                Log.d(TAG, "Requesting proximity keys")
                aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                CoroutineScope(Dispatchers.IO).launch {
                    delay(200)
                    if (!withCurrentControlSocket(socket) { aacpManager.sendPacket(aacpManager.createHandshakePacket()) }) return@launch
                    delay(200)
                    if (!withCurrentControlSocket(socket) { aacpManager.sendSetFeatureFlagsPacket() }) return@launch
                    delay(200)
                    if (!withCurrentControlSocket(socket) { requestNotificationsForSocket(socket) }) return@launch
                    delay(200)
                    if (!withCurrentControlSocket(socket) { aacpManager.sendSomePacketIDontKnowWhatItIs() }) return@launch
                    delay(200)
                    if (!withCurrentControlSocket(socket) {
                            aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                            if (!handleIncomingCallOnceConnected) startHeadTracking() else handleIncomingCall()
                        }) return@launch
                    Handler(Looper.getMainLooper()).postDelayed({
                        val currentSession = synchronized(connectionAttemptLock) {
                            if (BluetoothConnectionManager.aacpSocket !== socket || !socket.isConnected || controlSession?.socket !== socket) {
                                false
                            } else {
                                aacpManager.sendPacket(aacpManager.createHandshakePacket())
                                aacpManager.sendSetFeatureFlagsPacket()
                                requestNotificationsForSocket(socket)
                                aacpManager.sendRequestProximityKeys(AACPManager.Companion.ProximityKeyType.IRK.value)
                                if (!handleIncomingCallOnceConnected) stopHeadTracking()
                                true
                            }
                        }
                        if (!currentSession) return@postDelayed
                    }, 5000)

                    if (!withCurrentControlSocket(socket) {
                            sendBroadcast(
                                Intent(AirPodsNotifications.AIRPODS_CONNECTED).putExtra("device", device)
                                    .apply { setPackage(packageName) })
                            setupStemActions()
                            connectionIslandGate.onConnected()
                        }) return@launch
                    val startupRecoveryPolicy = StartupRecoveryPolicy(
                        recoveryAllowed = allowHandshakeRecovery,
                        timeoutRecoveryAllowed = allowTimeoutRecovery
                    )
                    val startupSession = synchronized(connectionAttemptLock) {
                        controlSession?.takeIf { it.socket === socket && isCurrentControlSession(it) }
                    } ?: return@launch
                    val startupTimeoutJob = attWorkScope.launch {
                        delay(STARTUP_RESPONSE_TIMEOUT_MS)
                        val shouldRecoverSilentSession = synchronized(connectionAttemptLock) {
                            isCurrentControlSession(startupSession) &&
                                !startupSession.handshakeAcknowledged && startupSession.model == null
                        }
                        if (!shouldRecoverSilentSession || !startupRecoveryPolicy.onTimeout()) return@launch
                        recoverStartupConnection(
                            socket,
                            adapter,
                            device,
                            manual,
                            StartupRecoveryTrigger.INITIALIZATION_TIMEOUT
                        )
                    }
                    try {
                        val inputStream = socket.inputStream
                        while (socket.isConnected) {
                            try {
                                val buffer = ByteArray(1024)
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead > 0) {
                                    if (!withCurrentControlSocket(socket) {}) return@launch
                                    val data = buffer.copyOfRange(0, bytesRead)
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                        putExtra("data", data)
                                        setPackage(packageName)
                                    })
                                    val formattedHex = data.joinToString(" ") { "%02X".format(it) }
//                                        CrossDevice.sendReceivedPacket(data)
                                    updateNotificationContent(
                                        true,
                                        getCurrentDeviceName(),
                                        batteryNotification.getBattery()
                                    )

                                    if (!withCurrentControlSocket(socket) { aacpManager.receivePacket(data) }) return@launch

                                    if (!isHeadTrackingData(data) && data.getOrNull(4) != 0x09.toByte() && data.getOrNull(4) != 0x63.toByte()) {
                                        Log.d("AirPodsData", "Data received: $formattedHex")
                                        logPacket(data, "AirPods")
                                    }

                                    if (startupRecoveryPolicy.onPacket(
                                            aacpManager.handshakeStatus(data),
                                            aacpManager.isValidAacpResponse(data)
                                        )
                                    ) {
                                        recoverStartupConnection(
                                            socket,
                                            adapter,
                                            device,
                                            manual,
                                            StartupRecoveryTrigger.HANDSHAKE_85
                                        )
                                        return@launch
                                    }
                                } else if (bytesRead == -1) {
                                    synchronized(connectionAttemptLock) {
                                        if (BluetoothConnectionManager.aacpSocket !== socket) return@launch
                                        endControlSession()
                                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                            putExtra("control_connection_token", controlConnectionToken)
                                            setPackage(packageName)
                                        })
                                        aacpManager.disconnected()
                                    }
                                    return@launch
                                }
                            } catch (e: Exception) {
                                synchronized(connectionAttemptLock) {
                                    if (BluetoothConnectionManager.aacpSocket !== socket) return@launch
                                    endControlSession()
                                    Log.w(TAG, "Error reading device data")
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                        putExtra("control_connection_token", controlConnectionToken)
                                        setPackage(packageName)
                                    })
                                    aacpManager.disconnected()
                                }
                                return@launch
                            }

                        }
                        synchronized(connectionAttemptLock) {
                            if (BluetoothConnectionManager.aacpSocket !== socket) return@launch
                            endControlSession()
                            aacpManager.disconnected()
                            updateNotificationContent(false)
                            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                putExtra("control_connection_token", controlConnectionToken)
                                setPackage(packageName)
                            })
                        }
                    } finally {
                        startupTimeoutJob.cancel()
                    }
                }
            }
        } catch (e: Exception) {
            if (!controlServiceDestroyRequested) {
                e.printStackTrace()
                Log.d(TAG, "Failed to connect to BluetoothConnectionManager.aacpSocket?: ${e.message}")
                showSocketConnectionFailureNotification("Failed to establish connection: ${e.localizedMessage}")
//                isConnectedLocally = false
                this@AirPodsService.device = device
                updateNotificationContent(false)
            }
        } finally {
            connectWatchdog.cancel()
            if (pendingControlSocket === pending) pendingControlSocket = null
            if (controlServiceDestroyRequested || !sessionInstalled) {
                closeCandidateOnDestroyOrFailure(pending)
            } else {
                cancelPendingControlSocket(pending)
            }
        }
//        } else {
//            Log.d(TAG, "Already connected locally, skipping BluetoothConnectionManager.aacpSocket? connection (isConnectedLocally = $isConnectedLocally, BluetoothConnectionManager.aacpSocket?.isConnected = ${this::BluetoothConnectionManager.aacpSocket?.isInitialized && BluetoothConnectionManager.aacpSocket?.isConnected})")
//        }
    }

    private fun recoverStartupConnection(
        socket: BluetoothSocket,
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        manual: Boolean,
        trigger: StartupRecoveryTrigger
    ) {
        synchronized(connectionAttemptLock) {
            if (BluetoothConnectionManager.aacpSocket !== socket) {
                Log.d(TAG, "Skipping startup recovery for a stale socket")
                return@synchronized
            }
            val recoveryToken = controlSession?.connectionToken ?: controlConnectionToken
            endControlSession()

            val oldAttSocket = BluetoothConnectionManager.attSocket
            val cleanupFailures = mutableListOf<String>()

            try {
                socket.close()
            } catch (e: Exception) {
                cleanupFailures += "AACP socket close failed (${e.javaClass.simpleName})"
            }
            try {
                oldAttSocket?.close()
            } catch (e: Exception) {
                cleanupFailures += "ATT socket close failed (${e.javaClass.simpleName})"
            }

            val attReaderStopped = try {
                attManager.stopReaderAndAwait(1000L)
            } catch (e: Exception) {
                cleanupFailures += "ATT reader stop failed (${e.javaClass.simpleName})"
                false
            }
            if (!attReaderStopped) {
                cleanupFailures += "ATT reader did not stop within 1000ms"
            }

            if (BluetoothConnectionManager.aacpSocket !== socket ||
                BluetoothConnectionManager.attSocket !== oldAttSocket
            ) {
                Log.d(TAG, "Stopping startup recovery because socket identity changed")
                return@synchronized
            }

            try {
                aacpManager.disconnected()
            } catch (e: Exception) {
                cleanupFailures += "AACP state reset failed (${e.javaClass.simpleName})"
            }
            if (attReaderStopped) {
                try {
                    attManager.disconnected()
                } catch (e: Exception) {
                    cleanupFailures += "ATT state reset failed (${e.javaClass.simpleName})"
                }
            }

            if (BluetoothConnectionManager.aacpSocket === socket) {
                BluetoothConnectionManager.aacpSocket = null
            }
            if (BluetoothConnectionManager.attSocket === oldAttSocket) {
                BluetoothConnectionManager.attSocket = null
            }
            if (cleanupFailures.isNotEmpty()) {
                Log.e(
                    TAG,
                    "Startup handshake recovery stopped after cleanup failure: ${cleanupFailures.joinToString()}"
                )
                try {
                    updateNotificationContent(false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update disconnected notification (${e.javaClass.simpleName})")
                }
                return@synchronized
            }

            Log.w(TAG, "Retrying socket connection once after startup recovery: ${trigger.name}")
            logSyncDiagnostic("session_restart token=$recoveryToken reason=${trigger.name}")
            connectToSocketInternal(
                adapter,
                device,
                manual,
                allowHandshakeRecovery = false,
                allowTimeoutRecovery = trigger == StartupRecoveryTrigger.HANDSHAKE_85
            )
        }
    }

    fun disconnectForCD() {
        synchronized(connectionAttemptLock) {
            endControlSession()
            BluetoothConnectionManager.aacpSocket?.close()
        }
        MediaController.pausedWhileTakingOver = false
        Log.d(TAG, "Disconnected from AirPods, showing island.")
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.MOVED_TO_REMOTE
        )
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
    }

    fun disconnectAirPods() {
        val disconnectedToken = synchronized(connectionAttemptLock) {
            endControlSession()
            if (BluetoothConnectionManager.aacpSocket == null) return
            try {
                BluetoothConnectionManager.aacpSocket?.close()
            } catch (_: Exception) {
                Log.e(TAG, "Unable to close device connection")
            }
//        isConnectedLocally = false
            aacpManager.disconnected()
            BluetoothConnectionManager.aacpSocket = null
            BluetoothConnectionManager.attSocket = null
            controlConnectionToken
        }

        updateNotificationContent(false)
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
            putExtra("control_connection_token", disconnectedToken)
            setPackage(packageName)
        })

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
            try {
                device?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "device.disconnect() failed, $e")
            }
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        }
        Log.d(TAG, "Disconnected AirPods upon user request")
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationAwarenessNotification()

    @Suppress("unused")
    fun setEarDetection(enabled: Boolean) {
        setUserControlValue(ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            byteArrayOf(if (enabled) 0x01 else 0x02))
    }

    fun getBattery(): List<Battery> {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            batteryNotification.setBattery(CrossDevice.batteryBytes)
//        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int = synchronized(connectionAttemptLock) {
        (controlSession?.state ?: lastControlDevice)?.desiredControls?.get(0x0D)
            ?.firstOrNull()?.toInt()?.and(0xff) ?: 0
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                                Log.d(TAG, "Already disconnected from A2DP")
                                return
                            }
                            val method = proxy.javaClass.getMethod(
                                "setConnectionPolicy", BluetoothDevice::class.java, Int::class.java
                            )
                            Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device?.address} to 0")
                            method.invoke(proxy, device, 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } else {
            Log.d(TAG, "not disconnecting A2DP, no BLUETOOTH_PRIVILEGED permission")
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        try {
                            val method =
                                proxy.javaClass.getMethod(
                                    "setConnectionPolicy",
                                    BluetoothDevice::class.java,
                                    Int::class.java
                                )
                            Log.d(TAG, "calling HEADSET.setConnectionPolicy for ${device?.address} to 0")
                            method.invoke(proxy, device, 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } else {
            Log.d(TAG, "not disconnecting HEADSET, no MODIFIY_PHONE_STATE permission")
        }
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.java
                            )
                            Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device?.address} to 100")
                            policyMethod.invoke(proxy, device, 100)

                            val connectMethod =
                                proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            connectMethod.invoke(
                                proxy, device
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            if (MediaController.pausedWhileTakingOver) {
                                MediaController.sendPlay()
                            }
                        }
                    }
                    else {
                        val connectMethod =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        connectMethod.invoke(
                            proxy, device
                        )
                        Log.d(TAG, "not setting connection policy for A2DP, no BLUETOOTH_PRIVILEGED permission. just called connect")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.java
                            )
                            Log.d(
                                TAG,
                                "calling HEADSET.setConnectionPolicy for ${device?.address} to 100"
                            )
                            policyMethod.invoke(proxy, device, 100)
                            val connectMethod =
                                proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            connectMethod.invoke(proxy, device)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    } else {
                        Log.d(TAG, "not setting connection policy for HEADSET, no MODIFIY_PHONE_STATE permission")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun getSystemDeviceNameOrNull(device: BluetoothDevice?): String? =
        runCatching { device?.alias?.takeIf { it.isNotBlank() } ?: device?.name }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun getSystemDeviceName(device: BluetoothDevice?): String = getSystemDeviceNameOrNull(device) ?: "AirPods"

    @SuppressLint("MissingPermission")
    private fun resetDeviceName(device: BluetoothDevice?) = synchronized(connectionAttemptLock) {
        val address = validControlAddress(device?.address)
        val local = address?.let { controlDevices[it]?.desiredName }
            ?: startupSavedDeviceName?.takeIf { it.first == address }?.second
        config.deviceName = local ?: getSystemDeviceName(device)
        config.airpodsName = local ?: ""
        airpodsInstance = airpodsInstance?.copy(name = config.deviceName)
    }

    @SuppressLint("MissingPermission")
    fun getCurrentDeviceName(): String = synchronized(connectionAttemptLock) {
        val state = controlSession?.state ?: lastControlDevice?.takeIf {
            device == null || validControlAddress(device?.address) == it.address
        }
        val address = controlSession?.state?.address ?: validControlAddress(device?.address)
            ?: state?.address ?: startupLegacyAddress
        val savedName = startupSavedDeviceName?.takeIf { it.first == address }?.second
        state?.desiredName ?: savedName
            ?: if (::config.isInitialized) config.deviceName.takeIf { it.isNotBlank() } ?: getSystemDeviceName(device)
            else getSystemDeviceName(device)
    }

    fun getNameConnectionToken(): Long? = synchronized(connectionAttemptLock) {
        controlSession?.takeIf(::isCurrentControlSession)?.connectionToken
    }

    fun hasCurrentSessionInformation(): Boolean = synchronized(connectionAttemptLock) {
        controlSession?.takeIf(::isCurrentControlSession)?.informationReceived == true
    }

    /** True means the name was saved and queued, not confirmed by the remote device. */
    @SuppressLint("MissingPermission")
    fun setName(name: String, connectionToken: Long?): Boolean = synchronized(connectionAttemptLock) {
        val session = controlSession?.takeIf(::isCurrentControlSession) ?: return@synchronized false
        if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 255 ||
            connectionToken == null || connectionToken != session.connectionToken) return@synchronized false
        try {
            if (!deviceSettingsStore.saveDesiredName(session.state.address, name)) return@synchronized false
        } catch (_: Exception) {
            Log.e(TAG, "Unable to save device name")
            return@synchronized false
        }
        session.state.desiredName = name
        session.userEditedKeys.add("name")
        if (session.syncRef == null) session.editedBeforeModel.add("name")
        session.syncRef?.let { session.sync.updateDesired(it, "name", name.toByteArray(Charsets.UTF_8), SystemClock.elapsedRealtime()) }
        publishSyncStatus(session)
        wakeSync(session)
        config.deviceName = name
        config.airpodsName = name
        airpodsInstance = airpodsInstance?.copy(name = name)
        runCatching { session.socket.remoteDevice?.alias = name }.onFailure { Log.w(TAG, "Unable to update Android device alias") }
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(packageName))
        updateNotificationContent(true, name, batteryNotification.getBattery())
        true
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        synchronized(connectionPublishLock) { controlServiceDestroyRequested = true }
        pendingControlSocket?.let(::closeCandidateOnDestroyOrFailure)
        startupDiscoveryGate.destroy()
        synchronized(connectionAttemptLock) {
            controlServiceDestroyed = true
            endControlSession()
            attWorkJob.cancel()
        }
        connectionIslandGate.onDisconnected()
        clearPacketLogs()
        Log.d(TAG, "Service stopped is being destroyed for some reason!")

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        if (batteryChangedReceiverRegistered) {
            try {
                batteryChangedIntentReceiver?.let { unregisterReceiver(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                batteryChangedReceiverRegistered = false
                batteryChangedIntentReceiver = null
            }
        }
        if (showIslandReceiverRegistered) {
            try {
                showIslandReceiver?.let { unregisterReceiver(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                showIslandReceiverRegistered = false
                showIslandReceiver = null
            }
        }

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(externalBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bleManager.stopScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.unregisterTelephonyCallback(phoneStateListener)
        }
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    var isHeadTrackingActive = false

    fun startHeadTracking() {
        isHeadTrackingActive = true
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && aacpManager.getControlCommandStatus(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
            )?.value?.get(0)?.toInt() != 1
        ) {
            takeOver("call", startHeadTrackingAgain = true)
            Log.d(TAG, "Taking over for head tracking")
        } else {
            Log.w(TAG, "Will not be taking over for head tracking, might not work.")
        }
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStartHeadTrackingPacket())
        } else {
            aacpManager.sendStartHeadTracking()
        }
        HeadTracking.reset()
    }

    fun stopHeadTracking() {
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStopHeadTrackingPacket())
        } else {
            aacpManager.sendStopHeadTracking()
        }
        isHeadTrackingActive = false
        gestureDetector?.stopDetection()
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac() {
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }
        if (device != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "connecting to $macAddress")
                connectToSocket(bluetoothAdapter, device!!, manual = true)
                connectAudio(this@AirPodsService, device!!)
            }
        }
    }
}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}

fun getNextMode(currentMode: Int, configByte: Int, offmodeEnabled: Boolean): Int {
    val enabledModes = buildList {
        if ((configByte and 0x01) != 0 && offmodeEnabled) add(1)
        if ((configByte and 0x04) != 0) add(3)
        if ((configByte and 0x08) != 0) add(4)
        if ((configByte and 0x02) != 0) add(2)
    }
    Log.d(TAG, "currentMode: $currentMode, config: ${configByte.toString(2)}")

    if (enabledModes.isEmpty()) return currentMode

    val currentIndex = enabledModes.indexOf(currentMode)
    val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % enabledModes.size

    return enabledModes[nextIndex]
}
