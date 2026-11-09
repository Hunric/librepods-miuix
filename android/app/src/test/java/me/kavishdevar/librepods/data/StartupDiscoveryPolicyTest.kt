package me.kavishdevar.librepods.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDiscoveryPolicyTest {
    private fun candidate(
        id: String, saved: Boolean = false, uuid: Boolean = false,
        reports: Boolean = false, successful: Boolean = false,
    ) = StartupDiscoveryPolicy.Candidate(id, saved, uuid, reports, successful)

    @Test
    fun savedConnectedDeviceWithRealReportsNeedsNoCachedUuid() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("saved", saved = true, reports = true), candidate("other")))
        assertEquals("saved", selection.candidate?.device)
        assertEquals(StartupDiscoveryPolicy.Outcome.SAVED, selection.outcome)
    }

    @Test
    fun lastSuccessfulAddressIsEvidenceWithoutUuidOrReports() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("saved", saved = true, successful = true), candidate("other")))
        assertEquals("saved", selection.candidate?.device)
        assertEquals(StartupDiscoveryPolicy.Outcome.SAVED, selection.outcome)
    }

    @Test
    fun successEvidenceDoesNotSelectANonPreferredCandidate() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("old", successful = true)))
        assertNull(selection.candidate)
        assertEquals(StartupDiscoveryPolicy.Outcome.NONE, selection.outcome)
    }

    @Test
    fun otherConnectedAudioDeviceAndUnverifiedSavedAddressAreNotSelected() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("other"), candidate("saved", saved = true)))
        assertNull(selection.candidate)
        assertEquals(StartupDiscoveryPolicy.Outcome.NONE, selection.outcome)
    }

    @Test
    fun historicalDeviceAbsentFromConnectedSetCannotBeSelected() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("connected", uuid = true)))
        assertEquals("connected", selection.candidate?.device)
        assertEquals(StartupDiscoveryPolicy.Outcome.UUID, selection.outcome)
    }

    @Test
    fun multipleUuidMatchesWithoutSupportedSavedDeviceAreAmbiguous() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("a", uuid = true), candidate("b", uuid = true)))
        assertNull(selection.candidate)
        assertEquals(StartupDiscoveryPolicy.Outcome.AMBIGUOUS, selection.outcome)
    }

    @Test
    fun supportedSavedCandidateWinsOverOtherUuidMatches() {
        val selection = StartupDiscoveryPolicy.select(listOf(candidate("other", uuid = true), candidate("saved", saved = true, reports = true)))
        assertEquals("saved", selection.candidate?.device)
        assertEquals(StartupDiscoveryPolicy.Outcome.SAVED, selection.outcome)
    }

    @Test
    fun duplicateEventsDuringQueryAndAttemptCauseOnlyOneFollowUp() {
        val gate = StartupDiscoveryGate()
        val first = gate.request()
        assertEquals(StartupDiscoveryGate.Admission.STARTED, first.admission)
        assertEquals(StartupDiscoveryGate.Admission.COALESCED, gate.request().admission)
        assertTrue(gate.acceptCallback(first.generation))
        assertEquals(StartupDiscoveryGate.Admission.COALESCED, gate.request().admission)
        assertFalse(gate.mayConnect(first.generation, candidateConnected = false))
        assertTrue(gate.mayConnect(first.generation, candidateConnected = true))
        val next = gate.finish(first.generation).nextGeneration!!
        assertTrue(gate.acceptCallback(next))
        assertNull(gate.finish(next).nextGeneration)
    }

    @Test
    fun timeoutInvalidatesOldCallbackAndOnlyOneCoalescedFollowUpStarts() {
        val gate = StartupDiscoveryGate()
        val first = gate.request()
        gate.request()
        val next = gate.finish(first.generation, waitingOnly = true).nextGeneration!!
        assertFalse(gate.acceptCallback(first.generation))
        assertTrue(gate.acceptCallback(next))
        assertFalse(gate.finish(next, waitingOnly = true).accepted)
        assertNull(gate.finish(next).nextGeneration)
    }

    @Test
    fun successfulSocketClaimPreventsLateTimeoutFromClosingIt() {
        val attempt = StartupConnectAttempt()
        assertTrue(attempt.claimConnected())
        assertFalse(attempt.cancelPending())
        assertFalse(attempt.cancelPending())
    }

    @Test
    fun timeoutWinningPreventsLaterSuccessClaim() {
        val attempt = StartupConnectAttempt()
        assertTrue(attempt.cancelPending())
        assertFalse(attempt.claimConnected())
        assertFalse(attempt.cancelPending())
    }

    @Test
    fun destroyOrTimeoutCanCloseOnlyThePendingCandidateOnce() {
        val pending = StartupConnectAttempt()
        val established = StartupConnectAttempt()
        assertTrue(established.claimConnected())
        val closed = mutableListOf<String>()
        if (pending.cancelPending()) closed += "candidate"
        if (pending.cancelPending()) closed += "candidate"
        if (established.cancelPending()) closed += "established"
        assertEquals(listOf("candidate"), closed)
    }

    @Test
    fun claimedCandidateCanBeAbandonedOnceWhenDestroyArrivesBeforeInstallation() {
        val attempt = StartupConnectAttempt()
        assertTrue(attempt.claimConnected())
        assertTrue(attempt.abandonClaimed())
        assertFalse(attempt.abandonClaimed())
        assertFalse(attempt.cancelPending())
    }

    @Test
    fun destroyInvalidatesLateCallbackAndOutstandingAttempt() {
        val gate = StartupDiscoveryGate()
        val token = gate.request().generation
        assertTrue(gate.acceptCallback(token))
        gate.destroy()
        assertFalse(gate.mayConnect(token, candidateConnected = true))
        assertFalse(gate.acceptCallback(token))
        assertFalse(gate.finish(token).accepted)
        assertEquals(StartupDiscoveryGate.Admission.DESTROYED, gate.request().admission)
    }
}
