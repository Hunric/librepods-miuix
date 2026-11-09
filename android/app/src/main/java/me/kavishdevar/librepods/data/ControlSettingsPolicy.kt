package me.kavishdevar.librepods.data

/**
 * Persistence/replay policy only; no Android, disk, timers or transport access.
 * Own one instance per physical device. [knownReported] must describe that device/model,
 * never the absence of a packet. The owner serializes storage operations and actual packet
 * writes with incoming reports, checking [isCurrent] immediately before each write. This
 * class cannot cancel IO that the owner has already started outside its synchronization.
 * Readiness is necessary, not permission to skip the owner's startup/report collection.
 */
class ControlSettingsPolicy(knownReported: Set<Int> = emptySet()) {
    class StoredValue internal constructor(val identifier: Int, val revision: Long, value: ByteArray) {
        private val bytes = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
    }

    class SendRequest internal constructor(
        val session: Long, val identifier: Int, val revision: Long, value: ByteArray
    ) {
        private val bytes = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
    }

    class ReportUpdate internal constructor(val identifier: Int, value: ByteArray) {
        private val bytes = value.copyOf()
        val value: ByteArray get() = bytes.copyOf()
        /** Delete this identifier from persistent desired state, even if it was not loaded. */
        val removeStoredValue: Boolean = true
    }

    private val reported = knownReported.intersect(SUPPORTED_IDENTIFIERS).toMutableSet()
    private val desired = linkedMapOf<Int, StoredValue>()
    private val sent = mutableMapOf<Int, Long>()
    private val inFlight = mutableMapOf<Int, SendRequest>()
    private var generation = 0L
    private var active = false
    private var identity: String? = null
    private var handshake = false
    private var features = false
    private var ready = false
    private var revision = 0L

    /** A different physical device requires a different policy and its own stored values. */
    @Synchronized
    fun beginSession(deviceIdentity: String): Long {
        require(deviceIdentity.isNotBlank()) { "Device identity is required" }
        require(identity == null || identity == deviceIdentity) { "Policy belongs to another device" }
        identity = deviceIdentity
        generation++
        active = true
        handshake = false
        features = false
        ready = false
        sent.clear()
        inFlight.clear()
        return generation
    }

    @Synchronized
    fun terminate(session: Long) {
        if (!current(session)) return
        active = false
        ready = false
        inFlight.clear()
        sent.clear()
    }

    @Synchronized
    fun acknowledgeHandshake(session: Long): Boolean {
        if (!current(session)) return false
        handshake = true
        return true
    }

    @Synchronized
    fun acknowledgeFeatures(session: Long): Boolean {
        if (!current(session)) return false
        features = true
        return true
    }

    /** Called explicitly by the owner after startup synchronization, never by a timeout here. */
    @Synchronized
    fun markReady(session: Long): Boolean {
        if (!current(session) || !handshake || !features || identity == null) return false
        ready = true
        return true
    }

    @Synchronized
    fun isDeviceReported(identifier: Int): Boolean = identifier in reported

    /** Evidence-backed capabilities for this session's verified model; no synthetic payload. */
    @Synchronized
    fun declareKnownReported(session: Long, identifiers: Set<Int>): Set<Int> {
        if (!current(session)) return emptySet()
        val accepted = identifiers.intersect(SUPPORTED_IDENTIFIERS)
        accepted.forEach { identifier ->
            reported.add(identifier)
            desired.remove(identifier)
            sent.remove(identifier)
            inFlight.remove(identifier)
        }
        return accepted.toSet()
    }

    /** Only actual decoded inbound packets may call this, never optimistic send callbacks. */
    @Synchronized
    fun acceptReported(session: Long, identifier: Int, value: ByteArray): ReportUpdate? {
        if (!current(session) || identifier !in SUPPORTED_IDENTIFIERS || value.size !in 1..4) return null
        // Inbound values may contain firmware bits not exposed by the UI. Still trust the device.
        reported.add(identifier)
        desired.remove(identifier)
        sent.remove(identifier)
        inFlight.remove(identifier)
        return ReportUpdate(identifier, value)
    }

