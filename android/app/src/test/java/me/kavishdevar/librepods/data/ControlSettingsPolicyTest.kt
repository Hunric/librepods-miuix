package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class ControlSettingsPolicyTest {
    private fun ready(policy: ControlSettingsPolicy, identity: String = "device-a"): Long {
        val session = policy.beginSession(identity)
        assertTrue(policy.acknowledgeHandshake(session))
        assertTrue(policy.acknowledgeFeatures(session))
        assertTrue(policy.markReady(session))
        return session
    }

    @Test
    fun emptyConfigurationNeverPlansDefaults() {
        val policy = ControlSettingsPolicy()
        assertTrue(policy.planSends(ready(policy)).isEmpty())
        assertTrue(policy.desiredValues().isEmpty())
    }

    @Test
    fun knownReportedValuesCannotBeLoadedPersistedOrRestored() {
        val policy = ControlSettingsPolicy(setOf(0x0D, 0x1F))
        assertNull(policy.loadDesired(0x0D, byteArrayOf(3)))
        assertNull(policy.setUserIntent(0x1F, byteArrayOf(75, 0x50)))
        assertTrue(policy.isDeviceReported(0x0D))
        assertTrue(policy.planSends(ready(policy)).isEmpty())
    }

    @Test
    fun unobservedLongPressIntentIsSavedAndRestoredWithoutInventingAValue() {
        val policy = ControlSettingsPolicy()
        val value = byteArrayOf(0x0B, 0, 0, 0)
        val stored = policy.setUserIntent(0x1A, value)!!
        assertArrayEquals(value, stored.value)
        assertEquals(0x1A, stored.identifier)
        val request = policy.planSends(ready(policy)).single()
        assertArrayEquals(value, request.value)
        assertTrue(policy.completeSend(request, true))
    }

    @Test
    fun readinessRequiresBothAcksAndCurrentSession() {
        val policy = ControlSettingsPolicy()
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val session = policy.beginSession("device-a")
        assertFalse(policy.markReady(session))
        assertTrue(policy.acknowledgeFeatures(session))
        assertFalse(policy.markReady(session))
        assertTrue(policy.planSends(session).isEmpty())
        assertTrue(policy.acknowledgeHandshake(session))
        assertTrue(policy.markReady(session))
        assertEquals(1, policy.planSends(session).size)
    }

    @Test
    fun trueReportDeletesDesiredAndPermanentlyStopsReplay() {
        val policy = ControlSettingsPolicy()
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val session = ready(policy)
        val request = policy.planSends(session).single()
        val report = policy.acceptReported(session, 0x1A, byteArrayOf(15, 0, 0, 0))!!
        assertTrue(report.removeStoredValue)
        assertArrayEquals(byteArrayOf(15, 0, 0, 0), report.value)
        assertTrue(policy.desiredValues().isEmpty())
        assertFalse(policy.isCurrent(request))
        assertFalse(policy.completeSend(request, true))
        assertNull(policy.setUserIntent(0x1A, byteArrayOf(3)))
        assertNull(policy.loadDesired(0x1A, byteArrayOf(3)))
        assertTrue(policy.planSends(ready(policy)).isEmpty())
    }

    @Test
    fun realReportWithNewFirmwareBitsStillBecomesDeviceSource() {
        val policy = ControlSettingsPolicy()
        val session = ready(policy)
        assertNotNull(policy.acceptReported(session, 0x1A, byteArrayOf(0x40)))
        assertTrue(policy.isDeviceReported(0x1A))
    }

    @Test
    fun oldAndClosedSessionPacketsCannotDeleteCurrentIntent() {
        val policy = ControlSettingsPolicy()
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val old = ready(policy)
        val current = ready(policy)
        assertNull(policy.acceptReported(old, 0x1A, byteArrayOf(3)))
        assertFalse(policy.acknowledgeHandshake(old))
        assertFalse(policy.markReady(old))
        policy.terminate(old)
        assertEquals(1, policy.planSends(current).size)
        policy.terminate(current)
        assertNull(policy.acceptReported(current, 0x1A, byteArrayOf(3)))
        assertFalse(policy.isDeviceReported(0x1A))
        assertEquals(1, policy.desiredValues().size)
    }

    @Test
    fun repeatedReadyDoesNotDuplicateQueuedOrSuccessfulWrite() {
        val policy = ControlSettingsPolicy()
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val session = ready(policy)
        val request = policy.planSends(session).single()
        assertTrue(policy.markReady(session))
        assertTrue(policy.planSends(session).isEmpty())
        assertTrue(policy.completeSend(request, true))
        assertTrue(policy.planSends(session).isEmpty())
    }

    @Test
    fun unsuccessfulWriteCanRetryButStaleAttemptCannotCompleteRetry() {
        val policy = ControlSettingsPolicy()
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val session = ready(policy)
        val failed = policy.planSends(session).single()
        assertFalse(policy.completeSend(failed, false))
        val retry = policy.planSends(session).single()
        assertFalse(policy.completeSend(failed, true))
        assertTrue(policy.isCurrent(retry))
        assertTrue(policy.completeSend(retry, true))
    }

    @Test
    fun newerUserIntentSurvivesOldWriteCompletionAndLateDiskLoad() {
        val policy = ControlSettingsPolicy()
        policy.loadDesired(0x1A, byteArrayOf(3))
        val session = ready(policy)
        val old = policy.planSends(session).single()
        val latest = policy.setUserIntent(0x1A, byteArrayOf(15))!!
        assertTrue(latest.revision > old.revision)
        assertFalse(policy.completeSend(old, true))
        assertNull(policy.loadDesired(0x1A, byteArrayOf(3)))
        val request = policy.planSends(session).single()
        assertArrayEquals(byteArrayOf(15), request.value)
        assertEquals(latest.revision, request.revision)
        assertTrue(policy.completeSend(request, true))
    }

    @Test
    fun lateIntentAfterReadyIsPlannedAndSameValueReplaysOnNextConnection() {
        val policy = ControlSettingsPolicy()
        val first = ready(policy)
        assertTrue(policy.planSends(first).isEmpty())
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val request = policy.planSends(first).single()
        assertTrue(policy.completeSend(request, true))
        val second = ready(policy)
        val replay = policy.planSends(second).single()
        assertEquals(request.revision, replay.revision)
        assertNotEquals(request.session, replay.session)
        assertFalse(policy.completeSend(request, true))
        assertTrue(policy.completeSend(replay, true))
    }

    @Test
    fun unsupportedCommandsAndMalformedUserValuesAreRejected() {
        val policy = ControlSettingsPolicy()
        for (identifier in listOf(0x06, 0x39, 0x31, 0xFE, -1, 0x101)) {
            assertNull(policy.setUserIntent(identifier, byteArrayOf(1)))
        }
        for (value in listOf(byteArrayOf(), byteArrayOf(7, 0, 0, 0, 0), byteArrayOf(1), byteArrayOf(16), byteArrayOf(7, 1))) {
            assertNull(policy.setUserIntent(0x1A, value))
        }
        assertNull(policy.setUserIntent(0x01, byteArrayOf(3)))
        assertNull(policy.setUserIntent(0x23, byteArrayOf(0)))
        assertNull(policy.setUserIntent(0x1F, byteArrayOf(101, 0x50)))
        assertNull(policy.setUserIntent(0x24, byteArrayOf(0, 1)))
        assertNull(policy.setUserIntent(0x2C, byteArrayOf(2, 1)))
        assertTrue(policy.desiredValues().isEmpty())
    }

    @Test
    fun allSupportedUiPayloadShapesAreAcceptedWithoutPaddingOrNormalization() {
        val values = mapOf(
            0x01 to byteArrayOf(2), 0x0A to byteArrayOf(1), 0x0D to byteArrayOf(4),
            0x17 to byteArrayOf(0), 0x18 to byteArrayOf(2), 0x1A to byteArrayOf(15),
            0x1B to byteArrayOf(2), 0x1F to byteArrayOf(75, 0x50), 0x20 to byteArrayOf(1),
            0x23 to byteArrayOf(3), 0x24 to byteArrayOf(0, 3), 0x25 to byteArrayOf(1),
            0x26 to byteArrayOf(2), 0x28 to byteArrayOf(1), 0x2C to byteArrayOf(1, 2),
            0x2E to byteArrayOf(100), 0x2F to byteArrayOf(1), 0x33 to byteArrayOf(2),
            0x34 to byteArrayOf(1), 0x35 to byteArrayOf(2), 0x37 to byteArrayOf(1), 0x3B to byteArrayOf(2)
        )
        assertEquals(ControlSettingsPolicy.SUPPORTED_IDENTIFIERS, values.keys)
        val policy = ControlSettingsPolicy()
        values.forEach { (id, value) -> assertArrayEquals(value, policy.setUserIntent(id, value)!!.value) }
    }

    @Test
    fun inputAndReturnedArraysCannotMutateInternalState() {
        val policy = ControlSettingsPolicy()
        val input = byteArrayOf(7)
        val stored = policy.setUserIntent(0x1A, input)!!
        input[0] = 15
        stored.value[0] = 3
        policy.desiredValues().single().value[0] = 3
        val request = policy.planSends(ready(policy)).single()
        request.value[0] = 15
        assertArrayEquals(byteArrayOf(7), request.value)
        assertArrayEquals(byteArrayOf(7), policy.desiredValues().single().value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun policyCannotReplayOneDevicesValuesToAnother() {
        val policy = ControlSettingsPolicy()
        ready(policy, "device-a")
        ready(policy, "device-b")
    }

    @Test
    fun verifiedModelAuthorityRequiresCurrentSessionAndCancelsPendingReplay() {
        val policy = ControlSettingsPolicy()
        policy.setUserIntent(0x1A, byteArrayOf(7))
        val old = ready(policy)
        val current = ready(policy)
        val pending = policy.planSends(current).single()
        assertTrue(policy.declareKnownReported(old, setOf(0x1A)).isEmpty())
        assertFalse(policy.isDeviceReported(0x1A))
        assertEquals(setOf(0x1A), policy.declareKnownReported(current, setOf(0x1A, 0x06, 0x39, 0x31, 0xFE)))
        assertTrue(policy.isDeviceReported(0x1A))
        assertTrue(policy.desiredValues().isEmpty())
        assertFalse(policy.completeSend(pending, true))
        assertTrue(policy.planSends(current).isEmpty())
        policy.terminate(current)
        assertTrue(policy.declareKnownReported(current, setOf(0x01)).isEmpty())
        assertFalse(policy.isDeviceReported(0x01))
    }
}
