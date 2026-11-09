package me.kavishdevar.librepods.bluetooth

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ATTWriteBoundaryTest {
    @Test
    fun fenceRunsAfterTheFinalGuardAndBeforeAnyBytes() {
        val stream = ByteArrayOutputStream()
        val order = mutableListOf<String>()
        writeAttPacket(stream, byteArrayOf(0x12, 0x1b, 0, 1),
            beforeWrite = { order += "guard"; assertEquals(0, stream.size()); true },
            onWriteStarted = { order += "fence"; assertEquals(0, stream.size()); true })
        assertEquals(listOf("guard", "fence"), order)
        assertArrayEquals(byteArrayOf(0x12, 0x1b, 0, 1), stream.toByteArray())
    }

    @Test
    fun invalidatedFenceCancelsBeforeSendWithoutPoisoningSession() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        val stream = ByteArrayOutputStream()
        val result = gate.transactResult(socket, 0x13, 100) {
            writeAttPacket(stream, byteArrayOf(0x12), beforeWrite = { true }, onWriteStarted = { false })
        }
        assertEquals(ATTResult.Cancelled, result)
        assertEquals(0, stream.size())
        assertTrue(gate.isUsable(socket))
        assertTrue(gate.transactResult(socket, 0x13, 100) {
            gate.offer(socket, byteArrayOf(0x13))
        } is ATTResult.Value)
    }

    @Test
    fun failedFinalGuardNeverCallsFenceOrWrites() {
        val stream = ByteArrayOutputStream()
        var called = false
        try {
            writeAttPacket(stream, byteArrayOf(0x12), beforeWrite = { false }, onWriteStarted = { called = true; true })
            fail("expected cancellation")
        } catch (_: ATTRequestCancelledBeforeSend) { }
        assertFalse(called)
        assertEquals(0, stream.size())
    }
}
