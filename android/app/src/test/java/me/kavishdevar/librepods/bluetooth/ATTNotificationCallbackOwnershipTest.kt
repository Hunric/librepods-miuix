package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ATTNotificationCallbackOwnershipTest {
    @Test
    fun clearOnlyRemovesTheCallbackThatStillOwnsTheSlot() {
        val manager = ATTManagerv2()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val oldCallback: (Byte, ByteArray) -> Unit = { _, _ -> calls.incrementAndGet() }
        val newCallback: (Byte, ByteArray) -> Unit = { _, _ -> calls.decrementAndGet() }

        manager.setOnNotificationReceived(oldCallback)
        manager.setOnNotificationReceived(newCallback)

        assertFalse(manager.clearOnNotificationReceived(oldCallback))
        assertTrue(manager.clearOnNotificationReceived(newCallback))
        assertFalse(manager.clearOnNotificationReceived(newCallback))
    }

    @Test
    fun oldCleanupRacingNewRegistrationLeavesNewCallbackOwned() {
        val manager = ATTManagerv2()
        val timeout = 5L

        repeat(50) {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val oldCallback: (Byte, ByteArray) -> Unit = { _, _ -> calls.incrementAndGet() }
            val newCallback: (Byte, ByteArray) -> Unit = { _, _ -> calls.decrementAndGet() }
            manager.setOnNotificationReceived(oldCallback)

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val failure = AtomicReference<Throwable?>(null)

            fun runAfterStart(action: () -> Unit) = Thread {
                try {
                    check(start.await(timeout, TimeUnit.SECONDS)) { "timed out waiting to start" }
                    action()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }

            val oldCleanup = runAfterStart { manager.clearOnNotificationReceived(oldCallback) }
            val newRegistration = runAfterStart { manager.setOnNotificationReceived(newCallback) }
            oldCleanup.start()
            newRegistration.start()
            start.countDown()

            assertTrue("callback operations timed out", done.await(timeout, TimeUnit.SECONDS))
            oldCleanup.join(TimeUnit.SECONDS.toMillis(timeout))
            newRegistration.join(TimeUnit.SECONDS.toMillis(timeout))
            assertFalse("old cleanup thread did not finish", oldCleanup.isAlive)
            assertFalse("new registration thread did not finish", newRegistration.isAlive)
            failure.get()?.let { throw AssertionError("callback operation failed", it) }

            assertTrue("new callback was unexpectedly removed", manager.clearOnNotificationReceived(newCallback))
            assertFalse(manager.clearOnNotificationReceived(newCallback))
        }
    }
}
