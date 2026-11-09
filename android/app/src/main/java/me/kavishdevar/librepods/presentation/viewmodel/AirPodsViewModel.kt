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

package me.kavishdevar.librepods.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.data.CompositeAttSettings
import me.kavishdevar.librepods.data.SettingsSyncPolicy
import me.kavishdevar.librepods.data.ControlDisplaySnapshot
import me.kavishdevar.librepods.data.ControlDisplayVersion
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.shouldCloseControlDisplay
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.services.AirPodsService

private const val TAG = "AirPodsViewModel"

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String = "AirPods",
    val nameConnectionToken: Long? = null,

    val isLocallyConnected: Boolean = false,
    val hasCurrentSessionInformation: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),
    val supportsAdaptiveAudio: Boolean = false,

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val controlServiceGeneration: Long = 0L,
    val controlDisplayVersion: ControlDisplayVersion? = null,
    val offListeningMode: Boolean = true,

    val battery: List<Battery> = emptyList(),
    val ancMode: Int = 3,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headGesturesEnabled: Boolean = true,

    val eqData: FloatArray = floatArrayOf(),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val loudSoundReductionEnabled: Boolean = false,
    val loudSoundReductionAvailable: Boolean = false,
    val transparencySettings: CompositeAttSettings = CompositeAttSettings(ATTHandles.TRANSPARENCY),
    val hearingAidSettings: CompositeAttSettings = CompositeAttSettings(ATTHandles.HEARING_AID),

    val isPremium: Boolean = false,
    val vendorIdHook: Boolean = false,

    val dynamicEndOfCharge: Boolean = false,

    val connectionSuccessful: Boolean = false,
    val timeUntilFOSSPremiumExpiry: Long = 0L,

    val customEq: CustomEq = CustomEq(1, 50, 50, 50) // disabled
)

val demoInstance = AirPodsInstance(
    name = "[DEMO] AirPods Pro",
    model = AirPodsModels.getModelByModelNumber("A3064")!!,
    actualModelNumber = "A3064",
    serialNumber = "JXF9Q94A40",
    leftSerialNumber = "L-DEMO",
    rightSerialNumber = "R-DEMO",
    version1 = "90.3388000000000000.1786",
    version2 = "90.3388000000000000.1786",
    version3 = "9441861",
)

val demoState = AirPodsUiState(
    deviceName = demoInstance.name,

    isLocallyConnected = true,
    hasCurrentSessionInformation = true,

    capabilities = demoInstance.model.capabilities,
    supportsAdaptiveAudio = demoInstance.model.capabilities.contains(Capability.ADAPTIVE_AUDIO),

    battery = listOf(
        Battery(BatteryComponent.LEFT, 80, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.RIGHT, 18, BatteryStatus.CHARGING),
        Battery(BatteryComponent.CASE, 76, BatteryStatus.NOT_CHARGING)
    ),

    ancMode = 3,
    offListeningMode = false,

    modelName = demoInstance.model.displayName,
    actualModel = demoInstance.actualModelNumber,
    serialNumbers =  listOf(
        demoInstance.serialNumber?: "",
        demoInstance.leftSerialNumber?: "",
        demoInstance.rightSerialNumber?: ""
    ),

    version1 = demoInstance.version1?: "",
    version2 = demoInstance.version2?: "",
    version3 = demoInstance.version3?: "",

    headTrackingActive = true,
    headGesturesEnabled = true,

    automaticEarDetectionEnabled = true,
    automaticConnectionEnabled = true,

    leftAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    rightAction = StemAction.DIGITAL_ASSISTANT,

    loudSoundReductionEnabled = true,
    loudSoundReductionAvailable = true,

    isPremium = true,
    vendorIdHook = true,

    dynamicEndOfCharge = true,

    customEq = CustomEq(state = 2, low = 65, mid = 50, high = 70),

    controlStates = mapOf(
        ControlCommandIdentifiers.LISTENING_MODE_CONFIGS to byteArrayOf(0x0E),
        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.STEM_CONFIG to byteArrayOf(0x00),
        ControlCommandIdentifiers.CLICK_HOLD_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL to byteArrayOf(0x00),
        ControlCommandIdentifiers.VOLUME_SWIPE_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG to byteArrayOf(0x00, 0x03),
        ControlCommandIdentifiers.CHIME_VOLUME to byteArrayOf(0x46, 0x50),
        ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_AID to byteArrayOf(0x01, 0x02),
        ControlCommandIdentifiers.HPS_GAIN_SWIPE to byteArrayOf(0x01),
        ControlCommandIdentifiers.HEARING_ASSIST_CONFIG to byteArrayOf(0x02),
        ControlCommandIdentifiers.HRM_STATE to byteArrayOf(0x01),
        ControlCommandIdentifiers.AUTO_ANC_STRENGTH to byteArrayOf(0x45),
        ControlCommandIdentifiers.ONE_BUD_ANC_MODE to byteArrayOf(0x01),
        ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_TOGGLE_CONFIG to byteArrayOf(0x01),
        ControlCommandIdentifiers.PPE_CAP_LEVEL_CONFIG to byteArrayOf(0x52),
        ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE to byteArrayOf(0x01),
        ControlCommandIdentifiers.LISTENING_MODE to byteArrayOf(0x04)
    )
)

