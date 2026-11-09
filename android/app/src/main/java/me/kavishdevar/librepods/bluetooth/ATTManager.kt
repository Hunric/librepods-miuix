/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ATTManager"

enum class ATTHandles(val value: Int) {
    TRANSPARENCY(0x18),
    LOUD_SOUND_REDUCTION(0x1B),
    HEARING_AID(0x2A)
}

enum class ATTCCCDHandles(val value: Int) {
    TRANSPARENCY(ATTHandles.TRANSPARENCY.value + 1),
    //    LOUD_SOUND_REDUCTION(ATTHandles.LOUD_SOUND_REDUCTION.value + 1), // doesn't work
    HEARING_AID(ATTHandles.HEARING_AID.value + 1)
}

/** ACK reports only transaction completion; Value is a fresh remote read. */
sealed interface ATTResult {
    data object Ack : ATTResult
    data class Value(val value: ByteArray) : ATTResult
    data class RemoteError(val code: Int) : ATTResult
    data object TimeoutOrQuarantined : ATTResult
    data object Cancelled : ATTResult
    data object TransportFailure : ATTResult
}

/** Success replies carry no handle; an unterminated transaction quarantines its socket. */
internal class ATTRequestCancelledBeforeSend : RuntimeException()

/** The fence is taken only after the final guard and before any setting bytes are written. */
internal fun writeAttPacket(output: OutputStream, packet: ByteArray,
    beforeWrite: () -> Boolean, onWriteStarted: () -> Boolean) {
    synchronized(output) {
        if (!beforeWrite() || !onWriteStarted()) throw ATTRequestCancelledBeforeSend()
        output.write(packet)
        output.flush()
    }
}

internal class ATTTransactionSession {
    private class Session(val token: Any) {
        var open = true
        var poisoned = false
        var opcode: Byte? = null
        var responses: LinkedBlockingQueue<ByteArray>? = null
        var beforeResponseQueued: ((ByteArray) -> Unit)? = null
    }

    private val stateLock = Any()
    private val transactionLock = Any()
    private var session: Session? = null

    fun begin(token: Any): Boolean = synchronized(stateLock) {
        val previous = session
        if (previous != null && previous.token === token) return@synchronized previous.open
        previous?.open = false
        previous?.responses?.offer(byteArrayOf())
        session = Session(token)
        true
    }

    fun isCurrent(token: Any): Boolean = synchronized(stateLock) {
        session?.let { it.token === token && it.open } == true
    }

    fun isUsable(token: Any): Boolean = synchronized(stateLock) {
        session?.let { it.token === token && it.open && !it.poisoned } == true
    }

    fun end(token: Any) = synchronized(stateLock) {
        session?.takeIf { it.token === token }?.let {
            it.open = false
            it.responses?.offer(byteArrayOf())
            it.responses = null
            it.opcode = null
            it.beforeResponseQueued = null
        }
    }

    fun offer(token: Any, packet: ByteArray): Boolean {
        val snapshot = packet.copyOf()
        val delivery = synchronized(stateLock) {
            val active = session ?: return false
            if (active.token !== token || !active.open || active.poisoned || snapshot.isEmpty()) return false
            if (snapshot[0] != active.opcode && snapshot[0] != 0x01.toByte()) return false
            val queue = active.responses ?: return false
            Triple(active, queue, active.beforeResponseQueued.takeIf { snapshot[0] == active.opcode })
        }
        // The reader publishes reads in wire order before releasing their waiter.
        // Callbacks may acquire readerLock; never invoke them while holding stateLock.
        delivery.third?.invoke(snapshot.copyOf())
        return synchronized(stateLock) {
            val (active, queue) = delivery
            if (session !== active || !active.open || active.poisoned || active.responses !== queue) false
            else queue.offer(snapshot)
        }
    }

    fun transact(
        token: Any,
        opcode: Byte,
        timeoutMillis: Long,
        requestOpcode: Byte? = null,
        requestHandle: Int? = null,
        beforeSend: () -> Boolean = { true },
        beforeResponseQueued: ((ByteArray) -> Unit)? = null,
        send: () -> Unit
    ): ByteArray? = (transactResult(token, opcode, timeoutMillis, requestOpcode, requestHandle,
        beforeSend, beforeResponseQueued, send = send) as? ATTResult.Value)?.value

