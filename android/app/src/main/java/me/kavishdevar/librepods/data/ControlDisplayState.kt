package me.kavishdevar.librepods.data

/** Orders display snapshots across a service's successive control connections. */
data class ControlDisplayVersion(val connectionToken: Long, val revision: Long) {
    fun canReplace(previous: ControlDisplayVersion?): Boolean = previous == null ||
        connectionToken > previous.connectionToken ||
        (connectionToken == previous.connectionToken && revision >= previous.revision)

    /** Reject snapshots captured before this connection was closed. */
    fun closed(): ControlDisplayVersion = copy(revision = Long.MAX_VALUE)
}

/** Decide whether a disconnect event still belongs to the displayed connection. */
fun shouldCloseControlDisplay(
    displayedToken: Long?, targetToken: Long?, activeToken: Long?
): Boolean = displayedToken == targetToken && activeToken == null

/** A display snapshot, not evidence that a socket write was accepted by the device. */
class ControlDisplaySnapshot<K> internal constructor(
    val version: ControlDisplayVersion,
    values: Map<K, ByteArray>
) {
    private val snapshot = values.mapValues { it.value.copyOf() }
    val values: Map<K, ByteArray> get() = snapshot.mapValues { it.value.copyOf() }
}

/**
 * In-memory state for one control connection. The owner serializes all operations with the
 * socket and the device's desired-value policy. No value here is persisted or replayed.
 */
class ControlDisplayState<K>(private val connectionToken: Long) {
    private val reported = mutableMapOf<K, ByteArray>()
    private val pending = mutableMapOf<K, ByteArray>()
    private val runtimeFallback = mutableMapOf<K, ByteArray>()
    private var revision = 0L
    private var active = true

    fun recordReport(identifier: K, value: ByteArray) {
        if (!active) return
        reported[identifier] = value.copyOf()
        pending.remove(identifier)
        // Even an unchanged report must order after an earlier write/snapshot.
        revision++
    }

    fun recordWriteResult(identifier: K, value: ByteArray, success: Boolean) {
        if (!active || !success) return
        pending[identifier] = value.copyOf()
        revision++
    }

    /** A newly retained desired choice supersedes an older successful-write overlay. */
    fun desiredChanged(identifier: K) {
        if (!active) return
        pending.remove(identifier)
        revision++
    }

    /** A policy transition can remove desired values without producing a report. */
    fun policyChanged() {
        if (active) revision++
    }

    /** Manager state for non-policy controls only; it never establishes device authority. */
    fun recordRuntimeFallback(identifier: K, value: ByteArray) {
        if (!active || runtimeFallback[identifier]?.contentEquals(value) == true) return
        runtimeFallback[identifier] = value.copyOf()
        revision++
    }

    fun reportedValues(): Map<K, ByteArray> = reported.mapValues { it.value.copyOf() }

    fun reportedValue(identifier: K): ByteArray? = reported[identifier]?.copyOf()

    fun currentRevision(): Long = revision

    fun isCurrent(version: ControlDisplayVersion): Boolean = active &&
        version.connectionToken == connectionToken && version.revision == revision

    fun snapshot(desired: Map<K, ByteArray>): ControlDisplaySnapshot<K>? {
        if (!active) return null
        val values = desired.toMutableMap()
        values.putAll(runtimeFallback)
        values.putAll(reported)
        values.putAll(pending)
        return ControlDisplaySnapshot(ControlDisplayVersion(connectionToken, revision), values)
    }

    fun close() {
        active = false
        reported.clear()
        pending.clear()
        runtimeFallback.clear()
    }
}
