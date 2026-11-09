package me.kavishdevar.librepods.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDisplayStateTest {
    private val adaptiveVolume = 0x26

    @Test
    fun successfulWriteSurvivesDisplayRebuildUntilActualReportWins() {
        val display = ControlDisplayState<Int>(4)
        display.recordReport(adaptiveVolume, byteArrayOf(2))
        display.recordWriteResult(adaptiveVolume, byteArrayOf(1), success = true)

        // A new consumer of the same session must not replace the write with the old report.
        val reopened = display.snapshot(emptyMap())!!
        assertArrayEquals(byteArrayOf(1), reopened.values[adaptiveVolume])
        assertArrayEquals(byteArrayOf(2), display.reportedValues()[adaptiveVolume])

        // A device report, including a rejection or an external change, takes precedence.
        display.recordReport(adaptiveVolume, byteArrayOf(2))
        val reported = display.snapshot(emptyMap())!!
        assertArrayEquals(byteArrayOf(2), reported.values[adaptiveVolume])
        assertTrue(reported.version.revision > reopened.version.revision)
        assertFalse(display.isCurrent(reopened.version))
    }

    @Test
    fun failedWriteDoesNotCreatePendingOrEraseEarlierSuccessfulWrite() {
        val display = ControlDisplayState<Int>(1)
        display.recordReport(adaptiveVolume, byteArrayOf(2))
        val original = display.snapshot(emptyMap())!!
        display.recordWriteResult(adaptiveVolume, byteArrayOf(1), success = false)
        assertArrayEquals(byteArrayOf(2), display.snapshot(emptyMap())!!.values[adaptiveVolume])
        assertTrue(display.isCurrent(original.version))

        display.recordWriteResult(adaptiveVolume, byteArrayOf(1), success = true)
        display.recordWriteResult(adaptiveVolume, byteArrayOf(2), success = false)
        assertArrayEquals(byteArrayOf(1), display.snapshot(emptyMap())!!.values[adaptiveVolume])
    }

    @Test
    fun failedWriteOfNewRetainedDesiredChoiceDoesNotExposeOlderPending() {
        val display = ControlDisplayState<Int>(1)
        display.recordWriteResult(adaptiveVolume, byteArrayOf(1), success = true)
        display.desiredChanged(adaptiveVolume)
        display.recordWriteResult(adaptiveVolume, byteArrayOf(2), success = false)
        assertArrayEquals(
            byteArrayOf(2), display.snapshot(mapOf(adaptiveVolume to byteArrayOf(2)))!!.values[adaptiveVolume]
        )
    }

    @Test
    fun sameValueReportAdvancesRevisionAndOlderSnapshotCannotWin() {
        val display = ControlDisplayState<Int>(9)
        display.recordReport(adaptiveVolume, byteArrayOf(2))
        display.recordWriteResult(adaptiveVolume, byteArrayOf(1), success = true)
        val writeSnapshot = display.snapshot(emptyMap())!!
        display.recordReport(adaptiveVolume, byteArrayOf(1))
        val reportSnapshot = display.snapshot(emptyMap())!!

        assertTrue(reportSnapshot.version.revision > writeSnapshot.version.revision)
        assertFalse(display.isCurrent(writeSnapshot.version))
        assertFalse(writeSnapshot.version.canReplace(reportSnapshot.version))
        assertTrue(reportSnapshot.version.canReplace(writeSnapshot.version))
    }

    @Test
    fun closedConnectionRejectsItsOldSnapshotsAndNewConnectionStartsEmpty() {
        val first = ControlDisplayState<Int>(5)
        first.recordWriteResult(adaptiveVolume, byteArrayOf(1), success = true)
        val beforeDisconnect = first.snapshot(emptyMap())!!
        first.close()
        assertFalse(first.isCurrent(beforeDisconnect.version))
        assertNull(first.snapshot(emptyMap()))
        assertFalse(beforeDisconnect.version.canReplace(beforeDisconnect.version.closed()))

        val next = ControlDisplayState<Int>(6)
        val newSnapshot = next.snapshot(emptyMap())!!
        assertTrue(newSnapshot.values.isEmpty())
        assertFalse(beforeDisconnect.version.canReplace(newSnapshot.version))
        assertTrue(newSnapshot.version.canReplace(beforeDisconnect.version.closed()))

        // A newly bound service has its own token sequence; its UI version is reset.
        val replacementService = ControlDisplayState<Int>(1).snapshot(emptyMap())!!
        assertFalse(replacementService.version.canReplace(newSnapshot.version))
        assertTrue(replacementService.version.canReplace(null))
    }

    @Test
    fun delayedDisconnectCannotCloseANewerDisplayedOrActiveConnection() {
        assertFalse(shouldCloseControlDisplay(6, 5, 6))
        assertFalse(shouldCloseControlDisplay(6, 5, null))
        assertFalse(shouldCloseControlDisplay(5, 5, 6))
        // A replacement service can reuse the same numeric token.
        assertFalse(shouldCloseControlDisplay(6, 6, 6))
        assertTrue(shouldCloseControlDisplay(6, 6, null))
        assertTrue(shouldCloseControlDisplay(null, null, null))
    }

    @Test
    fun inputAndOutputArraysCannotMutateTheSessionOrPriorSnapshot() {
        val display = ControlDisplayState<Int>(1)
        val report = byteArrayOf(2)
        val desired = byteArrayOf(2)
        val sent = byteArrayOf(1)
        display.recordReport(adaptiveVolume, report)
        display.recordWriteResult(adaptiveVolume, sent, success = true)
        val snapshot = display.snapshot(mapOf(0x28 to desired))!!
        report[0] = 1
        desired[0] = 1
        sent[0] = 2
        snapshot.values[adaptiveVolume]!![0] = 2
        display.reportedValues()[adaptiveVolume]!![0] = 1

        assertArrayEquals(byteArrayOf(1), snapshot.values[adaptiveVolume])
        assertArrayEquals(byteArrayOf(2), snapshot.values[0x28])
        assertArrayEquals(byteArrayOf(2), display.reportedValue(adaptiveVolume))
        assertArrayEquals(byteArrayOf(1), display.snapshot(emptyMap())!!.values[adaptiveVolume])
    }

    @Test
    fun nonPolicyRuntimeFallbackDoesNotBecomeReportedAuthority() {
        val display = ControlDisplayState<Int>(1)
        display.recordRuntimeFallback(0x31, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), display.snapshot(emptyMap())!!.values[0x31])
        assertTrue(display.reportedValues().isEmpty())
        val version = display.snapshot(emptyMap())!!.version
        display.recordRuntimeFallback(0x31, byteArrayOf(1))
        assertEquals(version, display.snapshot(emptyMap())!!.version)
        display.recordReport(0x31, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), display.snapshot(emptyMap())!!.values[0x31])
    }
}