    fun transactResult(
        token: Any,
        opcode: Byte,
        timeoutMillis: Long,
        requestOpcode: Byte? = null,
        requestHandle: Int? = null,
        beforeSend: () -> Boolean = { true },
        beforeResponseQueued: ((ByteArray) -> Unit)? = null,
        validateSuccess: (ByteArray) -> Boolean = { true },
        send: () -> Unit
    ): ATTResult =
        synchronized(transactionLock) {
            if (timeoutMillis <= 0) return@synchronized ATTResult.TransportFailure
            val queue = LinkedBlockingQueue<ByteArray>()
            val active = synchronized(stateLock) {
                session?.takeIf { it.token === token && it.open && !it.poisoned }?.also {
                    // A fresh queue discards all earlier packets before this request is sent.
                    it.responses = queue
                    it.opcode = opcode
                    it.beforeResponseQueued = beforeResponseQueued
                }
            } ?: return@synchronized if (synchronized(stateLock) {
                    session?.let { it.token === token && it.open && it.poisoned } == true
                }) ATTResult.TimeoutOrQuarantined else ATTResult.Cancelled
            var terminated = false
            try {
                if (!beforeSend()) throw ATTRequestCancelledBeforeSend()
                send()
                val response = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                val success = response != null && response.firstOrNull() == opcode && isCurrent(token) && validateSuccess(response)
                // Core Vol 3 Part F 3.4.1.1: a matching Error Response terminates
                // this request without producing a later success response.
                val errorHandle = if (response != null && response.size == 5) {
                    (response[2].toInt() and 0xff) or ((response[3].toInt() and 0xff) shl 8)
                } else null
                // Core Vol 3 Part F 3.3 permits handle zero for these request-level
                // errors: Invalid PDU, Request Not Supported, Unlikely Error,
                // and Insufficient Resources. Other errors must identify our handle.
                val requestLevelError = response != null && response.size == 5 && errorHandle == 0 &&
                    (response[4].toInt() and 0xff) in setOf(0x04, 0x06, 0x0E, 0x11)
                val matchedError = response != null && response.size == 5 &&
                    response[0] == 0x01.toByte() && requestOpcode != null && requestHandle != null &&
                    response[1] == requestOpcode && (errorHandle == requestHandle || requestLevelError) &&
                    isCurrent(token)
                terminated = success || matchedError
                when {
                    success -> ATTResult.Value(response.copyOf())
                    matchedError -> ATTResult.RemoteError(response[4].toInt() and 0xff)
                    response == null && !isCurrent(token) -> ATTResult.Cancelled
                    response == null -> ATTResult.TimeoutOrQuarantined
                    response.isEmpty() || !isCurrent(token) -> ATTResult.Cancelled
                    else -> ATTResult.TransportFailure
                }
            } catch (_: ATTRequestCancelledBeforeSend) {
                // No bytes were written, so there can be no delayed reply to quarantine.
                terminated = true
                ATTResult.Cancelled
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                ATTResult.Cancelled
            } catch (_: Exception) {
                ATTResult.TransportFailure
            } finally {
                synchronized(stateLock) {
                    if (!terminated) active.poisoned = true
                    active.responses = null
                    active.opcode = null
                    active.beforeResponseQueued = null
                }
            }
        }
}

/** Callback delivery is independent of Android sockets so ownership/isolation can be tested. */
internal fun <T : Any> deliverATTRemoteValue(
    token: T,
    value: ByteArray,
    isCurrent: (T) -> Boolean,
    remote: (ByteArray) -> Unit,
    notification: ((ByteArray) -> Unit)?,
    onError: (Throwable) -> Unit
) {
    if (value.isEmpty() || !isCurrent(token)) return
    try { remote(value.copyOf()) } catch (t: Throwable) { onError(t) }
    if (!isCurrent(token)) return
    try { notification?.invoke(value.copyOf()) } catch (t: Throwable) { onError(t) }
}

class ATTManagerv2 {
    // Only remote reads and notifications populate this cache; never local writes.
    val characteristicList: MutableMap<ATTHandles, ByteArray> = ConcurrentHashMap()
    private val transactions = ATTTransactionSession()
    private val readerLock = Any()
    private val readerRunning = AtomicBoolean(false)
    @Volatile private var readerThread: Thread? = null
    @Volatile private var readerSocket: BluetoothSocket? = null

    @Volatile
    private var onNotificationReceived: ((handle: Byte, value: ByteArray) -> Unit)? = null
    private val notificationCallbackLock = Any()
    @Volatile
    private var onRemoteValueReceived: ((ATTHandles, ByteArray, BluetoothSocket) -> Unit)? = null

    fun setOnRemoteValueReceived(listener: ((ATTHandles, ByteArray, BluetoothSocket) -> Unit)?) {
        onRemoteValueReceived = listener
    }

    fun startReader() {
        synchronized(readerLock) {
            // A blocking read may survive interrupt; never overlap old and new readers.
            if (readerThread?.isAlive == true) return
            val socket = BluetoothConnectionManager.attSocket ?: return
            if (!transactions.begin(socket)) return
            characteristicList.clear()
            readerSocket = socket
            readerRunning.set(true)
            readerThread = Thread {
                try {
                    runReaderLoop(socket)
                } catch (t: Throwable) {
                    Log.e(TAG, "reader thread crashed", t)
                } finally {
                    synchronized(readerLock) {
                        transactions.end(socket)
                        readerRunning.set(false)
                        if (readerSocket === socket) characteristicList.clear()
                    }
                }
            }.also { it.name = "ATT-Reader"; it.isDaemon = true; it.start() }
        }
    }

