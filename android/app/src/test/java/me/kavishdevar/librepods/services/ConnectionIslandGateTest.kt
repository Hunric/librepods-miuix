package me.kavishdevar.librepods.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionIslandGateTest {
    @Test
    fun doesNotTriggerBeforeConnected() {
        val gate = ConnectionIslandGate()

        assertFalse(gate.consumeIfReady(hasValidBattery = true))
        assertFalse(gate.isPending)
    }

    @Test
    fun waitsForValidBatteryAndConsumesOnlyOnce() {
        val gate = ConnectionIslandGate()
        gate.onConnected()

        assertTrue(gate.isPending)
        assertFalse(gate.consumeIfReady(hasValidBattery = false))
        assertTrue(gate.consumeIfReady(hasValidBattery = true))
        assertFalse(gate.consumeIfReady(hasValidBattery = true))
        assertFalse(gate.isPending)

        gate.onConnectedIslandShown()
        assertFalse(gate.isPending)
    }

    @Test
    fun allowsOnlyOneIslandForEachConnection() {
        val gate = ConnectionIslandGate()
        gate.onConnected()

        assertTrue(gate.consumeIfReady(hasValidBattery = true))
        assertFalse(gate.consumeIfReady(hasValidBattery = true))
    }

    @Test
    fun repeatedConnectedSignalsDoNotRearmNotice() {
        val gate = ConnectionIslandGate()
        assertTrue(gate.onConnected())
        assertTrue(gate.consumeIfReady(hasValidBattery = true))

        assertFalse(gate.onConnected())
        assertFalse(gate.isPending)
        assertFalse(gate.consumeIfReady(hasValidBattery = true))
    }

    @Test
    fun disconnectAndReconnectResetPendingState() {
        val gate = ConnectionIslandGate()
        gate.onConnected()
        assertTrue(gate.consumeIfReady(hasValidBattery = true))

        gate.onDisconnected()
        assertFalse(gate.isPending)
        assertFalse(gate.consumeIfReady(hasValidBattery = true))

        gate.onConnected()
        assertTrue(gate.isPending)
        assertTrue(gate.consumeIfReady(hasValidBattery = true))
    }

    @Test
    fun firstAndRepeatedNotWornPacketsDoNotCloseIsland() {
        val gate = ConnectionIslandGate()
        gate.onConnected()

        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = false))
        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = false))
    }

    @Test
    fun closesOnlyAfterWornStateTransitionsToBothNotWorn() {
        val gate = ConnectionIslandGate()
        gate.onConnected()

        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = true, rightInEar = false))
        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = true, rightInEar = true))
        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = true))
        assertTrue(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = false))
        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = false))
    }

    @Test
    fun firstNotWornPacketAfterReconnectDoesNotCloseIsland() {
        val gate = ConnectionIslandGate()
        gate.onConnected()
        gate.shouldCloseIslandOnEarDetection(leftInEar = true, rightInEar = true)

        gate.onDisconnected()
        gate.onConnected()

        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = false))
        assertFalse(gate.shouldCloseIslandOnEarDetection(leftInEar = false, rightInEar = false))
    }

    @Test
    fun doesNotRepeatIslandAlreadyShownOnEarDetection() {
        val gate = ConnectionIslandGate()
        gate.onConnected()
        gate.onConnectedIslandShown()

        assertFalse(gate.isPending)
        assertFalse(gate.consumeIfReady(hasValidBattery = true))
    }

    @Test
    fun concurrentConsumersCanClaimOnlyOnce() {
        val gate = ConnectionIslandGate()
        gate.onConnected()
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val consumed = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workers)

        repeat(workers) {
            executor.execute {
                ready.countDown()
                start.await()
                if (gate.consumeIfReady(hasValidBattery = true)) consumed.incrementAndGet()
            }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, consumed.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