class AirPodsViewModel(

) : ViewModel() {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var appContext: Context
    private lateinit var service: AirPodsService
    private lateinit var controlRepo: ControlCommandRepository

    var isReady by mutableStateOf(false)
        private set

    fun init(service: AirPodsService, controlRepo: ControlCommandRepository, sharedPreferences: SharedPreferences, appContext: Context) {
        if (::service.isInitialized && this.service === service && isReady) {
            Log.d(TAG, "AirPodsViewModel.init reused service; refreshing state snapshot")
            loadName()
            loadInstance()
            loadCurrentStatus()
            loadEq()
            loadATT()
            return
        }

        if (::service.isInitialized) {
            isReady = false
            clearServiceObservers()
        }

        this.service = service
        val generation = controlServiceGeneration + 1
        controlServiceGeneration = generation
        _uiState.update {
            it.copy(
                controlServiceGeneration = generation,
                controlDisplayVersion = null,
                isLocallyConnected = false,
                hasCurrentSessionInformation = false
            )
        }
        this.controlRepo = controlRepo
        this.sharedPreferences = sharedPreferences
        this.appContext = appContext
        Log.d(TAG, "AirPodsViewModel.init registering observers for service instance")

        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        observeAACP()
        loadCurrentStatus()
        loadEq()
        loadATT()
        observeSharedPreferences()
        observeBilling()
        if (isDemoMode) activateDemoMode()
        isReady = true
    }

    private fun clearServiceObservers() {
        if (::service.isInitialized) {
            if (::controlRepo.isInitialized) {
                listeners.forEach { (id, listener) -> controlRepo.remove(id, listener) }
            }
            listeners.clear()

            customEqCallback?.let { callback ->
                if (service.aacpManager.customEqCallback === callback) {
                    service.aacpManager.customEqCallback = null
                }
            }

        }
        customEqCallback = null

        sharedPreferencesListener?.let { listener ->
            if (::sharedPreferences.isInitialized) {
                sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
        sharedPreferencesListener = null

        broadcastReceiver?.let { receiver ->
            if (::appContext.isInitialized) {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    // It may not have registered if init failed partway through.
                }
            }
        }
        broadcastReceiver = null
    }

    private val _uiState = MutableStateFlow(AirPodsUiState())

    val uiState: StateFlow<AirPodsUiState> = _uiState

    // The status component collects independently from the controls on the main page.
    fun settingsSyncStatus(): StateFlow<Map<String, SettingsSyncPolicy.ItemSnapshot>>? =
        if (::service.isInitialized && !isDemoMode) service.settingsSyncStatus else null

    private var isDemoMode = false
    @Volatile private var controlServiceGeneration = 0L

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private var sharedPreferencesListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var billingJob: Job? = null
    private var customEqCallback: ((CustomEq) -> Unit)? = null
    private var attStateConnectionToken: Long? = null

    private val xposedRemotePref = XposedRemotePrefProvider.create()

    private var broadcastReceiver: BroadcastReceiver? = null

//    private val _cameraAction = MutableStateFlow(
//        sharedPreferences.getString("camera_action", null)
//            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } })
//
//    val cameraAction: StateFlow<AACPManager.Companion.StemPressType?> = _cameraAction
//
//    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
//        sharedPreferences.edit {
//            if (action == null) remove("camera_action")
//            else putString("camera_action", action.name)
//        }
//        _cameraAction.value = action
//    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        val token = if (::service.isInitialized) service.getNameConnectionToken() else null
        setCustomEq(low, mid, high, token)
    }

    fun setCustomEq(low: Int, mid: Int, high: Int, expectedConnectionToken: Long?) {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        updateCustomEq(_uiState.value.customEq.copy(low = low, mid = mid, high = high), expectedConnectionToken)
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        val token = if (::service.isInitialized) service.getNameConnectionToken() else null
        setCustomEqEnabled(enabled, token)
    }

    fun setCustomEqEnabled(enabled: Boolean, expectedConnectionToken: Long?) {
        updateCustomEq(_uiState.value.customEq.copy(state = if (enabled) 2 else 1), expectedConnectionToken)
    }

    private fun updateCustomEq(updatedEq: CustomEq, expectedConnectionToken: Long?) {
        val boundService = if (::service.isInitialized) service else null
        if (!isDemoMode && (boundService == null || expectedConnectionToken == null ||
                boundService.getNameConnectionToken() != expectedConnectionToken)) return
        val accepted = isDemoMode || boundService!!.setUserCustomEq(updatedEq, expectedConnectionToken)
        if (!isDemoMode && (service !== boundService || boundService!!.getNameConnectionToken() != expectedConnectionToken)) return
        if (!accepted) {
            Log.w(TAG, "Custom EQ was not saved locally")
            return
        }
        _uiState.update { it.copy(customEq = if (isDemoMode) updatedEq else boundService!!.getLocalDesiredCustomEq() ?: updatedEq) }
    }

    override fun onCleared() {
        clearServiceObservers()
        billingJob?.cancel()
        billingJob = null
    }

    private fun loadName() {
        _uiState.update { it.copy(
            deviceName = service.getCurrentDeviceName(),
            nameConnectionToken = service.getNameConnectionToken()
        ) }
    }

    private fun observeBilling() {
        if (isDemoMode || billingJob?.isActive == true) return
        billingJob = viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        setHeadGesturesEnabled(false)
                        _uiState.update { it.copy(isPremium = false) }
                    }
                }
            }
        }
    }

    private fun observeSharedPreferences() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "head_gestures", "left_long_press_action", "right_long_press_action",
                "foss_upgraded", "premium_expiry_time" -> loadSharedPreferences()
            }
        }
        sharedPreferencesListener = listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun observeBroadcasts() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    "me.kavishdevar.librepods.CONTROL_SETTINGS_READY" -> {
                        loadCurrentStatus()
                        loadEq()
                        loadATT()
                    }

                    "me.kavishdevar.librepods.ATT_SETTINGS_UPDATED" -> loadATT()

                    AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        clearATTState()
                        loadName()
                        _uiState.update {
                            it.copy(
                                isLocallyConnected = service.getControlDisplaySnapshot() != null,
                                hasCurrentSessionInformation = service.hasCurrentSessionInformation(),
                                // Local selections remain visible until the new session is loaded.
                                controlStates = it.controlStates
                            )
                        }
                        // Connected broadcasts can follow CONTROL_SETTINGS_READY.
                        // Rebuild from this session so clearing old UI never drops desired values.
                        loadCurrentStatus()
                        loadEq()
                        loadATT()
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        val boundService = service
                        val generation = controlServiceGeneration
                        val observed = _uiState.value
                        val hasToken = intent.hasExtra("control_connection_token")
                        val targetToken = if (hasToken) {
                            intent.getLongExtra("control_connection_token", Long.MIN_VALUE)
                                .takeUnless { it == Long.MIN_VALUE } ?: return
                        } else {
                            observed.controlDisplayVersion?.connectionToken ?: observed.nameConnectionToken
                        }
                        boundService.withControlConnectionToken { activeToken ->
                            fun belongsTo(state: AirPodsUiState): Boolean =
                                service === boundService && generation == controlServiceGeneration &&
                                    generation == state.controlServiceGeneration && shouldCloseControlDisplay(
                                        state.controlDisplayVersion?.connectionToken ?: state.nameConnectionToken,
                                        targetToken, activeToken
                                    )
                            if (!belongsTo(_uiState.value)) return@withControlConnectionToken
                            clearATTState()
                            _uiState.update { state ->
                                if (!belongsTo(state)) state else state.copy(
                                    isLocallyConnected = false,
                                    hasCurrentSessionInformation = false,
                                    nameConnectionToken = null,
                                    supportsAdaptiveAudio = false,
                                    controlDisplayVersion = state.controlDisplayVersion?.closed()
                                        ?: targetToken?.let { token -> ControlDisplayVersion(token, Long.MAX_VALUE) }
                                )
                            }
                        }
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(battery = service.getBattery())
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadName()
                        loadInstance()
                        loadCurrentStatus()
                    }
                }
            }
        }
        broadcastReceiver = receiver

        val filter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.CONTROL_SETTINGS_READY")
            addAction("me.kavishdevar.librepods.ATT_SETTINGS_UPDATED")
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            receiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun controlBoolean(value: ByteArray?): Boolean? = when (value?.firstOrNull()?.toInt()) {
        0x01 -> true
        0x02 -> false
        else -> null
    }

    private fun withControlValue(
        state: AirPodsUiState,
        identifier: ControlCommandIdentifiers,
        value: ByteArray?,
        fallback: AirPodsUiState = state
    ): AirPodsUiState {
        val updated = state.copy(
            controlStates = if (value == null) state.controlStates - identifier
                else state.controlStates + (identifier to value.copyOf())
        )
        val enabled = controlBoolean(value)
        return when (identifier) {
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG -> updated.copy(
                automaticEarDetectionEnabled = enabled ?: fallback.automaticEarDetectionEnabled
            )
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG -> updated.copy(
                automaticConnectionEnabled = enabled ?: fallback.automaticConnectionEnabled
            )
            ControlCommandIdentifiers.ALLOW_OFF_OPTION -> updated.copy(
                offListeningMode = enabled ?: fallback.offListeningMode
            )
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE -> updated.copy(
                dynamicEndOfCharge = enabled ?: fallback.dynamicEndOfCharge
            )
            else -> updated
        }
    }

    fun setControlCommandValue(identifier: ControlCommandIdentifiers, value: ByteArray) {
        val token = if (::service.isInitialized) service.getNameConnectionToken() else null
        setControlCommandValue(identifier, value, token)
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray, expectedConnectionToken: Long?
    ) {
        val payload = value.copyOf()
        if (isDemoMode) {
            _uiState.update { withControlValue(it, identifier, payload) }
            return
        }
        val boundService = if (::service.isInitialized) service else return
        if (expectedConnectionToken == null || boundService.getNameConnectionToken() != expectedConnectionToken) return
        val generation = controlServiceGeneration
        val sent = boundService.setUserControlValue(identifier, payload, expectedConnectionToken)
        if (service !== boundService || generation != controlServiceGeneration ||
            boundService.getNameConnectionToken() != expectedConnectionToken) return
        if (!sent) {
            val pending = boundService.getLocalDesiredControlValues()[identifier]
            Log.w(TAG, "Control $identifier was not written; pending locally: ${pending != null}")
        }
        refreshControlDisplay(boundService, generation)
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        val listener = controlRepo.observe(identifier) {
            // Manager callbacks may be optimistic. Rebuild from the service's ordered
            // display snapshot instead of treating a callback as a device report.
            if (isDemoMode || !::service.isInitialized) return@observe
            val boundService = service
            refreshControlDisplay(boundService, controlServiceGeneration)
        }

        listeners[identifier] = listener
    }

    // I'm lazy, sorry.
    fun observeAACP() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
        val callback: (CustomEq) -> Unit = {
            // Manager callbacks may be optimistic; only the saved value drives the UI.
            if (!isDemoMode && ::service.isInitialized) loadEq()
        }
        customEqCallback = callback
        service.aacpManager.customEqCallback = callback
    }

    private fun refreshControlDisplay(
        boundService: AirPodsService, generation: Long, refreshAnc: Boolean = false
    ) {
        // A report may arrive after taking a snapshot; retry with its newer revision.
        repeat(3) {
            if (service !== boundService || generation != controlServiceGeneration) return
            val snapshot = boundService.getControlDisplaySnapshot() ?: return
            if (applyControlDisplaySnapshot(boundService, generation, snapshot, refreshAnc)) return
        }
    }

    private fun applyControlDisplaySnapshot(
        boundService: AirPodsService, generation: Long,
        snapshot: ControlDisplaySnapshot<ControlCommandIdentifiers>, refreshAnc: Boolean
    ): Boolean {
        val controls = snapshot.values
        return boundService.withCurrentControlDisplaySnapshot(snapshot) {
            _uiState.update { state ->
                if (service !== boundService || generation != controlServiceGeneration ||
                    generation != state.controlServiceGeneration ||
                    !snapshot.version.canReplace(state.controlDisplayVersion)) return@update state
                // Actual reports do not change the local revision or main controls.
                if (snapshot.version == state.controlDisplayVersion) return@update state
                val base = state.copy(
                    controlDisplayVersion = snapshot.version,
                    ancMode = if (refreshAnc) controls[ControlCommandIdentifiers.LISTENING_MODE]
                        ?.firstOrNull()?.toInt() ?: 1 else state.ancMode,
                    controlStates = emptyMap(),
                    offListeningMode = true,
                    automaticEarDetectionEnabled = true,
                    automaticConnectionEnabled = true,
                    dynamicEndOfCharge = false
                )
                controls.entries.fold(base) { current, (identifier, value) ->
                    withControlValue(current, identifier, value)
                }
            }
        }
    }

    fun loadCurrentStatus() {
        if (isDemoMode) return
        val boundService = service
        val generation = controlServiceGeneration
        boundService.withControlConnectionToken { connectionToken ->
            val name = boundService.getCurrentDeviceName()
            val battery = boundService.getBattery()
            val connected = connectionToken != null && BluetoothConnectionManager.aacpSocket?.isConnected == true &&
                boundService.getControlDisplaySnapshot() != null
            val hasCurrentSessionInformation =
                connected && boundService.hasCurrentSessionInformation()
            val supportsAdaptive = connected && boundService.supportsAdaptiveAudioForCurrentSession()
            _uiState.update { state ->
                if (service !== boundService || generation != controlServiceGeneration ||
                    generation != state.controlServiceGeneration) state else state.copy(
                    isLocallyConnected = connected,
                    hasCurrentSessionInformation = hasCurrentSessionInformation,
                    nameConnectionToken = connectionToken,
                    supportsAdaptiveAudio = supportsAdaptive,
                    deviceName = name,
                    battery = battery
                )
            }
        }
        refreshControlDisplay(boundService, generation, refreshAnc = true)
    }

    private fun loadSharedPreferences() {
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures", true)
        val leftAction = StemAction.valueOf(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val rightAction = StemAction.valueOf(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false)

        val connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)

        _uiState.update {
            it.copy(
                headGesturesEnabled = headGesturesEnabled,
                leftAction = leftAction,
                rightAction = rightAction,
                vendorIdHook = vendorIdHook,
                connectionSuccessful = connectionSuccessful,
            )
        }

        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase
        if (BuildConfig.PLAY_BUILD) {
            val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
            val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
            val now = System.currentTimeMillis()

            when {
                // existing temporary premium
                expiryTime > 0L -> {
                    if (expiryTime <= now) {
                        sharedPreferences.edit {
                            remove("premium_expiry_time")
                            remove("foss_upgraded")
                        }

                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = 0L,
                                isPremium = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                timeUntilFOSSPremiumExpiry = expiryTime - now,
                                isPremium = true
                            )
                        }
                    }
                }

                // First migration from accidental FOSS Play build
                fossUpgraded && !_uiState.value.isPremium -> {
                    val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                    sharedPreferences.edit {
                        putLong("premium_expiry_time", newExpiry)
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = newExpiry - now,
                            isPremium = true
                        )
                    }
                }
            }
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setDynamicEndOfCharge(enabled: Boolean) {
        setControlCommandBoolean(ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE, enabled)
    }

    private fun loadEq() {
        if (isDemoMode) return
        val eq = service.getLocalDesiredCustomEq() ?: CustomEq(1, 50, 50, 50)
        _uiState.update { it.copy(customEq = eq) }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance ?: AirPodsInstance(
            name = "AirPods",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = null,
            leftSerialNumber = null,
            rightSerialNumber = null,
            version1 = null,
            version2 = null,
            version3 = null,
        )

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(
                    instance.serialNumber ?: "",
                    instance.leftSerialNumber ?: "",
                    instance.rightSerialNumber ?: ""
                ),
                version1 = instance.version1 ?: "",
                version2 = instance.version2 ?: "",
                version3 = instance.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        service.reconnectFromSavedMac()
    }

    fun setName(name: String, connectionToken: Long?): Boolean {
        if (!::service.isInitialized || isDemoMode) return false
        return service.setName(name, connectionToken)
    }

    fun startHeadTracking() {
        service.startHeadTracking()
        _uiState.update { it.copy(headTrackingActive = true) }
    }

    fun stopHeadTracking() {
        service.stopHeadTracking()
        _uiState.update { it.copy(headTrackingActive = false) }
    }

    private fun clearATTState() {
        // Saved selections are retained; only the active session's token is cleared.
        attStateConnectionToken = null
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray) {
        val token = if (::service.isInitialized) service.getNameConnectionToken() else null
        setATTCharacteristicValue(handle, value, token)
    }

    fun setATTCharacteristicValue(handle: ATTHandles, value: ByteArray, expectedConnectionToken: Long?) {
        // Composite settings use the typed local API; raw UI writes are restricted to LSR.
        if (handle != ATTHandles.LOUD_SOUND_REDUCTION || value.size != 1 || value[0].toInt() !in 0..1) return
        if (isDemoMode) {
            _uiState.update { it.copy(loudSoundReductionEnabled = value[0].toInt() == 1, loudSoundReductionAvailable = true) }
            return
        }
        val boundService = if (::service.isInitialized) service else return
        val token = boundService.getNameConnectionToken() ?: return
        if (token != expectedConnectionToken || attStateConnectionToken != token) return
        if (boundService.setUserAttValue(handle, value.copyOf(), token) &&
            service === boundService && boundService.getNameConnectionToken() == token) loadATT()
    }

    fun setCompositeAttSettings(settings: CompositeAttSettings, expectedConnectionToken: Long?): Boolean {
        if (isDemoMode) {
            _uiState.update {
                when (settings.handle) {
                    ATTHandles.TRANSPARENCY -> it.copy(transparencySettings = settings)
                    ATTHandles.HEARING_AID -> it.copy(hearingAidSettings = settings)
                    else -> it
                }
            }
            return true
        }
        val boundService = if (::service.isInitialized) service else return false
        val token = boundService.getNameConnectionToken() ?: return false
        if (token != expectedConnectionToken || attStateConnectionToken != token) return false
        val accepted = boundService.setUserCompositeAttSettings(settings, token)
        if (accepted && service === boundService && boundService.getNameConnectionToken() == token) loadATT()
        return accepted
    }

    fun loadATT() {
        if (isDemoMode || !::service.isInitialized) return
        val boundService = service
        val token = boundService.getNameConnectionToken()
        if (token == null) {
            clearATTState()
            return
        }
        val lsr = boundService.getLocalDesiredAttValues()[ATTHandles.LOUD_SOUND_REDUCTION]
        val transparency = boundService.getCompositeAttDesired(ATTHandles.TRANSPARENCY)
        val hearingAid = boundService.getCompositeAttDesired(ATTHandles.HEARING_AID)
        if (service !== boundService || boundService.getNameConnectionToken() != token) return
        attStateConnectionToken = token
        _uiState.update {
            it.copy(
                loudSoundReductionEnabled = lsr?.firstOrNull()?.toInt() == 1,
                loudSoundReductionAvailable = true,
                transparencySettings = transparency,
                hearingAidSettings = hearingAid
            )
        }
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
    }

    fun activateDemoMode() {
        isDemoMode = true
        _uiState.update { demoState.copy(controlServiceGeneration = controlServiceGeneration, controlDisplayVersion = null) }
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        if (modeBit !in setOf(0x01, 0x02, 0x04, 0x08)) return
        val identifier = ControlCommandIdentifiers.LISTENING_MODE_CONFIGS
        val current = uiState.value.controlStates[identifier]
            ?: if (!isDemoMode) service.getLocalDesiredControlValues()[identifier] else null
        val currentByte = current?.firstOrNull()?.toInt()?.and(0xFF)
            ?.takeIf { (it and 0xF0) == 0 && countEnabledModes(it) >= 2 } ?: return
        val newValue = if ((currentByte and modeBit) != 0) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) temp else currentByte
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
    }

    fun disconnect() {
        if (isDemoMode) {
            isDemoMode = false
            _uiState.update {
                it.copy(
                    isLocallyConnected = false,
                    hasCurrentSessionInformation = false,
                    nameConnectionToken = null,
                    supportsAdaptiveAudio = false,
                    controlStates = emptyMap(),
                    controlServiceGeneration = controlServiceGeneration,
                    controlDisplayVersion = null
                )
            }
        } else {
            service.disconnectAirPods()
            if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    appContext, "App has disconnected, disconnect from Android Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
