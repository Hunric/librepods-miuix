package me.kavishdevar.librepods.bluetooth

import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.AirPodsInformation
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ConnectedDevice
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AACPManagerControlStateTest {
    @Before
    fun clearSocket() {
        BluetoothConnectionManager.aacpSocket = null
    }

    @After
    fun clearSocketAfterTest() {
        BluetoothConnectionManager.aacpSocket = null
    }

    @Test
    fun changedValueUpsertsStatusBeforeNotifyingListener() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.SINGLE_CLICK_MODE
        val unrelatedIdentifier = ControlCommandIdentifiers.DOUBLE_CLICK_MODE
        val unrelatedStatus = status(unrelatedIdentifier, 0x33)
        manager.controlCommandStatusList += status(identifier, 0x11)
        manager.controlCommandStatusList += unrelatedStatus

        val observedValues = mutableListOf<ByteArray>()
        registerListener(manager, identifier) {
            observedValues += it.value
            assertArrayEquals(it.value, manager.getControlCommandStatus(identifier)?.value)
        }

        assertFalse(manager.sendControlCommand(identifier.value, byteArrayOf(0x22)))

        assertEquals(1, manager.controlCommandStatusList.count { it.identifier == identifier })
        assertArrayEquals(
            byteArrayOf(0x22),
            manager.getControlCommandStatus(identifier)?.value
        )
        assertEquals(1, observedValues.size)
        assertArrayEquals(byteArrayOf(0x22), observedValues.single())
        assertSame(unrelatedStatus, manager.getControlCommandStatus(unrelatedIdentifier))
    }

    @Test
    fun receivingCommandCollapsesHistoricalDuplicatesAndKeepsOtherIdentifiers() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.SINGLE_CLICK_MODE
        val unrelatedIdentifier = ControlCommandIdentifiers.DOUBLE_CLICK_MODE
        val unrelatedStatus = status(unrelatedIdentifier, 0x44)
        manager.controlCommandStatusList += status(identifier, 0x11)
        manager.controlCommandStatusList += status(identifier, 0x22)
        manager.controlCommandStatusList += unrelatedStatus

        val observedValues = mutableListOf<ByteArray>()
        registerListener(manager, identifier) {
            observedValues += it.value
            assertArrayEquals(it.value, manager.getControlCommandStatus(identifier)?.value)
        }

        manager.receivePacket(commandPacket(manager, identifier, byteArrayOf(0x55)))

        assertEquals(1, manager.controlCommandStatusList.count { it.identifier == identifier })
        assertArrayEquals(
            byteArrayOf(0x55),
            manager.getControlCommandStatus(identifier)?.value
        )
        assertEquals(1, observedValues.size)
        assertArrayEquals(byteArrayOf(0x55), observedValues.single())
        assertSame(unrelatedStatus, manager.getControlCommandStatus(unrelatedIdentifier))
    }

    @Test
    fun eachSendOverloadUpdatesLocallyAndNotifiesOnceWhenSocketIsNull() {
        val manager = AACPManager()
        val byteArrayId = ControlCommandIdentifiers.SINGLE_CLICK_MODE
        val byteId = ControlCommandIdentifiers.DOUBLE_CLICK_MODE
        val booleanId = ControlCommandIdentifiers.CLICK_HOLD_MODE
        val intId = ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL
        val expected = linkedMapOf(
            byteArrayId to byteArrayOf(0x21, 0x22),
            byteId to byteArrayOf(0x23),
            booleanId to byteArrayOf(0x01),
            intId to byteArrayOf(0x24),
        )
        val notifications = mutableMapOf<ControlCommandIdentifiers, Int>()

        expected.forEach { (identifier, expectedValue) ->
            registerListener(manager, identifier) {
                notifications[identifier] = (notifications[identifier] ?: 0) + 1
                assertArrayEquals(
                    expectedValue,
                    manager.getControlCommandStatus(identifier)?.value
                )
            }
        }

        assertFalse(manager.sendControlCommand(byteArrayId.value, expected.getValue(byteArrayId)))
        assertFalse(manager.sendControlCommand(byteId.value, expected.getValue(byteId).single()))
        assertFalse(manager.sendControlCommand(booleanId.value, true))
        assertFalse(manager.sendControlCommand(intId.value, expected.getValue(intId).single().toInt()))

        expected.forEach { (identifier, expectedValue) ->
            assertEquals(1, notifications[identifier])
            assertEquals(1, manager.controlCommandStatusList.count { it.identifier == identifier })
            assertArrayEquals(expectedValue, manager.getControlCommandStatus(identifier)?.value)
        }
    }

    @Test
    fun receivingOwnershipCommandNotifiesListenerAndPacketCallbacksOnceAfterStateUpdate() {
        val manager = AACPManager()
        val identifier = ControlCommandIdentifiers.OWNS_CONNECTION
        val packetCallback = RecordingPacketCallback()
        manager.setPacketCallback(packetCallback)
        var listenerCalls = 0
        registerListener(manager, identifier) { command ->
            listenerCalls++
            assertTrue(manager.owns)
            assertArrayEquals(
                command.value,
                manager.getControlCommandStatus(identifier)?.value
            )
        }
        val packet = commandPacket(manager, identifier, byteArrayOf(0x01))

        manager.receivePacket(packet)

        assertTrue(manager.owns)
        assertEquals(1, listenerCalls)
        assertEquals(listOf(true), packetCallback.ownershipChanges)
        assertEquals(1, packetCallback.controlPackets.size)
        assertArrayEquals(packet, packetCallback.controlPackets.single())
    }

    @Test
    fun controlListenersRemainRegisteredAcrossDisconnectAndCanBeRemoved() {
        val manager = AACPManager()
        val listeningMode = ControlCommandIdentifiers.LISTENING_MODE
        val conversationAwareness = ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG
        val listeningModeValues = mutableListOf<ByteArray>()
        val conversationAwarenessValues = mutableListOf<ByteArray>()
        val listeningModeListener = registerListener(manager, listeningMode) {
            listeningModeValues += it.value
        }
        val conversationAwarenessListener = registerListener(manager, conversationAwareness) {
            conversationAwarenessValues += it.value
        }

        manager.receivePacket(commandPacket(manager, listeningMode, byteArrayOf(0x03)))
        manager.receivePacket(
            commandPacket(manager, conversationAwareness, byteArrayOf(0x02))
        )

        assertEquals(1, listeningModeValues.size)
        assertArrayEquals(byteArrayOf(0x03), listeningModeValues.single())
        assertEquals(1, conversationAwarenessValues.size)
        assertArrayEquals(byteArrayOf(0x02), conversationAwarenessValues.single())

        manager.disconnected()

        assertTrue(manager.controlCommandStatusList.isEmpty())

        manager.receivePacket(commandPacket(manager, listeningMode, byteArrayOf(0x04)))
        manager.receivePacket(
            commandPacket(manager, conversationAwareness, byteArrayOf(0x01))
        )

        assertEquals(2, listeningModeValues.size)
        assertArrayEquals(byteArrayOf(0x04), listeningModeValues.last())
        assertArrayEquals(
            byteArrayOf(0x04),
            manager.getControlCommandStatus(listeningMode)?.value
        )
        assertEquals(2, conversationAwarenessValues.size)
        assertArrayEquals(byteArrayOf(0x01), conversationAwarenessValues.last())
        assertArrayEquals(
            byteArrayOf(0x01),
            manager.getControlCommandStatus(conversationAwareness)?.value
        )

        manager.unregisterControlCommandListener(listeningMode, listeningModeListener)
        manager.unregisterControlCommandListener(
            conversationAwareness,
            conversationAwarenessListener
        )
        manager.receivePacket(commandPacket(manager, listeningMode, byteArrayOf(0x02)))
        manager.receivePacket(
            commandPacket(manager, conversationAwareness, byteArrayOf(0x02))
        )

        assertEquals(2, listeningModeValues.size)
        assertEquals(2, conversationAwarenessValues.size)
    }

    @Test
    fun receivingBatteryAndEarDetectionPacketsStillInvokesPacketCallbacks() {
        val manager = AACPManager()
        val packetCallback = RecordingPacketCallback()
        manager.setPacketCallback(packetCallback)
        val batteryPacket = manager.createDataPacket(
            byteArrayOf(AACPManager.Companion.Opcodes.BATTERY_INFO, 0x00, 0x01)
        )
        val earDetectionPacket = manager.createDataPacket(
            byteArrayOf(AACPManager.Companion.Opcodes.EAR_DETECTION, 0x00, 0x01)
        )

        manager.receivePacket(batteryPacket)
        manager.receivePacket(earDetectionPacket)

        assertTrue(manager.isValidAacpResponse(batteryPacket))
        assertTrue(manager.isValidAacpResponse(earDetectionPacket))
        assertEquals(1, packetCallback.batteryPackets.size)
        assertArrayEquals(batteryPacket, packetCallback.batteryPackets.single())
        assertEquals(1, packetCallback.earDetectionPackets.size)
        assertArrayEquals(earDetectionPacket, packetCallback.earDetectionPackets.single())
        assertTrue(manager.controlCommandStatusList.isEmpty())
    }

    @Test
    fun conversationAwarenessPacketsRequireTenBytesAndKeepLongerPackets() {
        val manager = AACPManager()
        val packetCallback = RecordingPacketCallback()
        manager.setPacketCallback(packetCallback)
        val packet = manager.createDataPacket(
            byteArrayOf(AACPManager.Companion.Opcodes.CONVERSATION_AWARENESS, 0x00, 0x02, 0x00, 0x00, 0x01)
        )

        for (size in 6..9) manager.receivePacket(packet.copyOf(size))
        assertTrue(packetCallback.conversationAwarenessPackets.isEmpty())

        val longerPacket = packet + byteArrayOf(0x7F, 0x00)
        manager.receivePacket(packet)
        manager.receivePacket(longerPacket)
        assertEquals(2, packetCallback.conversationAwarenessPackets.size)
        assertArrayEquals(packet, packetCallback.conversationAwarenessPackets[0])
        assertArrayEquals(longerPacket, packetCallback.conversationAwarenessPackets[1])
    }

    @Test
    fun receivingSuccessfulHandshakeAckCallsCallbackOncePerSession() {
        val manager = AACPManager()
        val packetCallback = RecordingPacketCallback()
        manager.setPacketCallback(packetCallback)
        val recordedAckPrefix = byteArrayOf(
            0x01, 0x00, 0x04, 0x00, 0x00, 0x00
        )
        // Only the observed six-byte success prefix is available here.
        // The remaining 12 bytes are synthetic, not captured evidence.
        val ackWithSyntheticTail = recordedAckPrefix + ByteArray(12)

        repeat(3) { manager.receivePacket(ackWithSyntheticTail) }

        assertEquals(0, manager.handshakeStatus(ackWithSyntheticTail))
        assertEquals(1, packetCallback.handshakeAcknowledgements)
        assertTrue(packetCallback.unknownPackets.isEmpty())

        manager.resetStartupAcknowledgements()
        manager.receivePacket(ackWithSyntheticTail)

        assertEquals(2, packetCallback.handshakeAcknowledgements)

        manager.resetStartupAcknowledgements()
        manager.receivePacket(byteArrayOf(0x01, 0x00, 0x04, 0x00))

        assertEquals(2, packetCallback.handshakeAcknowledgements)
    }

    @Test
    fun nonSuccessHandshakeAckIsParsedButDoesNotAdvanceStartup() {
        val manager = AACPManager()
        val packetCallback = RecordingPacketCallback()
        manager.setPacketCallback(packetCallback)
        val nonSuccessAck = byteArrayOf(
            0x01, 0x00, 0x04, 0x00, 0x85.toByte(), 0x00
        )

        repeat(3) { manager.receivePacket(nonSuccessAck) }

        assertEquals(0x85, manager.handshakeStatus(nonSuccessAck))
        assertEquals(0, packetCallback.handshakeAcknowledgements)

        manager.receivePacket(byteArrayOf(0x01, 0x00, 0x04, 0x00, 0x00, 0x00))

        assertEquals(1, packetCallback.handshakeAcknowledgements)
    }

    @Test
    fun receivingFeatureFlagsAckCallsCallbackOnceAndDisconnectResetsIt() {
        val manager = AACPManager()
        val packetCallback = RecordingPacketCallback()
        manager.setPacketCallback(packetCallback)
        val featureFlagsAck = byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x2B, 0x00
        )

        repeat(3) { manager.receivePacket(featureFlagsAck) }

        assertEquals(1, packetCallback.featureFlagsAcknowledgements)
        assertTrue(packetCallback.unknownPackets.isEmpty())

        manager.disconnected()
        manager.receivePacket(featureFlagsAck)

        assertEquals(2, packetCallback.featureFlagsAcknowledgements)
    }

    @Test
    fun shortAndWrongPrefixAcksAreIgnored() {
        val manager = AACPManager()
        val packetCallback = RecordingPacketCallback()

        manager.setPacketCallback(packetCallback)
        manager.receivePacket(byteArrayOf(0x01, 0x00, 0x04))
        manager.receivePacket(byteArrayOf(0x01, 0x00, 0x04, 0x00, 0x00))
        manager.receivePacket(byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x2B))
        manager.receivePacket(byteArrayOf(0x01, 0x00, 0x05, 0x00, 0x85.toByte(), 0x00))

        assertTrue(manager.handshakeStatus(byteArrayOf(0x01, 0x00, 0x04)) == null)
        assertEquals(0, packetCallback.handshakeAcknowledgements)
        assertEquals(0, packetCallback.featureFlagsAcknowledgements)
        assertTrue(packetCallback.unknownPackets.isEmpty())
    }

    private fun status(
        identifier: ControlCommandIdentifiers,
        value: Byte,
    ) = ControlCommandStatus(identifier, byteArrayOf(value))

    private fun registerListener(
        manager: AACPManager,
        identifier: ControlCommandIdentifiers,
        onCommand: (AACPManager.ControlCommand) -> Unit,
    ): AACPManager.ControlCommandListener {
        val listener = object : AACPManager.ControlCommandListener {
            override fun onControlCommandReceived(controlCommand: AACPManager.ControlCommand) {
                onCommand(controlCommand)
            }
        }
        manager.registerControlCommandListener(identifier, listener)
        return listener
    }

    private fun commandPacket(
        manager: AACPManager,
        identifier: ControlCommandIdentifiers,
        value: ByteArray,
    ) = manager.createDataPacket(manager.createControlCommandPacket(identifier.value, value))

    private class RecordingPacketCallback : AACPManager.PacketCallback {
        val batteryPackets = mutableListOf<ByteArray>()
        val earDetectionPackets = mutableListOf<ByteArray>()
        val conversationAwarenessPackets = mutableListOf<ByteArray>()
        val controlPackets = mutableListOf<ByteArray>()
        val ownershipChanges = mutableListOf<Boolean>()
        val unknownPackets = mutableListOf<ByteArray>()
        var handshakeAcknowledgements = 0
        var featureFlagsAcknowledgements = 0

        override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
            batteryPackets += batteryInfo
        }

        override fun onEarDetectionReceived(earDetection: ByteArray) {
            earDetectionPackets += earDetection
        }
        override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
            conversationAwarenessPackets += conversationAwareness
        }
        override fun onControlCommandReceived(controlCommand: ByteArray) {
            controlPackets += controlCommand
        }

        override fun onDeviceInformationReceived(deviceInformation: AirPodsInformation) = Unit
        override fun onHeadTrackingReceived(headTracking: ByteArray) = Unit
        override fun onUnknownPacketReceived(packet: ByteArray) {
            unknownPackets += packet
        }
        override fun onProximityKeysReceived(proximityKeys: ByteArray) = Unit
        override fun onStemPressReceived(stemPress: ByteArray) = Unit
        override fun onAudioSourceReceived(audioSource: ByteArray) = Unit
        override fun onOwnershipChangeReceived(owns: Boolean) {
            ownershipChanges += owns
        }

        override fun onConnectedDevicesReceived(connectedDevices: List<ConnectedDevice>) = Unit
        override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) = Unit
        override fun onShowNearbyUI(sender: String) = Unit
        override fun onHeadphoneAccommodationReceived(eqData: FloatArray) = Unit
        override fun onCustomEqReceived(customEq: CustomEq) = Unit
        override fun onCapabilitiesReceived(capabilities: List<Capability>) = Unit
        override fun onHandshakeAcknowledged() {
            handshakeAcknowledgements++
        }
        override fun onFeatureFlagsAcknowledged() {
            featureFlagsAcknowledgements++
        }
    }
}
