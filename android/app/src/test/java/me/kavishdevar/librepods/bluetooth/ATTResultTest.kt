package me.kavishdevar.librepods.bluetooth

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ATTResultTest {
    @Test
    fun readValueAndMatchedRemoteErrorsAreDistinctAndLeaveSessionUsable() {
        val socket = Any()
        val session = ATTTransactionSession().apply { begin(socket) }
        val read = session.transactResult(socket, 0x0B, 100, 0x0A, 0x18) {
            session.offer(socket, byteArrayOf(0x0B, 42))
        }
        assertArrayEquals(byteArrayOf(0x0B, 42), (read as ATTResult.Value).value)
        val error = session.transactResult(socket, 0x13, 100, 0x12, 0x18) {
            session.offer(socket, byteArrayOf(0x01, 0x12, 0x18, 0, 0x03))
        }
        assertEquals(ATTResult.RemoteError(3), error)
        assertTrue(session.isUsable(socket))
        assertTrue(session.transactResult(socket, 0x13, 100, 0x12, 0x18) {
            session.offer(socket, byteArrayOf(0x13))
        } is ATTResult.Value)
    }

    @Test
    fun timeoutQuarantinesTheSocketUntilANewSession() {
        val old = Any()
        val session = ATTTransactionSession().apply { begin(old) }
        assertEquals(ATTResult.TimeoutOrQuarantined, session.transactResult(old, 0x0B, 1) {})
        assertFalse(session.isUsable(old))
        assertFalse(session.offer(old, byteArrayOf(0x0B, 1)))
        assertEquals(ATTResult.TimeoutOrQuarantined, session.transactResult(old, 0x13, 10) { fail("sent on old socket") })
        session.begin(old)
        assertFalse(session.isUsable(old))
        val replacement = Any()
        session.begin(replacement)
        assertTrue(session.isUsable(replacement))
        assertTrue(session.transactResult(replacement, 0x13, 100) {
            assertFalse(session.offer(old, byteArrayOf(0x13)))
            session.offer(replacement, byteArrayOf(0x13))
        } is ATTResult.Value)
    }

    @Test
    fun cancellationBeforeSendIsSafeButTransportOrMalformedReplyQuarantines() {
        val old = Any()
        val session = ATTTransactionSession().apply { begin(old) }
        assertEquals(ATTResult.Cancelled, session.transactResult(old, 0x13, 100, beforeSend = { false }) { fail("sent") })
        assertTrue(session.isUsable(old))
        assertEquals(ATTResult.Cancelled, session.transactResult(old, 0x13, 100) { throw ATTRequestCancelledBeforeSend() })
        assertTrue(session.isUsable(old))
        assertEquals(ATTResult.TransportFailure, session.transactResult(old, 0x13, 100) { throw IOException("partial send") })
        assertFalse(session.isUsable(old))

        val replacement = Any()
        session.begin(replacement)
        assertEquals(ATTResult.TransportFailure,
            session.transactResult(replacement, 0x13, 100, validateSuccess = { it.size == 1 }) {
                session.offer(replacement, byteArrayOf(0x13, 0))
            })
        assertFalse(session.isUsable(replacement))
    }

    @Test
    fun mismatchedErrorIsFailureRatherThanReportedRemoteError() {
        val socket = Any()
        val session = ATTTransactionSession().apply { begin(socket) }
        assertEquals(ATTResult.TransportFailure, session.transactResult(socket, 0x0B, 100, 0x0A, 0x18) {
            session.offer(socket, byteArrayOf(0x01, 0x0A, 0x2A, 0, 0x02))
        })
        assertFalse(session.isUsable(socket))
    }
}
