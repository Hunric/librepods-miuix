package me.kavishdevar.librepods.presentation.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupGateTest {
    private fun finish(
        elapsedMs: Long = 1_500L,
        onboardingComplete: Boolean = true,
        serviceReady: Boolean = true,
        locallyConnected: Boolean = true,
        a2dpPending: Boolean = false,
        hasUsableEarBattery: Boolean = true,
        requiresListeningMode: Boolean = true,
        hasListeningMode: Boolean = true,
    ) = StartupGate.shouldFinish(
        elapsedMs, onboardingComplete, serviceReady, locallyConnected, a2dpPending,
        hasUsableEarBattery, requiresListeningMode, hasListeningMode,
    )

    @Test
    fun alwaysHonorsMinimumDuration() {
        assertFalse(finish(elapsedMs = 1_499))
        assertFalse(finish(elapsedMs = 1_499, onboardingComplete = false))
        assertFalse(finish(elapsedMs = 1_499, locallyConnected = false))
        assertTrue(finish(elapsedMs = 1_500))
    }

    @Test
    fun absoluteDeadlineReleasesEvenWithoutServiceOrDeviceData() {
        assertFalse(finish(elapsedMs = 3_999, serviceReady = false))
        assertTrue(finish(elapsedMs = 4_000, serviceReady = false, hasUsableEarBattery = false))
        assertTrue(finish(elapsedMs = 4_150, locallyConnected = false, a2dpPending = true))
        assertTrue(finish(elapsedMs = 4_000, hasUsableEarBattery = false, hasListeningMode = false))
    }

    @Test
    fun onboardingDoesNotWaitForUnstartedService() {
        assertTrue(finish(onboardingComplete = false, serviceReady = false))
        assertFalse(finish(serviceReady = false))
    }

    @Test
    fun offlineReleasesButAudioConnectionStillInProgressWaits() {
        assertTrue(finish(locallyConnected = false, hasUsableEarBattery = false))
        assertFalse(finish(locallyConnected = false, a2dpPending = true))
        // An established local session uses its data even when A2DP is connected.
        assertTrue(finish(a2dpPending = true))
    }

    @Test
    fun waitsForBatteryAndOnlySupportedListeningControl() {
        assertFalse(finish(hasUsableEarBattery = false))
        assertFalse(finish(hasListeningMode = false))
        assertTrue(finish(requiresListeningMode = false, hasListeningMode = false))
        assertTrue(finish())
    }

    @Test
    fun batteryMustBeAnAvailableEarWithValidLevel() {
        assertFalse(StartupGate.isUsableEarBattery(isEar = true, level = 0, isAvailable = false))
        assertFalse(StartupGate.isUsableEarBattery(isEar = false, level = 90, isAvailable = true))
        assertFalse(StartupGate.isUsableEarBattery(isEar = true, level = -1, isAvailable = true))
        assertFalse(StartupGate.isUsableEarBattery(isEar = true, level = 101, isAvailable = true))
        assertTrue(StartupGate.isUsableEarBattery(isEar = true, level = 0, isAvailable = true))
        assertTrue(StartupGate.isUsableEarBattery(isEar = true, level = 100, isAvailable = true))
    }

    @Test
    fun connectionInterstitialWaitsForCurrentSessionInformationAtRoot() {
        assertTrue(ConnectionTransitionGate.shouldShowInterstitial(
            atRoot = true,
            locallyConnected = true,
            informationReady = false,
        ))
        assertFalse(ConnectionTransitionGate.shouldShowInterstitial(
            atRoot = true,
            locallyConnected = true,
            informationReady = true,
        ))
        assertFalse(ConnectionTransitionGate.shouldShowInterstitial(
            atRoot = true,
            locallyConnected = false,
            informationReady = false,
        ))
        assertFalse(ConnectionTransitionGate.shouldShowInterstitial(
            atRoot = false,
            locallyConnected = true,
            informationReady = false,
        ))
    }
}
