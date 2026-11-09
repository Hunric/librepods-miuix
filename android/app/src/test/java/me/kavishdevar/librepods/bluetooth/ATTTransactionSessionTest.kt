package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ATTTransactionSessionTest {
    @Test
    fun readerPublishesReadBeforeWaiterAndLaterNotificationWinsWithoutHoldingStateLock() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        val sent = CountDownLatch(1)
        val publishingRead = CountDownLatch(1)
        val finishReadPublication = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val currentValue = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val waiter = Thread {
            try {
                assertArrayEquals(byteArrayOf(0x0B, 1), gate.transact(socket, 0x0B, 2000,
                    requestOpcode = 0x0A, requestHandle = 0x18,
                    beforeResponseQueued = { response ->
                        publishingRead.countDown()
                        assertTrue(finishReadPublication.await(1, TimeUnit.SECONDS))
                        currentValue.set(response.copyOfRange(1, response.size))
                    }) { sent.countDown() })
                readReturned.countDown()
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true; start() }
        assertTrue(sent.await(1, TimeUnit.SECONDS))
        val reader = Thread {
            try {
                assertTrue(gate.offer(socket, byteArrayOf(0x0B, 1)))
                // The next wire PDU is a notification for the same handle.
                currentValue.set(byteArrayOf(2))
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true; start() }
        assertTrue(publishingRead.await(1, TimeUnit.SECONDS))
        assertFalse(readReturned.await(30, TimeUnit.MILLISECONDS))
        // A Service/reader callback may reenter transaction state from another thread.
        val checkedState = CountDownLatch(1)
        Thread {
            if (gate.isCurrent(socket)) checkedState.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue("response callback held stateLock", checkedState.await(1, TimeUnit.SECONDS))
        finishReadPublication.countDown()
        reader.join(2000)
        waiter.join(2000)
        assertFalse(reader.isAlive)
        assertFalse(waiter.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertArrayEquals(byteArrayOf(2), currentValue.get())
    }

    @Test
    fun queuedWriteRechecksGuardAfterTransactionLockAndCancellationDoesNotPoisonSocket() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        val readSent = CountDownLatch(1)
        val writeAttempted = CountDownLatch(1)
        val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val sentWrite = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val read = Thread {
            try {
                assertNotNull(gate.transact(socket, 0x0B, 2000) { readSent.countDown() })
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true; start() }
        assertTrue(readSent.await(1, TimeUnit.SECONDS))
        val write = Thread {
            try {
                writeAttempted.countDown()
                assertNull(gate.transact(socket, 0x13, 1000, beforeSend = { valid.get() }) {
                    sentWrite.set(true)
                })
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true; start() }
        assertTrue(writeAttempted.await(1, TimeUnit.SECONDS))
        // Real feedback retires desired while this write is queued behind the read.
        valid.set(false)
        assertTrue(gate.offer(socket, byteArrayOf(0x0B, 9)))
        read.join(2000)
        write.join(2000)
        assertFalse(read.isAlive)
        assertFalse(write.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertFalse(sentWrite.get())
        assertArrayEquals(byteArrayOf(0x13), gate.transact(socket, 0x13, 100) {
            gate.offer(socket, byteArrayOf(0x13))
        })
    }

    @Test
    fun cancellationAtOutputGuardDoesNotPoisonSocketButOrdinarySendFailureDoes() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        // Simulates a guard invalidating after transactionLock but before output.write.
        assertNull(gate.transact(socket, 0x13, 100) { throw ATTRequestCancelledBeforeSend() })
        assertArrayEquals(byteArrayOf(0x13), gate.transact(socket, 0x13, 100) {
            gate.offer(socket, byteArrayOf(0x13))
        })
        assertNull(gate.transact(socket, 0x13, 100) { throw java.io.IOException("partial write") })
        assertNull(gate.transact(socket, 0x13, 100) { fail("partial write must quarantine session") })
    }

    @Test
    fun timeoutQuarantinesLateReplyAndAllLaterTransactionsUntilNewSocket() {
        val gate = ATTTransactionSession()
        val oldSocket = Any()
        assertTrue(gate.begin(oldSocket))
        assertNull(gate.transact(oldSocket, 0x0B, 1) {})
        assertFalse(gate.offer(oldSocket, byteArrayOf(0x0B, 42)))
        var sends = 0
        assertNull(gate.transact(oldSocket, 0x0B, 10) { sends++ })
        assertNull(gate.transact(oldSocket, 0x13, 10) { sends++ })
        // Re-entering begin with the same socket cannot undo quarantine.
        gate.begin(oldSocket)
        assertNull(gate.transact(oldSocket, 0x0B, 10) { sends++ })
        assertEquals(0, sends)
        val newSocket = Any()
        assertTrue(gate.begin(newSocket))
        assertArrayEquals(byteArrayOf(0x0B, 9), gate.transact(newSocket, 0x0B, 100) {
            assertFalse(gate.offer(oldSocket, byteArrayOf(0x0B, 42)))
            assertTrue(gate.offer(newSocket, byteArrayOf(0x0B, 9)))
        })
    }

    @Test
    fun readAndWriteRequestsRemainSerialized() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        val readSent = CountDownLatch(1)
        val writeAttempted = CountDownLatch(1)
        val writeSent = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val readResponse = AtomicReference<ByteArray?>()
        val writeResponse = AtomicReference<ByteArray?>()
        fun worker(action: () -> Unit) = Thread {
            try { action() } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true }
        val read = worker {
            readResponse.set(gate.transact(socket, 0x0B, 2000) { readSent.countDown() })
        }
        val write = worker {
            writeAttempted.countDown()
            writeResponse.set(gate.transact(socket, 0x13, 2000) {
                writeSent.countDown()
                gate.offer(socket, byteArrayOf(0x13))
            })
        }
        read.start()
        assertTrue(readSent.await(1, TimeUnit.SECONDS))
        write.start()
        assertTrue(writeAttempted.await(1, TimeUnit.SECONDS))
        assertFalse(writeSent.await(50, TimeUnit.MILLISECONDS))
        assertTrue(gate.offer(socket, byteArrayOf(0x0B, 7)))
        read.join(2000)
        write.join(2000)
        assertFalse(read.isAlive)
        assertFalse(write.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertArrayEquals(byteArrayOf(0x0B, 7), readResponse.get())
        assertArrayEquals(byteArrayOf(0x13), writeResponse.get())
    }

    @Test
    fun unsolicitedAndPreviousRepliesCannotSatisfyNewTransaction() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        assertFalse(gate.offer(socket, byteArrayOf(0x0B, 1)))
        assertArrayEquals(byteArrayOf(0x0B, 2), gate.transact(socket, 0x0B, 100) {
            assertFalse(gate.offer(socket, byteArrayOf(0x13)))
            gate.offer(socket, byteArrayOf(0x0B, 2))
            gate.offer(socket, byteArrayOf(0x0B, 3))
        })
        assertArrayEquals(byteArrayOf(0x0B, 4), gate.transact(socket, 0x0B, 100) {
            gate.offer(socket, byteArrayOf(0x0B, 4))
        })
    }

    @Test
    fun endingSessionWakesWaiterAndOldSocketCannotFeedNewSession() {
        val gate = ATTTransactionSession()
        val oldSocket = Any()
        gate.begin(oldSocket)
        val sent = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>()
        val waiter = Thread {
            result.set(gate.transact(oldSocket, 0x0B, 5000) { sent.countDown() })
        }.apply { isDaemon = true; start() }
        assertTrue(sent.await(1, TimeUnit.SECONDS))
        gate.end(oldSocket)
        waiter.join(1000)
        assertFalse(waiter.isAlive)
        assertNull(result.get())
        assertFalse(gate.isCurrent(oldSocket))
        assertFalse(gate.begin(oldSocket))
        val newSocket = Any()
        gate.begin(newSocket)
        assertTrue(gate.isCurrent(newSocket))
        assertFalse(gate.isCurrent(oldSocket))
        assertFalse(gate.offer(oldSocket, byteArrayOf(0x0B, 1)))
        gate.end(oldSocket)
        assertTrue(gate.isCurrent(newSocket))
    }

    @Test
    fun queueOwnsReplyBytesAndWriteResponseHasNoValuePayload() {
        val gate = ATTTransactionSession()
        val socket = Any()
        gate.begin(socket)
        val packet = byteArrayOf(0x0B, 42)
        val response = gate.transact(socket, 0x0B, 100) {
            gate.offer(socket, packet)
            packet[1] = 99
        }
        assertArrayEquals(byteArrayOf(0x0B, 42), response)
        assertArrayEquals(byteArrayOf(0x13), gate.transact(socket, 0x13, 100) {
            gate.offer(socket, byteArrayOf(0x13))
        })
    }

    @Test
    fun errorResponseAndSendFailureDoNotPermitFurtherRequests() {
        for (throwOnSend in listOf(false, true)) {
            val gate = ATTTransactionSession()
            val socket = Any()
            gate.begin(socket)
            assertNull(gate.transact(socket, 0x0B, 100) {
                if (throwOnSend) throw java.io.IOException("synthetic send failure")
                gate.offer(socket, byteArrayOf(0x01, 0x0A, 0x18, 0, 1))
            })
            assertNull(gate.transact(socket, 0x13, 100) { fail("quarantined socket sent a request") })
        }
    }

    @Test
    fun remoteCallbackRunsFirstAndEachConsumerOwnsItsCopy() {
        val socket = Any()
        val gate = ATTTransactionSession().apply { begin(socket) }
        val value = byteArrayOf(42)
        val order = mutableListOf<String>()
        deliverATTRemoteValue(socket, value, gate::isCurrent,
            remote = { copied ->
                order.add("service")
                assertNotSame(value, copied)
                copied[0] = 99
            },
            notification = { copied ->
                order.add("viewModel")
                assertNotSame(value, copied)
                assertEquals(42.toByte(), copied[0])
                copied[0] = 0
            },
            onError = { throw AssertionError(it) }
        )
        assertEquals(listOf("service", "viewModel"), order)
        assertArrayEquals(byteArrayOf(42), value)
    }

    @Test
    fun oldSocketAndEmptyValuesNeverReachEitherCallback() {
        val oldSocket = Any()
        val gate = ATTTransactionSession().apply { begin(oldSocket); begin(Any()) }
        var reports = 0
        deliverATTRemoteValue(oldSocket, byteArrayOf(1), gate::isCurrent,
            remote = { reports++ }, notification = { reports++ }, onError = { throw AssertionError(it) })
        deliverATTRemoteValue(oldSocket, byteArrayOf(), { true },
            remote = { reports++ }, notification = { reports++ }, onError = { throw AssertionError(it) })
        assertEquals(0, reports)
    }

    @Test
    fun serviceCallbackFailureDoesNotStopViewModelButSessionChangeDoes() {
        val socket = Any()
        val gate = ATTTransactionSession().apply { begin(socket) }
        var notifications = 0
        var errors = 0
        deliverATTRemoteValue(socket, byteArrayOf(1), gate::isCurrent,
            remote = { throw IllegalStateException("synthetic callback failure") },
            notification = { notifications++ }, onError = { errors++ })
        assertEquals(1, errors)
        assertEquals(1, notifications)
        deliverATTRemoteValue(socket, byteArrayOf(1), gate::isCurrent,
            remote = { gate.begin(Any()) },
            notification = { notifications++ }, onError = { errors++ })
        assertEquals(1, notifications)
    }

    @Test
    fun matchedErrorTerminatesReadOrWriteAndAllowsNextHandle() {
        for ((responseOpcode, requestOpcode) in listOf(0x0B.toByte() to 0x0A.toByte(), 0x13.toByte() to 0x12.toByte())) {
            for (errorCode in listOf(0x02, 0xFF)) {
                val gate = ATTTransactionSession()
                val socket = Any()
                gate.begin(socket)
                assertNull(gate.transact(socket, responseOpcode, 100, requestOpcode, 0x0118) {
                    gate.offer(socket, byteArrayOf(0x01, requestOpcode, 0x18, 0x01, errorCode.toByte()))
                })
                assertArrayEquals(byteArrayOf(0x0B, 9), gate.transact(socket, 0x0B, 100, 0x0A, 0x2A) {
                    gate.offer(socket, byteArrayOf(0x0B, 9))
                })
            }
        }
    }

    @Test
    fun onlySpecifiedRequestLevelErrorsPermitZeroHandle() {
        for (errorCode in listOf(0x04, 0x06, 0x0E, 0x11)) {
            val gate = ATTTransactionSession()
            val socket = Any()
            gate.begin(socket)
            assertNull(gate.transact(socket, 0x0B, 100, 0x0A, 0x18) {
                gate.offer(socket, byteArrayOf(0x01, 0x0A, 0, 0, errorCode.toByte()))
            })
            assertArrayEquals(byteArrayOf(0x0B, 9), gate.transact(socket, 0x0B, 100, 0x0A, 0x2A) {
                gate.offer(socket, byteArrayOf(0x0B, 9))
            })
        }
    }

    @Test
    fun mismatchedOrTruncatedErrorsStillQuarantineSocket() {
        val invalidErrors = listOf(
            byteArrayOf(0x01, 0x12, 0x18, 0, 0x02), // wrong request opcode
            byteArrayOf(0x01, 0x0A, 0x2A, 0, 0x02), // another real handle
            byteArrayOf(0x01, 0x0A, 0x18, 1, 0x02), // wrong high handle byte
            byteArrayOf(0x01, 0x0A, 0, 0, 0x02), // zero not allowed for Read Not Permitted
            byteArrayOf(0x01, 0x0A, 0, 0, 0xFF.toByte()), // unknown error cannot justify zero handle
            byteArrayOf(0x01, 0x12, 0, 0, 0x06), // zero exception still requires request opcode
            byteArrayOf(0x01, 0x0A, 0x18, 0), // missing error code
            byteArrayOf(0x01, 0x0A, 0x18, 0, 0x02, 0) // extra bytes are not a complete error PDU
        )
        for (error in invalidErrors) {
            val gate = ATTTransactionSession()
            val socket = Any()
            gate.begin(socket)
            assertNull(gate.transact(socket, 0x0B, 100, 0x0A, 0x18) { gate.offer(socket, error) })
            assertNull(gate.transact(socket, 0x0B, 100, 0x0A, 0x2A) { fail("invalid error unquarantined session") })
        }
    }

    @Test
    fun queuedOldSocketWriteNeverSendsAfterSessionSwitch() {
        val gate = ATTTransactionSession()
        val oldSocket = Any()
        val newSocket = Any()
        gate.begin(oldSocket)
        val readSent = CountDownLatch(1)
        val writeAttempted = CountDownLatch(1)
        val staleWriteSent = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val oldRead = Thread {
            try {
                assertNull(gate.transact(oldSocket, 0x0B, 5000) { readSent.countDown() })
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true; start() }
        assertTrue(readSent.await(1, TimeUnit.SECONDS))
        val oldWrite = Thread {
            try {
                writeAttempted.countDown()
                assertNull(gate.transact(oldSocket, 0x13, 100) { staleWriteSent.set(true) })
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
        }.apply { isDaemon = true; start() }
        assertTrue(writeAttempted.await(1, TimeUnit.SECONDS))
        gate.begin(newSocket)
        oldRead.join(1000)
        oldWrite.join(1000)
        assertFalse(oldRead.isAlive)
        assertFalse(oldWrite.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertFalse(staleWriteSent.get())
        assertArrayEquals(byteArrayOf(0x13), gate.transact(newSocket, 0x13, 100) {
            gate.offer(newSocket, byteArrayOf(0x13))
        })
    }

    @Test
    fun switchDuringOldSocketWriteRejectsReplyWithoutPoisoningNewSocket() {
        val gate = ATTTransactionSession()
        val oldSocket = Any()
        val newSocket = Any()
        gate.begin(oldSocket)
        assertNull(gate.transact(oldSocket, 0x13, 100) {
            gate.begin(newSocket)
            assertFalse(gate.offer(oldSocket, byteArrayOf(0x13)))
        })
        assertNull(gate.transact(oldSocket, 0x13, 100) { fail("old socket was reused after switch") })
        assertArrayEquals(byteArrayOf(0x0B, 7), gate.transact(newSocket, 0x0B, 100) {
            assertFalse(gate.offer(oldSocket, byteArrayOf(0x0B, 99)))
            gate.offer(newSocket, byteArrayOf(0x0B, 7))
        })
    }
}
