package me.kavishdevar.librepods.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveVolumeRestorePolicyTest {
    @Test
    fun absentIntentOrUnreadySessionNeverWrites() {
        val absent = AdaptiveVolumeRestorePolicy(1, null)
        assertNull(absent.planRestore(ready = false, actual = null))
        assertNull(absent.planRestore(ready = true, actual = 2))
        val selected = AdaptiveVolumeRestorePolicy(2, 1)
        assertNull(selected.planRestore(ready = false, actual = 2))
        assertEquals(1.toByte(), selected.planRestore(ready = true, actual = 2)!!.value)
    }

    @Test
    fun matchingRealReportSkipsOnceEvenWhenLaterReportDiffers() {
        val policy = AdaptiveVolumeRestorePolicy(7, 1)
        assertNull(policy.planRestore(ready = true, actual = 1))
        assertNull(policy.planRestore(ready = true, actual = 2))
        assertNull(policy.planRestore(ready = true, actual = null))
    }

    @Test
    fun differentOrMissingReportPlansOnlyOneAttempt() {
        for (actual in listOf(2.toByte(), null, 0.toByte())) {
            val policy = AdaptiveVolumeRestorePolicy(3, 1)
            val request = policy.planRestore(ready = true, actual = actual)!!
            assertEquals(1.toByte(), request.value)
            assertTrue(policy.isCurrent(request))
            assertNull(policy.planRestore(ready = true, actual = actual))
            assertTrue(policy.complete(request))
            assertFalse(policy.isCurrent(request))
            assertNull(policy.planRestore(ready = true, actual = actual))
        }
    }

    @Test
    fun failedAttemptDoesNotLoopButNewConnectionCanRetry() {
        val oldSession = AdaptiveVolumeRestorePolicy(8, 2)
        val failed = oldSession.planRestore(ready = true, actual = 1)!!
        assertTrue(oldSession.complete(failed))
        assertNull(oldSession.planRestore(ready = true, actual = 1))
        oldSession.close()
        val newSession = AdaptiveVolumeRestorePolicy(9, 2)
        assertEquals(2.toByte(), newSession.planRestore(ready = true, actual = 1)!!.value)
        assertFalse(newSession.isCurrent(failed))
    }

    @Test
    fun explicitUserWritesDoNotAutomaticallyRepeatBeforeReady() {
        for (success in listOf(true, false)) {
            val policy = AdaptiveVolumeRestorePolicy(10, 2)
            val revision = policy.setUserIntent(1)
            policy.recordUserWrite(revision, success)
            assertNull(policy.planRestore(ready = true, actual = 2))
        }
    }

    @Test
    fun staleUserWriteResultCannotSuppressTheLatestSelection() {
        val policy = AdaptiveVolumeRestorePolicy(10, null)
        val oldRevision = policy.setUserIntent(1)
        val latestRevision = policy.setUserIntent(2)
        policy.recordUserWrite(oldRevision, success = true)
        val request = policy.planRestore(ready = true, actual = 1)!!
        assertEquals(latestRevision, request.revision)
        assertEquals(2.toByte(), request.value)
    }

    @Test
    fun newUserRevisionInvalidatesOldRestoreAndOldUserResult() {
        val policy = AdaptiveVolumeRestorePolicy(11, 1)
        val oldRestore = policy.planRestore(ready = true, actual = 2)!!
        val newRevision = policy.setUserIntent(2)
        assertFalse(policy.isCurrent(oldRestore))
        assertFalse(policy.complete(oldRestore))
        policy.recordUserWrite(oldRestore.revision, success = true)
        policy.recordUserWrite(newRevision, success = true)
        assertNull(policy.planRestore(ready = true, actual = 1))

        val nextSession = AdaptiveVolumeRestorePolicy(12, 2)
        val nextRequest = nextSession.planRestore(ready = true, actual = 1)!!
        assertEquals(2.toByte(), nextRequest.value)
        assertFalse(policy.isCurrent(nextRequest))
    }

    @Test
    fun deviceReportIsOnlyComparisonAndDoesNotReplaceUserIntent() {
        val policy = AdaptiveVolumeRestorePolicy(13, 1)
        assertNull(policy.planRestore(ready = false, actual = 2))
        val request = policy.planRestore(ready = true, actual = 2)!!
        assertEquals(1.toByte(), request.value)
        assertTrue(policy.complete(request))
        assertNull(policy.planRestore(ready = true, actual = 2))
    }

    @Test
    fun invalidIntentCannotBecomeARestoreValue() {
        val absent = AdaptiveVolumeRestorePolicy(14, 3)
        assertNull(absent.planRestore(ready = true, actual = 2))
        val policy = AdaptiveVolumeRestorePolicy(15, null)
        assertThrows(IllegalArgumentException::class.java) { policy.setUserIntent(0) }
        assertThrows(IllegalArgumentException::class.java) { policy.setUserIntent(3) }
        assertNull(policy.planRestore(ready = true, actual = null))
    }
}
