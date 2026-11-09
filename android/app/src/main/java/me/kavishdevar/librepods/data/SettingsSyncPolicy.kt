package me.kavishdevar.librepods.data

/**
 * Per-connection reconciliation. No IO, clocks, timers or Android dependencies.
 * The owner serializes all calls and actual sends/receive delivery, and calls [startWrite]
 * immediately before writing bytes. A session ref is unique even if owner/token are reused.
 */
class SettingsSyncPolicy(
    private val matcher: (String, ByteArray, ByteArray) -> Boolean = { _, desired, actual -> desired.contentEquals(actual) },
    private val confirmationTimeoutMs: Long = 1500L,
) {
    init { require(confirmationTimeoutMs > 0) }

    /** SENT_UNVERIFIED reflects send results only; it is not an observed device value. */
    enum class Phase { PREPARING, PENDING, RESERVED, SENDING, AWAITING_CONFIRMATION, BACKOFF, WAITING_FOR_WEAR, CONFIRMED, SENT_UNVERIFIED, FAILED }
    enum class Failure { TRANSPORT_FAILURE, DEVICE_REJECTED, MISMATCH, UNCONFIRMED }
    /**
     * SENT includes transport ACKs. UNCONFIRMED means a write was attempted but no result arrived;
     * NOT_SENT_UNCONFIRMED means no setting write was attempted.
     */
    enum class WriteResult { SENT, TRANSPORT_FAILURE, DEVICE_REJECTED, UNCONFIRMED, NOT_SENT_UNCONFIRMED }

    class SessionRef internal constructor(val owner: String, val token: Long, val generation: Long)

    class WriteRequest internal constructor(
        val session: SessionRef, val key: String, val revision: Long, val attempt: Int,
        val sequence: Long, value: ByteArray,
    ) {
        private val bytes = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
    }

    class ItemSnapshot internal constructor(
        val phase: Phase, val revision: Long, val attempts: Int,
        val failure: Failure?, val nextAtMs: Long?, desired: ByteArray, actual: ByteArray?,
    ) {
        private val desiredBytes = desired.copyOf()
        private val actualBytes = actual?.copyOf()
        val desired: ByteArray get() = desiredBytes.copyOf()
        val actual: ByteArray? get() = actualBytes?.copyOf()
    }

    private class Item(value: ByteArray, val createdAt: Long) {
        var desired = value.copyOf()
        var actual: ByteArray? = null
        var desiredSequence = createdAt
        var actualSequence = 0L
        var revision = 1L
        var attempts = 0
        var successfulSends = 0
        var lastNonSentFailure: Failure? = null
        var phase = Phase.PREPARING
        var failure: Failure? = null
        var nextAtMs: Long? = null
        var resumePhase: Phase? = null
        var resumeAtMs: Long? = null
        var request: WriteRequest? = null
        var startSequence: Long? = null
        var successfulStartSequence: Long? = null
    }

    private var generation = 0L
    private var sequence = 0L
    private var active: SessionRef? = null
    private var prepared = false
    private val items = linkedMapOf<String, Item>()
    private val remotelyObservedKeys = mutableSetOf<String>()
    private val blockedWriteKeys = mutableSetOf<String>()

    fun begin(owner: String, token: Long, desired: Map<String, ByteArray>): SessionRef {
        require(owner.isNotBlank()) { "Device identity is required" }
        require(desired.keys.none(String::isBlank)) { "Setting keys must not be blank" }
        val session = SessionRef(owner, token, ++generation)
        active = session
        prepared = false
        items.clear()
        remotelyObservedKeys.clear()
        blockedWriteKeys.clear()
        desired.forEach { (key, value) -> items[key] = Item(value, ++sequence) }
        return session
    }

    fun ready(session: SessionRef, nowMs: Long): Boolean {
        if (!current(session)) return false
        require(nowMs >= 0)
        if (prepared) return true
        prepared = true
        items.forEach { (key, item) ->
            if (matchesCurrentObservation(key, item)) confirm(item)
            else schedulePending(key, item, nowMs)
        }
        return true
    }

    /** An explicit user edit/retry starts a new bounded round, including for the same value. */
    fun updateDesired(session: SessionRef, key: String, value: ByteArray, nowMs: Long): Long? {
        if (!current(session)) return null
        require(key.isNotBlank() && nowMs >= 0)
        val existing = items[key]
        if (existing == null) {
            val item = Item(value, ++sequence)
            if (prepared) schedulePending(key, item, nowMs)
            items[key] = item
            return item.revision
        }
        existing.desired = value.copyOf()
        existing.desiredSequence = ++sequence
        existing.revision++
        existing.attempts = 0
        existing.successfulSends = 0
        existing.lastNonSentFailure = null
        existing.failure = null
        existing.request = null
        existing.startSequence = null
        existing.successfulStartSequence = null
        existing.resumePhase = null
        existing.resumeAtMs = null
        if (prepared) schedulePending(key, existing, nowMs)
        else { existing.phase = Phase.PREPARING; existing.nextAtMs = null }
        return existing.revision
    }

    /** Pause new writes without changing the desired revision, attempts, or retry deadline. */
    fun setWriteAllowed(session: SessionRef, key: String, allowed: Boolean, nowMs: Long): Boolean {
        if (!current(session) || key.isBlank()) return false
        require(nowMs >= 0)
        if (allowed) {
            blockedWriteKeys.remove(key)
            val item = items[key]
            if (item != null && item.phase == Phase.WAITING_FOR_WEAR) {
                item.phase = checkNotNull(item.resumePhase)
                item.nextAtMs = checkNotNull(item.resumeAtMs)
                item.resumePhase = null
                item.resumeAtMs = null
            }
        } else {
            blockedWriteKeys.add(key)
            val item = items[key] ?: return true
            when (item.phase) {
                Phase.PENDING, Phase.BACKOFF -> pauseForWear(item, item.phase, checkNotNull(item.nextAtMs))
                Phase.RESERVED -> pauseReservedForWear(item)
                else -> Unit // Do not cancel in-flight writes or reopen terminal states.
            }
        }
        return true
    }

    /** Record a real report seen for this device, even before the key is tracked, without reusing its old value. */
    fun noteRemoteObservation(session: SessionRef, key: String): Boolean {
        if (!current(session) || key.isBlank()) return false
        remotelyObservedKeys.add(key)
        val item = items[key]
        if (item?.phase == Phase.SENT_UNVERIFIED) {
            item.phase = Phase.FAILED
            item.failure = Failure.UNCONFIRMED
            item.nextAtMs = null
        }
        return true
    }

    fun hasRemoteObservation(session: SessionRef, key: String): Boolean =
        current(session) && key in remotelyObservedKeys

    /**
     * Forget a stale observation without restarting the round or cancelling an in-flight write.
     * A confirmed item becomes unconfirmed until a fresh matching report satisfies the usual rules.
     */
    fun forgetActual(session: SessionRef, key: String): Boolean {
        if (!current(session)) return false
        val item = items[key] ?: return false
        item.actual = null
        item.actualSequence = 0L
        if (item.phase == Phase.CONFIRMED) {
            item.phase = Phase.FAILED
            item.failure = Failure.UNCONFIRMED
            item.nextAtMs = null
        }
        return true
    }

    /** Only decoded remote reads/reports may be passed here, never optimistic send callbacks.
     * For an untracked key only report capability is retained, not the old value.
     */
    fun observeActual(session: SessionRef, key: String, value: ByteArray, nowMs: Long): Boolean {
        if (!current(session) || key.isBlank()) return false
        require(nowMs >= 0)
        remotelyObservedKeys.add(key)
        val item = items[key] ?: return true
        item.actual = value.copyOf()
        item.actualSequence = ++sequence
        if (!prepared) return true
        val matches = matchesCurrentObservation(key, item)
        if (item.phase == Phase.SENT_UNVERIFIED) {
            if (matches) confirm(item)
            else {
                item.phase = Phase.FAILED
                item.failure = Failure.MISMATCH
                item.nextAtMs = null
            }
            return true
        }
        if (item.phase == Phase.CONFIRMED) {
            if (!matches) {
                item.phase = Phase.FAILED
                item.failure = Failure.MISMATCH
                item.nextAtMs = null
            }
            return true
        }
        if (item.phase == Phase.FAILED) {
            val afterSuccessfulWrite = item.successfulStartSequence?.let { item.actualSequence > it } == true
            if (matches && (item.attempts == 0 || afterSuccessfulWrite)) confirm(item)
            else if (!matches && afterSuccessfulWrite) item.failure = Failure.MISMATCH
            return true
        }
        val preWrite = item.attempts == 0 &&
            (item.phase == Phase.PENDING || item.phase == Phase.RESERVED || item.phase == Phase.WAITING_FOR_WEAR)
        val afterSuccessfulWrite = item.successfulStartSequence?.let { item.actualSequence > it } == true
        if (matches && (preWrite || afterSuccessfulWrite)) confirm(item)
        // A report received while a write is in progress is kept until completeWrite(SENT).
        return true
    }

    /** Reserve every due key without waiting for any other key's confirmation. */
    fun time(session: SessionRef, nowMs: Long): List<WriteRequest> {
        if (!current(session)) return emptyList()
        require(nowMs >= 0)
        if (!prepared) return emptyList()
        val requests = mutableListOf<WriteRequest>()
        items.forEach { (key, item) ->
            if (item.phase == Phase.AWAITING_CONFIRMATION && nowMs >= (item.nextAtMs ?: Long.MAX_VALUE)) {
                val observedAfterWrite = item.startSequence?.let { item.actualSequence > it } == true
                val failure = if (observedAfterWrite && !matchesCurrentObservation(key, item)) Failure.MISMATCH else Failure.UNCONFIRMED
                finishAttempt(key, item, failure, nowMs)
            }
            if ((item.phase == Phase.PENDING || item.phase == Phase.BACKOFF) && key in blockedWriteKeys) {
                pauseForWear(item, item.phase, checkNotNull(item.nextAtMs))
            }
            if ((item.phase == Phase.PENDING || item.phase == Phase.BACKOFF) && nowMs >= (item.nextAtMs ?: Long.MAX_VALUE)) {
                val request = WriteRequest(session, key, item.revision, item.attempts + 1, ++sequence, item.desired)
                item.request = request
                item.phase = Phase.RESERVED
                item.nextAtMs = null
                requests += request
            }
        }
        return requests
    }

    /** False cancels a stale reservation without consuming a send attempt. */
    fun startWrite(session: SessionRef, request: WriteRequest): Boolean {
        val item = requestItem(session, request) ?: return false
        if (item.phase != Phase.RESERVED) return false
        if (request.key in blockedWriteKeys) {
            pauseReservedForWear(item)
            return false
        }
        item.attempts++
        item.startSequence = ++sequence
        item.phase = Phase.SENDING
        return true
    }

    fun completeWrite(session: SessionRef, request: WriteRequest, result: WriteResult, nowMs: Long): Boolean {
        val item = requestItem(session, request) ?: return false
        if (item.phase != Phase.SENDING) return false
        require(nowMs >= 0)
        item.lastNonSentFailure = when (result) {
            WriteResult.SENT -> item.lastNonSentFailure
            WriteResult.TRANSPORT_FAILURE -> Failure.TRANSPORT_FAILURE
            WriteResult.DEVICE_REJECTED -> Failure.DEVICE_REJECTED
            WriteResult.UNCONFIRMED, WriteResult.NOT_SENT_UNCONFIRMED -> Failure.UNCONFIRMED
        }
        when (result) {
            WriteResult.SENT -> {
                item.successfulSends++
                item.successfulStartSequence = item.startSequence
                if (item.actualSequence > (item.startSequence ?: Long.MAX_VALUE) && matchesCurrentObservation(request.key, item)) {
                    confirm(item)
                } else if (request.key !in remotelyObservedKeys) {
                    if (item.attempts == MAX_ATTEMPTS && item.successfulSends == MAX_ATTEMPTS) {
                        item.phase = Phase.SENT_UNVERIFIED
                        item.failure = null
                        item.nextAtMs = null
                        item.request = null
                    } else {
                        finishAttempt(request.key, item, item.lastNonSentFailure ?: Failure.UNCONFIRMED, nowMs)
                    }
                } else {
                    item.request = null
                    item.phase = Phase.AWAITING_CONFIRMATION
                    item.nextAtMs = plusDelay(nowMs, confirmationTimeoutMs)
                }
            }
            WriteResult.TRANSPORT_FAILURE -> finishAttempt(request.key, item, Failure.TRANSPORT_FAILURE, nowMs)
            WriteResult.DEVICE_REJECTED -> finishAttempt(request.key, item, Failure.DEVICE_REJECTED, nowMs)
            WriteResult.UNCONFIRMED -> {
                item.successfulStartSequence = item.startSequence
                if (item.actualSequence > (item.startSequence ?: Long.MAX_VALUE) && matchesCurrentObservation(request.key, item)) {
                    confirm(item)
                } else finishAttempt(request.key, item, Failure.UNCONFIRMED, nowMs)
            }
            WriteResult.NOT_SENT_UNCONFIRMED -> finishAttempt(request.key, item, Failure.UNCONFIRMED, nowMs)
        }
        return true
    }

    fun snapshot(session: SessionRef): Map<String, ItemSnapshot> {
        if (!current(session)) return emptyMap()
        return items.mapValues { (_, item) ->
            ItemSnapshot(item.phase, item.revision, item.attempts, item.failure, item.nextAtMs, item.desired, item.actual)
        }
    }

    fun end(session: SessionRef): Boolean {
        if (!current(session)) return false
        active = null
        prepared = false
        items.clear()
        remotelyObservedKeys.clear()
        blockedWriteKeys.clear()
        return true
    }

    private fun matchesCurrentObservation(key: String, item: Item): Boolean =
        item.actualSequence > item.desiredSequence && item.actual?.let {
            matcher(key, item.desired.copyOf(), it.copyOf())
        } == true

    private fun current(session: SessionRef): Boolean = active === session

    private fun requestItem(session: SessionRef, request: WriteRequest): Item? {
        if (!current(session) || request.session !== session) return null
        val item = items[request.key] ?: return null
        return item.takeIf { it.request === request && it.revision == request.revision }
    }

    private fun confirm(item: Item) {
        item.phase = Phase.CONFIRMED
        item.failure = null
        item.nextAtMs = null
        item.request = null
        item.resumePhase = null
        item.resumeAtMs = null
    }

    private fun schedulePending(key: String, item: Item, nowMs: Long) {
        if (key in blockedWriteKeys) pauseForWear(item, Phase.PENDING, nowMs)
        else { item.phase = Phase.PENDING; item.nextAtMs = nowMs }
    }

    private fun pauseForWear(item: Item, resumePhase: Phase, resumeAtMs: Long) {
        item.resumePhase = resumePhase
        item.resumeAtMs = resumeAtMs
        item.phase = Phase.WAITING_FOR_WEAR
        item.nextAtMs = null
        item.request = null
    }

    private fun pauseReservedForWear(item: Item) {
        // Reservations are created only after their deadline, so a replacement is immediately due.
        pauseForWear(item, if (item.attempts == 0) Phase.PENDING else Phase.BACKOFF, 0L)
    }

    private fun finishAttempt(key: String, item: Item, failure: Failure, nowMs: Long) {
        item.failure = failure
        item.request = null
        if (item.attempts >= MAX_ATTEMPTS) {
            // Three accepted sends with no report is the strongest evidence this device
            // gives for keys it never echoes; a real mismatch or transport failure still fails.
            if (failure == Failure.UNCONFIRMED && item.successfulSends == MAX_ATTEMPTS) {
                item.phase = Phase.SENT_UNVERIFIED
                item.failure = null
                item.nextAtMs = null
            } else {
                item.phase = Phase.FAILED
                item.nextAtMs = null
            }
        } else {
            val nextAtMs = plusDelay(nowMs, RETRY_DELAYS_MS[item.attempts - 1])
            if (key in blockedWriteKeys) pauseForWear(item, Phase.BACKOFF, nextAtMs)
            else { item.phase = Phase.BACKOFF; item.nextAtMs = nextAtMs }
        }
    }

    private fun plusDelay(nowMs: Long, delayMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs

    private companion object {
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(1000, 2000)
    }
}
