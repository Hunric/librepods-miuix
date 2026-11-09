package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class SettingsSyncPolicyTest {
    private val wanted = byteArrayOf(1)
    private val other = byteArrayOf(2)

    @Test
    fun startupComparesActualAndSchedulesMissingOrDifferentItemsTogether() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 7, linkedMapOf("same" to wanted, "different" to wanted, "unknown" to wanted))
        assertTrue(policy.time(session, 0).isEmpty())
        policy.observeActual(session, "same", wanted, 0)
        policy.observeActual(session, "different", other, 0)
        assertTrue(policy.ready(session, 100))
        val requests = policy.time(session, 100)
        assertEquals(listOf("different", "unknown"), requests.map { it.key })
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("same").phase)
        assertTrue(policy.time(session, 100).isEmpty())
        requests.forEach { assertTrue(policy.startWrite(session, it)) }
        requests.forEach { assertTrue(policy.completeWrite(session, it, SettingsSyncPolicy.WriteResult.SENT, 100)) }
        assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION,
            policy.snapshot(session).getValue("different").phase)
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF,
            policy.snapshot(session).getValue("unknown").phase)
    }

    @Test
    fun aWriteAckIsNotConfirmationAndReportMayArriveBeforeItsCompletion() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("eq" to wanted))
        policy.observeActual(session, "eq", other, 0)
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.observeActual(session, "eq", wanted, 1))
        assertEquals(SettingsSyncPolicy.Phase.SENDING, policy.snapshot(session).getValue("eq").phase)
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, 2))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("eq").phase)

        policy.updateDesired(session, "eq", other, 3)
        val newRequest = policy.time(session, 3).single()
        assertTrue(policy.startWrite(session, newRequest))
        assertTrue(policy.completeWrite(session, newRequest, SettingsSyncPolicy.WriteResult.SENT, 3))
        assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, policy.snapshot(session).getValue("eq").phase)
    }

    @Test
    fun noReportReceivesThreeSuccessfulSendsAndStopsWithoutWaiting() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("aacp:26" to wanted))
        policy.ready(session, 0)
        val starts = listOf(0L, 1000L, 3000L)
        for (index in starts.indices) {
            val request = policy.time(session, starts[index]).single()
            assertEquals(index + 1, request.attempt)
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, starts[index]))
            if (index < 2) {
                val item = policy.snapshot(session).getValue("aacp:26")
                assertEquals(SettingsSyncPolicy.Phase.BACKOFF, item.phase)
                assertEquals(starts[index + 1], item.nextAtMs)
                assertTrue(policy.time(session, starts[index + 1] - 1).isEmpty())
            }
        }
        val sent = policy.snapshot(session).getValue("aacp:26")
        assertEquals(3, sent.attempts)
        assertEquals(SettingsSyncPolicy.Phase.SENT_UNVERIFIED, sent.phase)
        assertNull(sent.failure)
        assertNull(sent.nextAtMs)
        assertNull(sent.actual)
        assertFalse(policy.hasRemoteObservation(session, "aacp:26"))
        assertTrue(policy.time(session, 1_000_000).isEmpty())
        assertTrue(policy.observeActual(session, "aacp:26", wanted, 1_000_001))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("aacp:26").phase)
        assertTrue(policy.time(session, 1_000_002).isEmpty())
    }

    @Test
    fun explicitRejectAndNoConfirmationRemainDifferentFailures() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        policy.ready(session, 0)
        for ((index, time) in listOf(0L, 1000L, 3000L).withIndex()) {
            val request = policy.time(session, time).single()
            assertEquals(index + 1, request.attempt)
            policy.startWrite(session, request)
            // A value observed before a rejected completion cannot confirm that write.
            policy.observeActual(session, "att:1b", wanted, time)
            policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.DEVICE_REJECTED, time)
        }
        val item = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Phase.FAILED, item.phase)
        assertEquals(SettingsSyncPolicy.Failure.DEVICE_REJECTED, item.failure)
        policy.observeActual(session, "att:1b", wanted, 8000)
        assertEquals(SettingsSyncPolicy.Phase.FAILED, policy.snapshot(session).getValue("att:1b").phase)
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun mismatchDoesNotOverrideDesiredOrResetBudgetAndCanRecoverAfterCompletion() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.observeActual(session, "k", other, 0)
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        policy.startWrite(session, request)
        policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, 0)
        policy.observeActual(session, "k", other, 100)
        assertTrue(policy.time(session, 1500).isEmpty())
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, policy.snapshot(session).getValue("k").failure)
        policy.observeActual(session, "k", wanted, 2000)
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("k").phase)
        policy.observeActual(session, "k", other, 2001)
        val failed = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.FAILED, failed.phase)
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, failed.failure)
        assertEquals(1, failed.attempts)
        assertArrayEquals(wanted, failed.desired)
        assertTrue(policy.time(session, 100_000).isEmpty())
        policy.observeActual(session, "k", wanted, 100_001)
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("k").phase)
    }

    @Test
    fun changedChoiceAndNewSessionInvalidateEveryOldRequestEvenWithSameToken() {
        val policy = SettingsSyncPolicy()
        val first = policy.begin("same-device", 4, mapOf("k" to wanted))
        policy.ready(first, 0)
        val old = policy.time(first, 0).single()
        assertEquals(2L, policy.updateDesired(first, "k", other, 1))
        assertFalse(policy.startWrite(first, old))
        val current = policy.time(first, 1).single()
        assertEquals(2L, current.revision)
        assertArrayEquals(other, current.value)
        assertTrue(policy.startWrite(first, current))
        val second = policy.begin("same-device", 4, mapOf("k" to wanted))
        assertFalse(policy.completeWrite(first, current, SettingsSyncPolicy.WriteResult.SENT, 2))
        assertFalse(policy.observeActual(first, "k", other, 2))
        assertFalse(policy.end(first))
        assertTrue(policy.ready(second, 2))
        assertEquals(1, policy.time(second, 2).size)
    }

    @Test
    fun savedChoiceCopiesAndMatcherCannotMutatePolicyData() {
        val policy = SettingsSyncPolicy(matcher = { _, desired, actual ->
            val matches = desired[0] == actual[0]
            desired[0] = 9
            actual[0] = 9
            matches
        })
        val input = byteArrayOf(1)
        val session = policy.begin("device-a", 1, mapOf("k" to input))
        input[0] = 3
        policy.observeActual(session, "k", byteArrayOf(1, 0, 0), 0)
        policy.ready(session, 0)
        val item = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, item.phase)
        item.desired[0] = 8
        item.actual!![0] = 8
        assertArrayEquals(byteArrayOf(1), policy.snapshot(session).getValue("k").desired)
        assertArrayEquals(byteArrayOf(1, 0, 0), policy.snapshot(session).getValue("k").actual)
    }

    @Test
    fun reportBeforeReservedSendMayCancelItAndFailedSendCannotConfirmFromThatReport() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("a" to wanted, "b" to wanted))
        policy.ready(session, 0)
        val requests = policy.time(session, 0).associateBy { it.key }
        policy.observeActual(session, "a", wanted, 1)
        assertFalse(policy.startWrite(session, requests.getValue("a")))
        assertEquals(0, policy.snapshot(session).getValue("a").attempts)
        val request = requests.getValue("b")
        policy.startWrite(session, request)
        policy.observeActual(session, "b", wanted, 2)
        policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, 2)
        val item = policy.snapshot(session).getValue("b")
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF, item.phase)
        assertEquals(SettingsSyncPolicy.Failure.TRANSPORT_FAILURE, item.failure)
        assertEquals(1002L, item.nextAtMs)
    }

    @Test
    fun explicitSameValueRetryStartsNewRevisionWithoutTrustingTheOldReport() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.observeActual(session, "k", wanted, 0)
        policy.ready(session, 0)
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("k").phase)
        assertEquals(2L, policy.updateDesired(session, "k", wanted, 1))
        val request = policy.time(session, 1).single()
        assertEquals(2L, request.revision)
        assertEquals(1, request.attempt)
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.UNCONFIRMED, 1))
        val item = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF, item.phase)
        assertEquals(SettingsSyncPolicy.Failure.UNCONFIRMED, item.failure)
        assertEquals(1001L, item.nextAtMs)
    }

    @Test
    fun matchingReportCanConfirmAnAttemptWhoseTransportReplyWasLost() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        policy.startWrite(session, request)
        policy.observeActual(session, "att:1b", wanted, 1)
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.UNCONFIRMED, 2))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("att:1b").phase)
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun forgettingDuringConfirmationWaitPreservesRoundAndDiscardsStaleMismatch() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        assertTrue(policy.noteRemoteObservation(session, "att:1b"))
        assertEquals(2L, policy.updateDesired(session, "att:1b", wanted, 0))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, 10))
        assertTrue(policy.observeActual(session, "att:1b", other, 20))
        val before = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, before.phase)
        assertArrayEquals(other, before.actual)

        assertTrue(policy.forgetActual(session, "att:1b"))
        val after = policy.snapshot(session).getValue("att:1b")
        assertNull(after.actual)
        assertArrayEquals(before.desired, after.desired)
        assertEquals(before.phase, after.phase)
        assertEquals(before.revision, after.revision)
        assertEquals(before.attempts, after.attempts)
        assertEquals(before.failure, after.failure)
        assertEquals(1510L, after.nextAtMs)
        assertTrue(policy.time(session, 1509).isEmpty())
        assertTrue(policy.time(session, 1510).isEmpty())
        val backoff = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Failure.UNCONFIRMED, backoff.failure)
        assertEquals(2510L, backoff.nextAtMs)
        val retry = policy.time(session, 2510).single()
        assertEquals(2, retry.attempt)
        assertEquals(before.revision, retry.revision)
    }

    @Test
    fun forgettingDuringBackoffKeepsFailureAttemptAndDeadline() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.observeActual(session, "att:1b", other, 5))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, 10))
        val before = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF, before.phase)
        assertEquals(SettingsSyncPolicy.Failure.TRANSPORT_FAILURE, before.failure)
        assertEquals(1010L, before.nextAtMs)

        assertTrue(policy.forgetActual(session, "att:1b"))
        val after = policy.snapshot(session).getValue("att:1b")
        assertNull(after.actual)
        assertArrayEquals(before.desired, after.desired)
        assertEquals(before.phase, after.phase)
        assertEquals(before.revision, after.revision)
        assertEquals(before.attempts, after.attempts)
        assertEquals(before.failure, after.failure)
        assertEquals(before.nextAtMs, after.nextAtMs)
        assertTrue(policy.time(session, 1009).isEmpty())
        assertEquals(2, policy.time(session, 1010).single().attempt)
    }

    @Test
    fun forgettingAFailedItemKeepsItsExistingFailureAndExhaustedBudget() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        policy.ready(session, 0)
        for (time in listOf(0L, 1000L, 3000L)) {
            val request = policy.time(session, time).single()
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.observeActual(session, "att:1b", other, time))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.DEVICE_REJECTED, time))
        }
        assertTrue(policy.forgetActual(session, "att:1b"))
        val item = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Phase.FAILED, item.phase)
        assertEquals(SettingsSyncPolicy.Failure.DEVICE_REJECTED, item.failure)
        assertEquals(3, item.attempts)
        assertArrayEquals(wanted, item.desired)
        assertNull(item.actual)
        assertNull(item.nextAtMs)
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun forgettingConfirmedReportsFailsWithoutRestartAndFreshReportsRestoreConfirmation() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, linkedMapOf("pre" to wanted, "written" to wanted))
        assertTrue(policy.observeActual(session, "pre", wanted, 0))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertEquals("written", request.key)
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, 0))
        assertTrue(policy.observeActual(session, "written", wanted, 1))

        for ((key, attempts) in listOf("pre" to 0, "written" to 1)) {
            val before = policy.snapshot(session).getValue(key)
            assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, before.phase)
            assertTrue(policy.forgetActual(session, key))
            val after = policy.snapshot(session).getValue(key)
            assertEquals(SettingsSyncPolicy.Phase.FAILED, after.phase)
            assertEquals(SettingsSyncPolicy.Failure.UNCONFIRMED, after.failure)
            assertEquals(attempts, after.attempts)
            assertEquals(before.revision, after.revision)
            assertArrayEquals(before.desired, after.desired)
            assertNull(after.actual)
            assertNull(after.nextAtMs)
        }
        assertTrue(policy.time(session, 100_000).isEmpty())
        assertTrue(policy.observeActual(session, "written", other, 100_001))
        assertEquals(SettingsSyncPolicy.Phase.FAILED, policy.snapshot(session).getValue("written").phase)
        assertTrue(policy.observeActual(session, "pre", wanted, 100_002))
        assertTrue(policy.observeActual(session, "written", wanted, 100_002))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("pre").phase)
        val written = policy.snapshot(session).getValue("written")
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, written.phase)
        assertEquals(1, written.attempts)
        assertTrue(policy.time(session, 200_000).isEmpty())
    }

    @Test
    fun forgettingRequiresCurrentSessionIdentityAndExistingKey() {
        val policy = SettingsSyncPolicy()
        val first = policy.begin("device-a", 7, mapOf("k" to wanted))
        val second = policy.begin("device-b", 7, linkedMapOf("k" to wanted, "other" to other))
        assertTrue(policy.observeActual(second, "k", wanted, 0))
        assertTrue(policy.observeActual(second, "other", other, 0))
        assertFalse(policy.forgetActual(first, "k"))
        assertFalse(policy.forgetActual(second, "missing"))
        assertArrayEquals(wanted, policy.snapshot(second).getValue("k").actual)

        val third = policy.begin("device-b", 7, linkedMapOf("k" to wanted, "other" to other))
        val sameFieldsDifferentRef = SettingsSyncPolicy.SessionRef(third.owner, third.token, third.generation)
        assertTrue(policy.observeActual(third, "k", wanted, 0))
        assertTrue(policy.observeActual(third, "other", other, 0))
        assertFalse(policy.forgetActual(second, "k"))
        assertFalse(policy.forgetActual(sameFieldsDifferentRef, "k"))
        assertArrayEquals(wanted, policy.snapshot(third).getValue("k").actual)
        assertTrue(policy.forgetActual(third, "k"))
        assertNull(policy.snapshot(third).getValue("k").actual)
        assertArrayEquals(other, policy.snapshot(third).getValue("other").actual)
        assertTrue(policy.end(third))
        assertFalse(policy.forgetActual(third, "k"))
    }

    @Test
    fun forgettingDuringSendPreservesRequestsAndPreventsOldReportsConfirmingThem() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, linkedMapOf("sent" to wanted, "failed" to wanted))
        policy.ready(session, 0)
        val requests = policy.time(session, 0).associateBy { it.key }
        for ((key, request) in requests) {
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.observeActual(session, key, wanted, 1))
            assertTrue(policy.forgetActual(session, key))
            val item = policy.snapshot(session).getValue(key)
            assertEquals(SettingsSyncPolicy.Phase.SENDING, item.phase)
            assertEquals(1, item.attempts)
            assertNull(item.actual)
        }
        assertTrue(policy.completeWrite(session, requests.getValue("sent"), SettingsSyncPolicy.WriteResult.SENT, 2))
        assertTrue(policy.completeWrite(session, requests.getValue("failed"), SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, 2))
        assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, policy.snapshot(session).getValue("sent").phase)
        val failed = policy.snapshot(session).getValue("failed")
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF, failed.phase)
        assertEquals(SettingsSyncPolicy.Failure.TRANSPORT_FAILURE, failed.failure)
        assertEquals(1002L, failed.nextAtMs)
    }

    @Test
    fun neverSentAttemptsCannotTurnMatchingReportsIntoConfirmation() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        policy.ready(session, 0)
        for ((index, time) in listOf(0L, 1000L, 3000L).withIndex()) {
            val request = policy.time(session, time).single()
            assertEquals(index + 1, request.attempt)
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.observeActual(session, "att:1b", wanted, time))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED, time))
            assertTrue(policy.observeActual(session, "att:1b", wanted, time + 1))
            val item = policy.snapshot(session).getValue("att:1b")
            assertEquals(if (index < 2) SettingsSyncPolicy.Phase.BACKOFF else SettingsSyncPolicy.Phase.FAILED, item.phase)
            assertEquals(SettingsSyncPolicy.Failure.UNCONFIRMED, item.failure)
            assertEquals(index + 1, item.attempts)
        }
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun neverSentRetryKeepsEarlierSuccessfulWritesLateReportEligibility() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("att:1b" to wanted))
        assertTrue(policy.noteRemoteObservation(session, "att:1b"))
        policy.ready(session, 0)
        val first = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, first))
        assertTrue(policy.completeWrite(session, first, SettingsSyncPolicy.WriteResult.SENT, 0))
        assertTrue(policy.time(session, 1500).isEmpty())
        val second = policy.time(session, 2500).single()
        assertTrue(policy.startWrite(session, second))
        assertTrue(policy.completeWrite(session, second, SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED, 2500))
        val backoff = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Phase.BACKOFF, backoff.phase)
        assertEquals(2, backoff.attempts)
        assertEquals(4500L, backoff.nextAtMs)
        assertTrue(policy.observeActual(session, "att:1b", wanted, 2600))
        val confirmed = policy.snapshot(session).getValue("att:1b")
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, confirmed.phase)
        assertEquals(2, confirmed.attempts)
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun unsuccessfulResultsAreNeverCountedAsSentAndTheirFailureIsRetained() {
        val failures = mapOf(
            SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE to SettingsSyncPolicy.Failure.TRANSPORT_FAILURE,
            SettingsSyncPolicy.WriteResult.DEVICE_REJECTED to SettingsSyncPolicy.Failure.DEVICE_REJECTED,
            SettingsSyncPolicy.WriteResult.UNCONFIRMED to SettingsSyncPolicy.Failure.UNCONFIRMED,
            SettingsSyncPolicy.WriteResult.NOT_SENT_UNCONFIRMED to SettingsSyncPolicy.Failure.UNCONFIRMED,
        )
        for ((result, expectedFailure) in failures) for (failureIndex in listOf(0, 2)) {
            val policy = SettingsSyncPolicy()
            val session = policy.begin("device-a", 1, mapOf("k" to wanted))
            policy.ready(session, 0)
            for ((index, time) in listOf(0L, 1000L, 3000L).withIndex()) {
                val request = policy.time(session, time).single()
                assertTrue(policy.startWrite(session, request))
                val outcome = if (index == failureIndex) result else SettingsSyncPolicy.WriteResult.SENT
                assertTrue(policy.completeWrite(session, request, outcome, time))
            }
            val item = policy.snapshot(session).getValue("k")
            assertEquals(SettingsSyncPolicy.Phase.FAILED, item.phase)
            assertEquals(expectedFailure, item.failure)
            assertEquals(3, item.attempts)
            assertNull(item.actual)
            assertTrue(policy.time(session, 100_000).isEmpty())
        }
    }

    @Test
    fun reportsDuringWritesKeepMismatchFromFallingBackToSendSuccess() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.observeActual(session, "k", other, 0)
        policy.ready(session, 0)
        val starts = listOf(0L, 2500L, 6000L)
        val deadlines = listOf(1500L, 4000L, 7500L)
        for (index in starts.indices) {
            val request = policy.time(session, starts[index]).single()
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.observeActual(session, "k", other, starts[index]))
            assertEquals(SettingsSyncPolicy.Phase.SENDING, policy.snapshot(session).getValue("k").phase)
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, starts[index]))
            assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, policy.snapshot(session).getValue("k").phase)
            assertTrue(policy.time(session, deadlines[index]).isEmpty())
        }
        val item = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.FAILED, item.phase)
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, item.failure)
        assertEquals(3, item.attempts)
        assertTrue(policy.hasRemoteObservation(session, "k"))
        assertArrayEquals(other, item.actual)
    }

    @Test
    fun reportArrivingAfterFirstSendDisablesFallbackForLaterSends() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        val first = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, first))
        assertTrue(policy.completeWrite(session, first, SettingsSyncPolicy.WriteResult.SENT, 0))
        assertTrue(policy.observeActual(session, "k", other, 500))
        for ((start, deadline) in listOf(1000L to 2500L, 4500L to 6000L)) {
            val request = policy.time(session, start).single()
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, start))
            assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, policy.snapshot(session).getValue("k").phase)
            assertTrue(policy.time(session, deadline).isEmpty())
        }
        val item = policy.snapshot(session).getValue("k")
        // Reports stopped after the first send, so three accepted sends end provisionally verified.
        assertEquals(SettingsSyncPolicy.Phase.SENT_UNVERIFIED, item.phase)
        assertNull(item.failure)
        assertEquals(3, item.attempts)
        assertArrayEquals(other, item.actual)
    }

    @Test
    fun observationBeforeItemCreationBlocksFallbackWithoutReplayingOldValue() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, emptyMap())
        assertTrue(policy.noteRemoteObservation(session, "noted"))
        assertTrue(policy.observeActual(session, "received", wanted, 0))
        assertTrue(policy.hasRemoteObservation(session, "noted"))
        assertTrue(policy.hasRemoteObservation(session, "received"))
        assertEquals(1L, policy.updateDesired(session, "noted", wanted, 0))
        assertEquals(1L, policy.updateDesired(session, "received", wanted, 0))
        policy.ready(session, 0)
        val starts = listOf(0L, 2500L, 6000L)
        val deadlines = listOf(1500L, 4000L, 7500L)
        for (index in starts.indices) {
            val requests = policy.time(session, starts[index])
            assertEquals(setOf("noted", "received"), requests.map { it.key }.toSet())
            requests.forEach { assertTrue(policy.startWrite(session, it)) }
            requests.forEach { assertTrue(policy.completeWrite(session, it, SettingsSyncPolicy.WriteResult.SENT, starts[index])) }
            assertTrue(policy.time(session, deadlines[index]).isEmpty())
        }
        for (item in policy.snapshot(session).values) {
            assertEquals(SettingsSyncPolicy.Phase.SENT_UNVERIFIED, item.phase)
            assertNull(item.failure)
            assertEquals(3, item.attempts)
            assertNull(item.actual)
        }
    }

    @Test
    fun lateReportReplacesUnverifiedSendStatusWithActualComparison() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, linkedMapOf("mismatch" to wanted, "noted" to wanted))
        policy.ready(session, 0)
        for (time in listOf(0L, 1000L, 3000L)) {
            val requests = policy.time(session, time)
            assertEquals(2, requests.size)
            requests.forEach { assertTrue(policy.startWrite(session, it)) }
            requests.forEach { assertTrue(policy.completeWrite(session, it, SettingsSyncPolicy.WriteResult.SENT, time)) }
        }
        assertTrue(policy.snapshot(session).values.all { it.phase == SettingsSyncPolicy.Phase.SENT_UNVERIFIED })
        assertTrue(policy.observeActual(session, "mismatch", other, 3001))
        val mismatch = policy.snapshot(session).getValue("mismatch")
        assertEquals(SettingsSyncPolicy.Phase.FAILED, mismatch.phase)
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, mismatch.failure)
        assertTrue(policy.noteRemoteObservation(session, "noted"))
        assertEquals(SettingsSyncPolicy.Phase.FAILED, policy.snapshot(session).getValue("noted").phase)
        assertEquals(SettingsSyncPolicy.Failure.UNCONFIRMED, policy.snapshot(session).getValue("noted").failure)
        assertTrue(policy.observeActual(session, "noted", other, 3002))
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, policy.snapshot(session).getValue("noted").failure)
        assertTrue(policy.observeActual(session, "noted", wanted, 3003))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("noted").phase)
    }

    @Test
    fun reportCapabilitySurvivesForgetAndRevisionButNotAnotherSession() {
        val policy = SettingsSyncPolicy()
        val first = policy.begin("device-a", 1, mapOf("k" to wanted))
        assertTrue(policy.observeActual(first, "k", other, 0))
        assertTrue(policy.forgetActual(first, "k"))
        assertTrue(policy.hasRemoteObservation(first, "k"))
        assertEquals(2L, policy.updateDesired(first, "k", wanted, 1))
        assertTrue(policy.hasRemoteObservation(first, "k"))
        assertNull(policy.snapshot(first).getValue("k").actual)
        val second = policy.begin("device-a", 1, mapOf("k" to wanted))
        assertFalse(policy.hasRemoteObservation(first, "k"))
        assertFalse(policy.hasRemoteObservation(second, "k"))
        assertFalse(policy.noteRemoteObservation(first, "k"))
        assertFalse(policy.noteRemoteObservation(second, " "))
        assertTrue(policy.noteRemoteObservation(second, "k"))
        assertTrue(policy.hasRemoteObservation(second, "k"))
        assertTrue(policy.end(second))
        assertFalse(policy.hasRemoteObservation(second, "k"))
    }

    @Test
    fun newRevisionResetsSuccessfulSendCount() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        for (time in listOf(0L, 1000L)) {
            val request = policy.time(session, time).single()
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, time))
        }
        assertEquals(2L, policy.updateDesired(session, "k", other, 1001))
        for ((index, time) in listOf(1001L, 2001L, 4001L).withIndex()) {
            val request = policy.time(session, time).single()
            assertEquals(index + 1, request.attempt)
            assertArrayEquals(other, request.value)
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, time))
            assertEquals(if (index < 2) SettingsSyncPolicy.Phase.BACKOFF else SettingsSyncPolicy.Phase.SENT_UNVERIFIED,
                policy.snapshot(session).getValue("k").phase)
        }
    }

    @Test
    fun pausedItemsCanConfirmFromReportsWhileOtherItemsProceed() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, linkedMapOf(
            "already" to wanted, "waiting" to wanted, "other" to wanted,
        ))
        assertTrue(policy.setWriteAllowed(session, "already", false, 0))
        assertTrue(policy.setWriteAllowed(session, "waiting", false, 0))
        assertTrue(policy.observeActual(session, "already", wanted, 0))
        policy.ready(session, 0)
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("already").phase)
        val waiting = policy.snapshot(session).getValue("waiting")
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, waiting.phase)
        assertEquals(0, waiting.attempts)
        assertNull(waiting.nextAtMs)
        assertEquals("other", policy.time(session, 0).single().key)
        assertTrue(policy.observeActual(session, "waiting", wanted, 1))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("waiting").phase)
        assertTrue(policy.setWriteAllowed(session, "waiting", true, 2))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("waiting").phase)
    }

    @Test
    fun pausingReservedRequestInvalidatesItWithoutConsumingAnAttempt() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        val stale = policy.time(session, 0).single()
        assertTrue(policy.setWriteAllowed(session, "k", false, 1))
        val waiting = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, waiting.phase)
        assertEquals(0, waiting.attempts)
        assertEquals(1L, waiting.revision)
        assertNull(waiting.nextAtMs)
        assertFalse(policy.startWrite(session, stale))
        assertTrue(policy.setWriteAllowed(session, "k", true, 2))
        val current = policy.time(session, 2).single()
        assertEquals(1, current.attempt)
        assertEquals(stale.revision, current.revision)
        assertFalse(policy.startWrite(session, stale))
        assertTrue(policy.startWrite(session, current))
        assertEquals(1, policy.snapshot(session).getValue("k").attempts)
    }

    @Test
    fun pausedBackoffPreservesDeadlinesAndTheThreeSendBudget() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        val first = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, first))
        assertTrue(policy.completeWrite(session, first, SettingsSyncPolicy.WriteResult.SENT, 0))
        assertTrue(policy.setWriteAllowed(session, "k", false, 200))
        val waiting = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, waiting.phase)
        assertEquals(1, waiting.attempts)
        assertEquals(SettingsSyncPolicy.Failure.UNCONFIRMED, waiting.failure)
        assertNull(waiting.nextAtMs)
        assertTrue(policy.time(session, 300).isEmpty())
        assertTrue(policy.setWriteAllowed(session, "k", true, 500))
        assertEquals(1000L, policy.snapshot(session).getValue("k").nextAtMs)
        assertTrue(policy.time(session, 999).isEmpty())
        val staleSecond = policy.time(session, 1000).single()
        assertTrue(policy.setWriteAllowed(session, "k", false, 1001))
        assertFalse(policy.startWrite(session, staleSecond))
        assertEquals(1, policy.snapshot(session).getValue("k").attempts)
        assertTrue(policy.setWriteAllowed(session, "k", true, 1002))
        val second = policy.time(session, 1002).single()
        assertEquals(2, second.attempt)
        assertTrue(policy.startWrite(session, second))
        assertTrue(policy.completeWrite(session, second, SettingsSyncPolicy.WriteResult.SENT, 1002))
        assertTrue(policy.setWriteAllowed(session, "k", false, 1100))
        assertTrue(policy.time(session, 10_000).isEmpty())
        assertNull(policy.snapshot(session).getValue("k").nextAtMs)
        assertTrue(policy.setWriteAllowed(session, "k", true, 10_000))
        assertEquals(3002L, policy.snapshot(session).getValue("k").nextAtMs)
        val third = policy.time(session, 10_000).single()
        assertEquals(3, third.attempt)
        assertTrue(policy.startWrite(session, third))
        assertTrue(policy.completeWrite(session, third, SettingsSyncPolicy.WriteResult.SENT, 10_000))
        val sent = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.SENT_UNVERIFIED, sent.phase)
        assertEquals(3, sent.attempts)
        assertEquals(1L, sent.revision)
        assertArrayEquals(wanted, sent.desired)
        assertTrue(policy.setWriteAllowed(session, "k", false, 10_001))
        assertTrue(policy.setWriteAllowed(session, "k", true, 10_002))
        assertEquals(SettingsSyncPolicy.Phase.SENT_UNVERIFIED, policy.snapshot(session).getValue("k").phase)
        assertTrue(policy.time(session, 20_000).isEmpty())
        assertTrue(policy.observeActual(session, "k", other, 20_001))
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, policy.snapshot(session).getValue("k").failure)
        assertTrue(policy.observeActual(session, "k", wanted, 20_002))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("k").phase)
    }

    @Test
    fun pausingDuringSendPreservesConfirmationAndSuspendsOnlyTheRetry() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, linkedMapOf("match" to wanted, "mismatch" to wanted))
        assertTrue(policy.noteRemoteObservation(session, "match"))
        assertTrue(policy.noteRemoteObservation(session, "mismatch"))
        policy.ready(session, 0)
        val requests = policy.time(session, 0).associateBy { it.key }
        for ((key, request) in requests) {
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.setWriteAllowed(session, key, false, 1))
            assertEquals(SettingsSyncPolicy.Phase.SENDING, policy.snapshot(session).getValue(key).phase)
        }
        assertTrue(policy.observeActual(session, "match", wanted, 1))
        assertEquals(SettingsSyncPolicy.Phase.SENDING, policy.snapshot(session).getValue("match").phase)
        assertTrue(policy.completeWrite(session, requests.getValue("match"), SettingsSyncPolicy.WriteResult.SENT, 2))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("match").phase)
        assertTrue(policy.completeWrite(session, requests.getValue("mismatch"), SettingsSyncPolicy.WriteResult.SENT, 2))
        assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, policy.snapshot(session).getValue("mismatch").phase)
        assertTrue(policy.observeActual(session, "mismatch", other, 20))
        assertTrue(policy.time(session, 1502).isEmpty())
        val waiting = policy.snapshot(session).getValue("mismatch")
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, waiting.phase)
        assertEquals(SettingsSyncPolicy.Failure.MISMATCH, waiting.failure)
        assertEquals(1, waiting.attempts)
        assertNull(waiting.nextAtMs)
        assertTrue(policy.setWriteAllowed(session, "mismatch", true, 2000))
        assertEquals(2502L, policy.snapshot(session).getValue("mismatch").nextAtMs)
        assertTrue(policy.time(session, 2501).isEmpty())
        assertEquals(2, policy.time(session, 2502).single().attempt)
    }

    @Test
    fun failedInFlightWritePausesOnlyItsNextRetry() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.setWriteAllowed(session, "k", false, 10))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, 20))
        val waiting = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, waiting.phase)
        assertEquals(SettingsSyncPolicy.Failure.TRANSPORT_FAILURE, waiting.failure)
        assertEquals(1, waiting.attempts)
        assertNull(waiting.nextAtMs)
        assertTrue(policy.setWriteAllowed(session, "k", true, 500))
        assertEquals(1020L, policy.snapshot(session).getValue("k").nextAtMs)
        assertTrue(policy.time(session, 1019).isEmpty())
        assertEquals(2, policy.time(session, 1020).single().attempt)
    }

    @Test
    fun confirmationTimeoutOnLastAttemptBecomesUnverifiedEvenIfWearIsPaused() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        assertTrue(policy.noteRemoteObservation(session, "k"))
        policy.ready(session, 0)
        for ((index, times) in listOf(0L to 1500L, 2500L to 4000L, 6000L to 7500L).withIndex()) {
            val (start, deadline) = times
            val request = policy.time(session, start).single()
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, start))
            if (index == 2) {
                assertTrue(policy.setWriteAllowed(session, "k", false, start + 1))
                assertEquals(SettingsSyncPolicy.Phase.AWAITING_CONFIRMATION, policy.snapshot(session).getValue("k").phase)
            }
            assertTrue(policy.time(session, deadline).isEmpty())
        }
        val item = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.SENT_UNVERIFIED, item.phase)
        assertNull(item.failure)
        assertEquals(3, item.attempts)
        assertTrue(policy.setWriteAllowed(session, "k", true, 7501))
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun pausedBackoffCanStillConfirmFromALateReport() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        val request = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, request))
        assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.SENT, 0))
        assertTrue(policy.setWriteAllowed(session, "k", false, 10))
        assertTrue(policy.observeActual(session, "k", wanted, 20))
        assertEquals(SettingsSyncPolicy.Phase.CONFIRMED, policy.snapshot(session).getValue("k").phase)
        assertTrue(policy.setWriteAllowed(session, "k", true, 30))
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun wearEventsCannotReviveAnExhaustedFailure() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        for (time in listOf(0L, 1000L, 3000L)) {
            val request = policy.time(session, time).single()
            assertTrue(policy.startWrite(session, request))
            assertTrue(policy.completeWrite(session, request, SettingsSyncPolicy.WriteResult.TRANSPORT_FAILURE, time))
        }
        assertTrue(policy.setWriteAllowed(session, "k", false, 3001))
        assertTrue(policy.setWriteAllowed(session, "k", true, 3002))
        val item = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.FAILED, item.phase)
        assertEquals(SettingsSyncPolicy.Failure.TRANSPORT_FAILURE, item.failure)
        assertEquals(3, item.attempts)
        assertTrue(policy.time(session, 100_000).isEmpty())
    }

    @Test
    fun explicitEditDuringPauseKeepsTheGateAndSendsTheLatestRevision() {
        val policy = SettingsSyncPolicy()
        val session = policy.begin("device-a", 1, mapOf("k" to wanted))
        policy.ready(session, 0)
        val first = policy.time(session, 0).single()
        assertTrue(policy.startWrite(session, first))
        assertTrue(policy.completeWrite(session, first, SettingsSyncPolicy.WriteResult.SENT, 0))
        assertTrue(policy.setWriteAllowed(session, "k", false, 10))
        assertEquals(2L, policy.updateDesired(session, "k", other, 20))
        val waiting = policy.snapshot(session).getValue("k")
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, waiting.phase)
        assertEquals(0, waiting.attempts)
        assertArrayEquals(other, waiting.desired)
        assertNull(waiting.nextAtMs)
        assertTrue(policy.time(session, 100).isEmpty())
        assertTrue(policy.setWriteAllowed(session, "k", true, 101))
        val current = policy.time(session, 101).single()
        assertEquals(2L, current.revision)
        assertEquals(1, current.attempt)
        assertArrayEquals(other, current.value)
    }

    @Test
    fun gateForNewKeysIsScopedToTheCurrentSession() {
        val policy = SettingsSyncPolicy()
        val first = policy.begin("device-a", 1, emptyMap())
        policy.ready(first, 0)
        assertTrue(policy.setWriteAllowed(first, "new", false, 0))
        assertEquals(1L, policy.updateDesired(first, "new", wanted, 0))
        assertEquals(SettingsSyncPolicy.Phase.WAITING_FOR_WEAR, policy.snapshot(first).getValue("new").phase)
        assertTrue(policy.time(first, 100_000).isEmpty())
        val second = policy.begin("device-a", 1, mapOf("new" to wanted))
        assertFalse(policy.setWriteAllowed(first, "new", true, 100_001))
        assertFalse(policy.setWriteAllowed(second, " ", false, 100_001))
        policy.ready(second, 100_001)
        assertEquals("new", policy.time(second, 100_001).single().key)
        assertTrue(policy.end(second))
        assertFalse(policy.setWriteAllowed(second, "new", false, 100_002))
    }
}
