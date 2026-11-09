package me.kavishdevar.librepods.presentation.viewmodel

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Activity-scoped: configuration changes keep the deadline; a new process starts fresh. */
class StartupViewModel : ViewModel() {
    private val startedAtMs = SystemClock.elapsedRealtime()

    var finished by mutableStateOf(false)
        private set

    fun update(
        onboardingComplete: Boolean,
        serviceReady: Boolean,
        locallyConnected: Boolean,
        a2dpPending: Boolean,
        hasUsableEarBattery: Boolean,
        requiresListeningMode: Boolean,
        hasListeningMode: Boolean,
    ) {
        if (finished) return
        finished = StartupGate.shouldFinish(
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            onboardingComplete = onboardingComplete,
            serviceReady = serviceReady,
            locallyConnected = locallyConnected,
            a2dpPending = a2dpPending,
            hasUsableEarBattery = hasUsableEarBattery,
            requiresListeningMode = requiresListeningMode,
            hasListeningMode = hasListeningMode,
        )
    }
}