    /** Load only explicitly stored values. Does not overwrite a newer intent already in memory. */
    @Synchronized
    fun loadDesired(identifier: Int, value: ByteArray): StoredValue? {
        if (identifier in desired) return null
        return rememberIntent(identifier, value)
    }

    /**
     * Returns the value the owner must persist. Null means no persistence/replay is allowed;
     * it does not prevent an explicit user command to a device-reported setting.
     */
    @Synchronized
    fun setUserIntent(identifier: Int, value: ByteArray): StoredValue? = rememberIntent(identifier, value)

    private fun rememberIntent(identifier: Int, value: ByteArray): StoredValue? {
        val snapshot = value.copyOf()
        if (identifier in reported || !isValidUserValue(identifier, snapshot)) return null
        val old = desired[identifier]
        if (old != null && old.value.contentEquals(snapshot)) return copy(old)
        val stored = StoredValue(identifier, ++revision, snapshot)
        desired[identifier] = stored
        inFlight.remove(identifier)
        return copy(stored)
    }

    @Synchronized
    fun desiredValues(): List<StoredValue> = desired.values.map(::copy)

    /** Reserves each pending revision once. An unsuccessful result releases it for retry. */
    @Synchronized
    fun planSends(session: Long): List<SendRequest> {
        if (!current(session) || !ready) return emptyList()
        return desired.values.filter {
            it.identifier !in reported && sent[it.identifier] != it.revision && it.identifier !in inFlight
        }.map {
            SendRequest(session, it.identifier, it.revision, it.value).also { request ->
                inFlight[it.identifier] = request
            }
        }
    }

    @Synchronized
    fun isCurrent(request: SendRequest): Boolean =
        current(request.session) && ready && request.identifier !in reported &&
            desired[request.identifier]?.revision == request.revision && inFlight[request.identifier] === request

    /** True means the current revision was written successfully, not acknowledged by the device. */
    @Synchronized
    fun completeSend(request: SendRequest, success: Boolean): Boolean {
        if (!isCurrent(request)) return false
        inFlight.remove(request.identifier)
        if (success) sent[request.identifier] = request.revision
        return success
    }

    private fun current(session: Long): Boolean = active && generation == session
    private fun copy(value: StoredValue) = StoredValue(value.identifier, value.revision, value.value)

    companion object {
        /** UI settings only. Ownership, stem interception and unknown private opcodes are excluded. */
        val SUPPORTED_IDENTIFIERS: Set<Int> get() = setOf(
            0x01, 0x0A, 0x0D, 0x17, 0x18, 0x1A, 0x1B, 0x1F, 0x20, 0x23, 0x24,
            0x25, 0x26, 0x28, 0x2C, 0x2E, 0x2F, 0x33, 0x34, 0x35, 0x37, 0x3B
        )

        /** Accept UI payloads and their zero-padded wire form without normalizing saved bytes. */
        fun isValidUserValue(identifier: Int, value: ByteArray): Boolean {
            if (identifier !in SUPPORTED_IDENTIFIERS || value.size !in 1..4) return false
            fun byte(index: Int) = value[index].toInt() and 0xFF
            fun scalar(range: IntRange) = byte(0) in range && value.drop(1).all { it == 0.toByte() }
            fun pair(first: IntRange, second: IntRange) = value.size >= 2 &&
                byte(0) in first && byte(1) in second && value.drop(2).all { it == 0.toByte() }
            return when (identifier) {
                0x01, 0x17, 0x18 -> scalar(0..2)
                0x0D -> scalar(1..4)
                0x1A -> scalar(0..15) && Integer.bitCount(byte(0)) >= 2
                0x1F -> pair(0..100, 0x50..0x50)
                0x23 -> scalar(1..3)
                0x24 -> pair(0..0, 2..3)
                0x2C -> pair(1..1, 1..2)
                0x2E -> scalar(0..100)
                else -> scalar(1..2)
            }
        }
    }
}
