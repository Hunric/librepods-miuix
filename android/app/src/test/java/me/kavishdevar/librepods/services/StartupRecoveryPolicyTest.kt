package me.kavishdevar.librepods.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecoveryPolicyTest {
    @Test
    fun successfulHandshakeDoesNotRequestRecovery() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertFalse(policy.onPacket(handshakeStatus = 0x0000, isValidAacpResponse = false))
    }

    @Test
    fun firstObserved85BeforeValidDataRequestsOnlyOneRecovery() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertTrue(policy.onPacket(handshakeStatus = 0x85, isValidAacpResponse = false))
        assertFalse(policy.onPacket(handshakeStatus = 0x85, isValidAacpResponse = false))
    }

    @Test
    fun normalAacpResponsePreventsRecoveryForLater85() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertFalse(policy.onPacket(handshakeStatus = null, isValidAacpResponse = true))
        assertFalse(policy.onPacket(handshakeStatus = 0x85, isValidAacpResponse = false))
    }

    @Test
    fun silentStartupRequestsOnlyOneTimeoutRecovery() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertTrue(policy.onTimeout())
        assertFalse(policy.onTimeout())
    }

    @Test
    fun unrelatedAacpResponseDoesNotMaskAnUninitializedSession() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertFalse(policy.onPacket(handshakeStatus = null, isValidAacpResponse = true))
        assertTrue(policy.onTimeout())
    }

    @Test
    fun handshakeStatusRecoveryPreventsLaterTimeoutRecovery() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertTrue(policy.onPacket(handshakeStatus = 0x85, isValidAacpResponse = false))
        assertFalse(policy.onTimeout())
    }

    @Test
    fun retrySessionCannotRecoverAgain() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = false)

        assertFalse(policy.onPacket(handshakeStatus = 0x85, isValidAacpResponse = false))
        assertFalse(policy.onPacket(handshakeStatus = 0x85, isValidAacpResponse = false))
    }

    @Test
    fun retrySessionCannotRecoverOnTimeout() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = false)

        assertFalse(policy.onTimeout())
    }

    @Test
    fun handshakeRetryStillAllowsOneTimeoutRecovery() {
        val policy = StartupRecoveryPolicy(
            recoveryAllowed = false,
            timeoutRecoveryAllowed = true
        )

        assertTrue(policy.onTimeout())
        assertFalse(policy.onTimeout())
    }

    @Test
    fun otherNonzeroStatusesDoNotRequestRecovery() {
        val policy = StartupRecoveryPolicy(recoveryAllowed = true)

        assertFalse(policy.onPacket(handshakeStatus = 0x01, isValidAacpResponse = false))
    }
}
