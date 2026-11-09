package me.kavishdevar.librepods.data

/** One connection's restore decision for an independently persisted user choice. */
class AdaptiveVolumeRestorePolicy(private val connectionToken: Long, initialIntent: Byte?) {
    class Request internal constructor(val token: Long, val revision: Long, val value: Byte)

    private var intent = initialIntent?.takeIf(::isValidValue)
    private var revision = if (intent == null) 0L else 1L
    private var active = true
    private var considered = false
    private var successfulUserRevision: Long? = null
    private var failedUserRevision: Long? = null
    private var inFlight: Request? = null

    /** Called after the explicit selection has been durably saved. */
    fun setUserIntent(value: Byte): Long {
        check(active) { "Connection is closed" }
        require(isValidValue(value)) { "Adaptive volume must be 1 or 2" }
        intent = value
        revision++
        inFlight = null
        return revision
    }

    /** A failed explicit write is retained for the next connection, not retried in this one. */
    fun recordUserWrite(intentRevision: Long, success: Boolean) {
        if (!active || intentRevision != revision) return
        if (success) successfulUserRevision = intentRevision else failedUserRevision = intentRevision
    }

    /** Decide once after startup synchronization; [actual] must come from a real report. */
    fun planRestore(ready: Boolean, actual: Byte?): Request? {
        if (!active || !ready || considered) return null
        considered = true
        val selected = intent ?: return null
        if (successfulUserRevision == revision || failedUserRevision == revision || actual == selected) return null
        return Request(connectionToken, revision, selected).also { inFlight = it }
    }

    fun isCurrent(request: Request): Boolean = active && request === inFlight &&
        request.token == connectionToken && request.revision == revision

    /** Mark the single attempt complete regardless of socket success. */
    fun complete(request: Request): Boolean {
        if (!isCurrent(request)) return false
        inFlight = null
        return true
    }

    fun close() {
        active = false
        inFlight = null
    }

    companion object {
        fun isValidValue(value: Byte): Boolean = value.toInt() in 1..2
    }
}