    fun stopReader() {
        synchronized(readerLock) {
            readerRunning.set(false)
            readerSocket?.let(transactions::end)
            readerThread?.interrupt()
            characteristicList.clear()
            // Retain the thread until it actually exits, even if input.read ignores interrupt.
        }
    }

    /** The caller closes the old socket to unblock input.read before starting a new session. */
    fun stopReaderAndAwait(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        val thread = synchronized(readerLock) {
            stopReader()
            readerThread
        } ?: return true
        if (thread === Thread.currentThread()) return false
        try {
            thread.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (thread.isAlive) return false
        synchronized(readerLock) {
            if (readerThread === thread) readerThread = null
        }
        return true
    }

    /** A retired session must never stop a newer session's reader. */
    fun stopReaderAndAwaitForSocket(expectedSocket: BluetoothSocket, timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        val thread = synchronized(readerLock) {
            if (readerSocket !== expectedSocket) return true
            stopReader()
            readerThread
        } ?: return true
        if (thread === Thread.currentThread()) return false
        try {
            thread.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (thread.isAlive) return false
        synchronized(readerLock) {
            if (readerThread === thread) readerThread = null
        }
        return true
    }

    fun setOnNotificationReceived(listener: ((handle: Byte, value: ByteArray) -> Unit)?) {
        synchronized(notificationCallbackLock) { onNotificationReceived = listener }
    }

    fun clearOnNotificationReceived(expectedListener: (handle: Byte, value: ByteArray) -> Unit): Boolean {
        synchronized(notificationCallbackLock) {
            if (onNotificationReceived !== expectedListener) return false
            onNotificationReceived = null
            return true
        }
    }

    fun enableNotification(handle: ATTCCCDHandles) {
        writeCharacteristic(handle.value.toByte(), byteArrayOf(0x01))
    }

    fun enableNotificationForSocket(
        expectedSocket: BluetoothSocket,
        handle: ATTCCCDHandles,
        timeoutMillis: Long = 2000,
        beforeSend: () -> Boolean = { true }
    ): ATTResult = writeConfirmedResult(expectedSocket, handle.value.toByte(), byteArrayOf(0x01), timeoutMillis, beforeSend)

    fun getCharacteristic(handle: ATTHandles): ByteArray? =
        characteristicList[handle]?.takeIf { it.isNotEmpty() }?.copyOf() ?: readCharacteristic(handle)

    private fun isCurrent(socket: BluetoothSocket): Boolean =
        BluetoothConnectionManager.attSocket === socket && transactions.isCurrent(socket)

    fun isSessionUsable(expectedSocket: BluetoothSocket): Boolean =
        BluetoothConnectionManager.attSocket === expectedSocket && transactions.isUsable(expectedSocket)

    fun hasRunningReader(): Boolean = synchronized(readerLock) { readerThread?.isAlive == true }

    fun readCharacteristic(handle: ATTHandles, timeoutMillis: Long = 2000): ByteArray? {
        val socket = BluetoothConnectionManager.attSocket ?: return null
        return readCharacteristicForSocket(socket, handle, timeoutMillis)
    }

    fun readCharacteristicForSocket(
        expectedSocket: BluetoothSocket,
        handle: ATTHandles,
        timeoutMillis: Long = 2000
    ): ByteArray? = (readCharacteristicResultForSocket(expectedSocket, handle, timeoutMillis) as? ATTResult.Value)?.value

    fun readCharacteristicResultForSocket(
        expectedSocket: BluetoothSocket,
        handle: ATTHandles,
        timeoutMillis: Long = 2000,
        beforeSend: () -> Boolean = { true }
    ): ATTResult {
        val socket = expectedSocket
        if (!isCurrent(socket)) return ATTResult.Cancelled
        val response = transactions.transactResult(socket, 0x0B, timeoutMillis, requestOpcode = 0x0A, requestHandle = handle.value,
            beforeSend = beforeSend,
            beforeResponseQueued = { response ->
                if (response.size > 1) publishRemoteValue(handle, response.copyOfRange(1, response.size), socket)
            }, validateSuccess = { it.size > 1 }) {
            check(isCurrent(socket))
            val output = socket.outputStream
            synchronized(output) {
                check(isCurrent(socket))
                if (!beforeSend()) throw ATTRequestCancelledBeforeSend()
                output.write(byteArrayOf(0x0A, handle.value.toByte(), 0x00))
                output.flush()
            }
        }
        if (response !is ATTResult.Value) return response
        if (!isCurrent(socket)) return ATTResult.Cancelled
        return ATTResult.Value(response.value.copyOfRange(1, response.value.size))
    }

    fun writeCharacteristicConfirmed(handle: ATTHandles, data: ByteArray, timeoutMillis: Long = 2000): Boolean {
        val socket = BluetoothConnectionManager.attSocket ?: return false
        return writeCharacteristicConfirmedForSocket(socket, handle, data, timeoutMillis)
    }

    fun writeCharacteristicConfirmedForSocket(
        expectedSocket: BluetoothSocket,
        handle: ATTHandles,
        data: ByteArray,
        timeoutMillis: Long = 2000,
        beforeSend: () -> Boolean = { true }
    ): Boolean = writeCharacteristicResultForSocket(expectedSocket, handle, data, timeoutMillis, beforeSend) is ATTResult.Ack

    fun writeCharacteristicResultForSocket(
        expectedSocket: BluetoothSocket,
        handle: ATTHandles,
        data: ByteArray,
        timeoutMillis: Long = 2000,
        beforeSend: () -> Boolean = { true },
        onWriteStarted: () -> Boolean = { true }
    ): ATTResult = writeConfirmedResult(expectedSocket, handle.value.toByte(), data, timeoutMillis, beforeSend, onWriteStarted)

    fun writeCharacteristic(handle: ATTHandles, data: ByteArray, timeoutMillis: Long = 2000) {
        writeCharacteristicConfirmed(handle, data, timeoutMillis)
    }

    fun writeCharacteristic(handle: Byte, data: ByteArray, timeoutMillis: Long = 2000) {
        val socket = BluetoothConnectionManager.attSocket ?: return
        writeConfirmed(socket, handle, data, timeoutMillis)
    }

    private fun writeConfirmed(socket: BluetoothSocket, handle: Byte, data: ByteArray, timeoutMillis: Long,
        beforeSend: () -> Boolean = { true }): Boolean =
        writeConfirmedResult(socket, handle, data, timeoutMillis, beforeSend) is ATTResult.Ack

    private fun writeConfirmedResult(socket: BluetoothSocket, handle: Byte, data: ByteArray, timeoutMillis: Long,
        beforeSend: () -> Boolean = { true }, onWriteStarted: () -> Boolean = { true }): ATTResult {
        if (!isCurrent(socket)) return ATTResult.Cancelled
        val payload = data.copyOf()
        val response = transactions.transactResult(socket, 0x13, timeoutMillis, requestOpcode = 0x12,
            requestHandle = handle.toInt() and 0xff, beforeSend = beforeSend, validateSuccess = { it.size == 1 }) {
            check(isCurrent(socket))
            val output = socket.outputStream
            writeAttPacket(output, byteArrayOf(0x12, handle, 0x00) + payload,
                beforeWrite = { isCurrent(socket) && beforeSend() }, onWriteStarted = onWriteStarted)
        }
        // ATT Write Response is exactly its opcode. It is not a remote value report.
        if (response !is ATTResult.Value) return response
        return if (isCurrent(socket)) ATTResult.Ack else ATTResult.Cancelled
    }

    private fun publishRemoteValue(
        handle: ATTHandles,
        value: ByteArray,
        socket: BluetoothSocket,
        notifyViewModel: Boolean = false
    ) {
        synchronized(readerLock) {
            if (value.isEmpty() || !isCurrent(socket)) return
            characteristicList[handle] = value.copyOf()
        }
        deliverATTRemoteValue(
            socket, value, ::isCurrent,
            remote = { onRemoteValueReceived?.invoke(handle, it, socket) },
            notification = if (notifyViewModel) {
                { copied -> onNotificationReceived?.invoke(handle.value.toByte(), copied); Unit }
            } else null,
            onError = { Log.e(TAG, "ATT value callback threw", it) }
        )
    }

    fun disconnected() {
        val socket = readerSocket ?: BluetoothConnectionManager.attSocket
        stopReader()
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "error closing ATT socket", e)
        }
    }

    private fun runReaderLoop(socket: BluetoothSocket) {
        val input = socket.inputStream
        val buffer = ByteArray(512)
        while (readerRunning.get() && isCurrent(socket)) {
            val len = input.read(buffer)
            if (len < 0) break
            if (len == 0 || !isCurrent(socket)) continue
            val data = buffer.copyOfRange(0, len)
            if (data[0] == 0x1B.toByte()) {
                // L2CAP supplies a complete ATT PDU; require a known full 16-bit handle and value.
                if (data.size <= 3) continue
                val handleValue = (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
                val handle = ATTHandles.entries.firstOrNull { it.value == handleValue } ?: continue
                val value = data.copyOfRange(3, data.size)
                publishRemoteValue(handle, value, socket, notifyViewModel = true)
            } else {
                transactions.offer(socket, data)
            }
        }
    }
}
