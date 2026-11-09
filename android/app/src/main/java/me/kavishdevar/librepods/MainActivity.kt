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

package me.kavishdevar.librepods

// import me.kavishdevar.librepods.screens.Onboarding
// import me.kavishdevar.librepods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.android.play.core.review.ReviewManagerFactory
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.ControlCommandRepository
import me.kavishdevar.librepods.presentation.navigation.NavigationRoot
import me.kavishdevar.librepods.presentation.screens.StartupScreen
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.StartupGate
import me.kavishdevar.librepods.presentation.viewmodel.StartupViewModel
import me.kavishdevar.librepods.services.AirPodsService
import me.kavishdevar.librepods.utils.XposedState
import kotlin.io.encoding.ExperimentalEncodingApi

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        init {
            if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                System.loadLibrary("l2c_fcr_hook")
            }
        }
    }

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            LibrePodsTheme {
//                For demo screenshots
//                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
//                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

                Main()
            }
        }
    }

}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main() {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)

    val airPodsViewModel: AirPodsViewModel = viewModel()
    val startupViewModel: StartupViewModel = viewModel()

    LaunchedEffect(Unit) {
        if (BuildConfig.PLAY_BUILD) {
            val now = System.currentTimeMillis()
            val firstConn =
                sharedPreferences.getLong("first_connection_successful_time", 0L)

            val alreadyPrompted =
                sharedPreferences.getBoolean("review_prompted", false)

            val oneDay = 24 * 60 * 60 * 1000L

            if (
                firstConn != 0L &&
                !alreadyPrompted &&
                (now - firstConn) > oneDay
            ) {
                triggerReviewFlow(context as? Activity ?: return@LaunchedEffect)

                sharedPreferences.edit {
                    putBoolean("review_prompted", true)
                }
            }
        }
    }

    val onboardingComplete = remember {
        mutableStateOf(sharedPreferences.getBoolean("onboarding_complete", false))
    }

    val releaseNotesShownPrefKey = "release_notes_shown_${BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-play")}"
    val releaseNotesShown = sharedPreferences.getBoolean(releaseNotesShownPrefKey, false)

    val serviceConnection = remember(context, airPodsViewModel, sharedPreferences) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as AirPodsService.LocalBinder
                val service = binder.getService()
                Log.d("MainActivity", "AirPodsService connected")
                airPodsViewModel.init(
                    service = service,
                    controlRepo = ControlCommandRepository(service.aacpManager),
                    sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE),
                    appContext = context.applicationContext
                )

                if (!sharedPreferences.contains("first_connection_successful_time")) {
                    sharedPreferences.edit {
                        putLong("first_connection_successful_time", System.currentTimeMillis())
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d("MainActivity", "AirPodsService disconnected")
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle, serviceConnection, onboardingComplete.value) {
        var isBound = false
        var serviceStartRequested = false

        fun bindServiceIfNeeded() {
            if (!onboardingComplete.value || isBound) return

            if (!serviceStartRequested) {
                context.startForegroundService(Intent(context, AirPodsService::class.java))
                serviceStartRequested = true
            }
            isBound = context.bindService(
                Intent(context, AirPodsService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
            Log.d("MainActivity", "AirPodsService bind requested; bound=$isBound")
        }

        fun unbindServiceIfNeeded() {
            if (!isBound) return
            context.unbindService(serviceConnection)
            isBound = false
            Log.d("MainActivity", "AirPodsService unbound")
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bindServiceIfNeeded()
                Lifecycle.Event.ON_STOP -> unbindServiceIfNeeded()
                else -> Unit
            }
        }

        lifecycle.addObserver(lifecycleObserver)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            bindServiceIfNeeded()
        }

        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            unbindServiceIfNeeded()
        }
    }

    LaunchedEffect(startupViewModel) {
        while (!startupViewModel.finished) {
            val state = airPodsViewModel.uiState.value
            val hasUsableEarBattery = state.battery.any { battery ->
                StartupGate.isUsableEarBattery(
                    isEar = battery.component == BatteryComponent.LEFT || battery.component == BatteryComponent.RIGHT,
                    level = battery.level,
                    isAvailable = battery.status == BatteryStatus.CHARGING ||
                        battery.status == BatteryStatus.NOT_CHARGING ||
                        battery.status == BatteryStatus.OPTIMIZED_CHARGING,
                )
            }
            startupViewModel.update(
                onboardingComplete = onboardingComplete.value,
                serviceReady = airPodsViewModel.isReady,
                locallyConnected = state.isLocallyConnected,
                a2dpPending = !state.isLocallyConnected && hasPendingA2dpConnection(context),
                hasUsableEarBattery = hasUsableEarBattery,
                requiresListeningMode = Capability.LISTENING_MODE in state.capabilities,
                hasListeningMode = state.controlStates[ControlCommandIdentifiers.LISTENING_MODE]?.isNotEmpty() == true,
            )
            if (!startupViewModel.finished) delay(150)
        }
    }

    AnimatedContent(
        targetState = startupViewModel.finished,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState) {
                (
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 380,
                            delayMillis = 30,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + scaleIn(
                        animationSpec = tween(
                            durationMillis = 380,
                            easing = FastOutSlowInEasing,
                        ),
                        initialScale = 0.985f,
                    )
                ) togetherWith (
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 220,
                            easing = LinearOutSlowInEasing,
                        ),
                    ) + scaleOut(
                        animationSpec = tween(
                            durationMillis = 220,
                            easing = LinearOutSlowInEasing,
                        ),
                        targetScale = 1.012f,
                    )
                )
            } else {
                fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(180, easing = LinearOutSlowInEasing))
            }
        },
        label = "startupToApp",
    ) { finished ->
        if (!finished) {
            StartupScreen()
        } else {
            NavigationRoot(
//        showReleaseNotes = !releaseNotesShown,
//        updatesShown = { sharedPreferences.edit { putBoolean(releaseNotesShownPrefKey, true) } },
                showOnboarding = !onboardingComplete.value,
                onboardingComplete = {
                    sharedPreferences.edit { putBoolean("onboarding_complete", true) }
                    onboardingComplete.value = true
                },
                airPodsViewModel = airPodsViewModel
            )
        }
    }
}

private fun triggerReviewFlow(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    val request = manager.requestReviewFlow()
    request.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val reviewInfo = task.result
            manager.launchReviewFlow(activity, reviewInfo)
        }
    }
}

// The profile query is only a startup hint; unavailable Bluetooth must not block the UI.
@SuppressLint("MissingPermission")
private fun hasPendingA2dpConnection(context: Context): Boolean = try {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter == null || !adapter.isEnabled) {
        false
    } else {
        when (adapter.getProfileConnectionState(BluetoothProfile.A2DP)) {
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING -> true
            else -> false
        }
    }
} catch (_: Exception) {
    false
}
