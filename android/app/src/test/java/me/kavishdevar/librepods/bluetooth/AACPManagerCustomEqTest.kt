package me.kavishdevar.librepods.bluetooth

import java.lang.reflect.Proxy
import me.kavishdevar.librepods.data.CustomEq
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AACPManagerCustomEqTest {
    @Before
    fun disconnectTransport() {
        BluetoothConnectionManager.aacpSocket = null
    }

    private fun packet(eq: CustomEq = CustomEq(2, 20, 60, 100)): ByteArray =
        byteArrayOf(4, 0, 4, 0) + eq.toPacket()

    private fun observe(manager: AACPManager, events: MutableList<String>) {
        val callback = Proxy.newProxyInstance(
            AACPManager.PacketCallback::class.java.classLoader,
            arrayOf(AACPManager.PacketCallback::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "onReceivedCustomEqValue" -> events.add("source")
                "onCustomEqReceived" -> events.add("generic")
            }
            null
        } as AACPManager.PacketCallback
        manager.setPacketCallback(callback)
        manager.customEqCallback = { events.add("vm") }
    }

    @Test
    fun validReplyEstablishesRemoteSourceBeforeVmNotification() {
        val manager = AACPManager()
        val events = mutableListOf<String>()
        observe(manager, events)
        manager.receivePacket(packet())
        assertEquals(CustomEq(2, 20, 60, 100), manager.customEq)
        assertEquals(listOf("source", "vm", "generic"), events)
    }

    @Test
    fun malformedReplyNeverCreatesDefaultStateOrCallsCustomEqListeners() {
        val manager = AACPManager()
        val events = mutableListOf<String>()
        observe(manager, events)
        val valid = packet()
        manager.receivePacket(valid)
        events.clear()
        val invalid = listOf(
            valid.copyOf(12), valid + byteArrayOf(0),
            valid.copyOf().also { it[0] = 5 },
            valid.copyOf().also { it[5] = 1 },
            valid.copyOf().also { it[6] = 4 },
            valid.copyOf().also { it[7] = 1 },
            valid.copyOf().also { it[8] = 2 },
            valid.copyOf().also { it[9] = 0 },
            valid.copyOf().also { it[9] = 3 },
            valid.copyOf().also { it[10] = 101 },
            valid.copyOf().also { it[11] = (-1).toByte() },
            valid.copyOf().also { it[12] = 101 }
        )
        invalid.forEach { malformed ->
            assertNull(manager.parseCustomEqPacket(malformed))
            manager.receivePacket(malformed)
            assertEquals(CustomEq(2, 20, 60, 100), manager.customEq)
            assertTrue(events.isEmpty())
        }
        assertNull(manager.parseCustomEqPacket(valid.copyOf().also { it[4] = 0x62 }))
    }

    @Test
    fun failedLocalSendDoesNotMasqueradeAsRemoteEq() {
        val manager = AACPManager()
        val events = mutableListOf<String>()
        observe(manager, events)
        val before = manager.customEq
        assertFalse(manager.sendCustomEqPacket(CustomEq(2, 20, 60, 100)))
        assertEquals(before, manager.customEq)
        assertTrue(events.isEmpty())
    }
}
