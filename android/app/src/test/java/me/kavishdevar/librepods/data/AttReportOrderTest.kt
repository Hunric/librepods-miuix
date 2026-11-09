package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class AttReportOrderTest {
    private val key = "att:1b"
    private val desired = byteArrayOf(1)

    @Test
    fun oldQueuedReportCannotConfirmAfterWriteButNewNotificationCan() {
        val order = AttReportOrder<String>()
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device", 1, mapOf(key to desired))
        policy.ready(session, 0)
        val oldReport = order.record(key, desired)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        order.markWrite(key, request.revision, request.sequence)
        assertFalse(order.isFreshForWrite(key, oldReport, request.revision, request.sequence))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.UNCONFIRMED, 1))
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF, policy.snapshot(session).getValue(key).phase)
        assertFalse(order.isFresh(key, oldReport)) // old coroutine delivered during BACKOFF
        val notify = order.record(key, desired)
        assertTrue(order.isFresh(key, notify))
        policy.observeActual(session, key, notify.value, 2)
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue(key).phase)
    }

    @Test
    fun notificationStagedDuringWriteCanConfirmWithoutARead() {
        val order = AttReportOrder<String>()
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device", 2, mapOf(key to desired))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        order.markWrite(key, request.revision, request.sequence)
        val notify = order.record(key, desired)
        assertTrue(order.isFreshForWrite(key, notify, request.revision, request.sequence))
        policy.observeActual(session, key, notify.value, 1) // replay while SENDING
        assertEquals(SettingsSyncPolicy.Phase.SENDING, policy.snapshot(session).getValue(key).phase)
        policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, 2)
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue(key).phase)
    }

    @Test
    fun editsAttemptsAndEpochsCannotReuseOlderReports() {
        val order = AttReportOrder<String>()
        val beforeEdit = order.record(key, byteArrayOf(0))
        order.markEdit(key)
        assertFalse(order.isFresh(key, beforeEdit))
        val afterEdit = order.record(key, desired)
        assertTrue(order.isFresh(key, afterEdit))
        order.markWrite(key, 3, 11)
        assertFalse(order.isFreshForWrite(key, afterEdit, 3, 11))
        val afterWrite = order.record(key, desired)
        assertTrue(order.isFreshForWrite(key, afterWrite, 3, 11))
        assertFalse(order.isFreshForWrite(key, afterWrite, 4, 11))
        assertFalse(order.isFreshForWrite(key, afterWrite, 3, 12))
        order.markEdit(key)
        assertFalse(order.isFresh(key, afterWrite))
        assertFalse(order.isFreshForWrite(key, afterWrite, 3, 11))
        val replacement = AttReportOrder<String>()
        replacement.markWrite(key, 3, 11)
        assertFalse(replacement.isFreshForWrite(key, afterWrite, 3, 11))
    }

    @Test
    fun notSentAndRemoteRejectionDoNotPretendAnAttemptSucceeded() {
        for (failure in listOf(SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED, SettingsSyncPolicy.WriteResult.DEVICE_REJECTED)) {
            val order = AttReportOrder<String>()
            val policy = SettingsSyncPolicy()
            val session = policy.begin("device", 3, mapOf(key to desired))
            policy.ready(session, 0)
            val request = policy.time(session, 0).single()
            policy.startWrite(session, request)
            if (failure == SettingsSyncPolicy.WriteResult.DEVICE_REJECTED) order.markWrite(key, request.revision, request.sequence)
            val report = order.record(key, desired)
            if (failure == SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED)
                assertFalse(order.isFreshForWrite(key, report, request.revision, request.sequence))
            policy.completeWrite(session, request, failure, 1)
            if (order.isFresh(key, report)) policy.observeActual(session, key, report.value, 2)
            assertEquals(SettingsSyncPolicy.Phase.BACKOFF, policy.snapshot(session).getValue(key).phase)
        }
    }

    @Test
    fun storedReportsOwnTheirBytesAndOnlyTheLatestIsEligible() {
        val order = AttReportOrder<String>()
        val input = byteArrayOf(1)
        val first = order.record(key, input)
        input[0] = 2
        val copy = first.value
        copy[0] = 3
        assertArrayEquals(byteArrayOf(1), first.value)
        val next = order.record(key, byteArrayOf(4))
        assertFalse(order.isFresh(key, first))
        assertTrue(order.isFresh(key, next))
    }
}
