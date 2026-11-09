package me.kavishdevar.librepods.presentation.viewmodel

/** Bounded startup policy, independent of Android clocks and Bluetooth APIs. */
internal object StartupGate {
    const val MIN_DURATION_MS = 1_500L
    const val MAX_DURATION_MS = 4_000L

    fun isUsableEarBattery(isEar: Boolean, level: Int, isAvailable: Boolean): Boolean =
        isEar && level in 0..100 && isAvailable

    fun shouldFinish(
        elapsedMs: Long,
        onboardingComplete: Boolean,
        serviceReady: Boolean,
        locallyConnected: Boolean,
        a2dpPending: Boolean,
        hasUsableEarBattery: Boolean,
        requiresListeningMode: Boolean,
        hasListeningMode: Boolean,
    ): Boolean = when {
        elapsedMs < MIN_DURATION_MS -> false
        elapsedMs >= MAX_DURATION_MS -> true
        !onboardingComplete -> true
        !serviceReady -> false
        locallyConnected -> hasUsableEarBattery && (!requiresListeningMode || hasListeningMode)
        else -> !a2dpPending
    }
}

internal object ConnectionTransitionGate {
    fun shouldShowInterstitial(
        atRoot: Boolean,
        locallyConnected: Boolean,
        informationReady: Boolean,
    ): Boolean = atRoot && locallyConnected && !informationReady
}
