package me.kavishdevar.librepods.data

/** Selects only among devices supplied by the current A2DP connected-device query. */
internal object StartupDiscoveryPolicy {
    data class Candidate<T>(
        val device: T, val preferred: Boolean, val hasUuid: Boolean,
        val hasReports: Boolean, val hasSuccessfulConnection: Boolean,
    )
    enum class Outcome { SAVED, UUID, NONE, AMBIGUOUS }
    data class Selection<T>(val candidate: Candidate<T>?, val outcome: Outcome)

    fun <T> select(candidates: List<Candidate<T>>): Selection<T> {
        val preferred = candidates.singleOrNull {
            it.preferred && (it.hasUuid || it.hasReports || it.hasSuccessfulConnection)
        }
        if (preferred != null) return Selection(preferred, Outcome.SAVED)
        val uuidMatches = candidates.filter { it.hasUuid }
        return when (uuidMatches.size) {
            0 -> Selection(null, Outcome.NONE)
            1 -> Selection(uuidMatches.single(), Outcome.UUID)
            else -> Selection(null, Outcome.AMBIGUOUS)
        }
    }
}

/** Serializes discovery generations; callers perform all external work outside this lock. */
internal class StartupDiscoveryGate {
    enum class Admission { STARTED, COALESCED, DESTROYED }
    private enum class Phase { IDLE, WAITING, PROCESSING }
    data class Request(val generation: Long, val admission: Admission)
    data class Completion(val accepted: Boolean, val nextGeneration: Long?)

    private var generation = 0L
    private var phase = Phase.IDLE
    private var pending = false
    private var destroyed = false

    @Synchronized
    fun request(): Request {
        if (destroyed) return Request(generation, Admission.DESTROYED)
        if (phase != Phase.IDLE) {
            pending = true
            return Request(generation, Admission.COALESCED)
        }
        generation++
        phase = Phase.WAITING
        return Request(generation, Admission.STARTED)
    }

    @Synchronized
    fun isWaiting(token: Long): Boolean = !destroyed && token == generation && phase == Phase.WAITING

    @Synchronized
    fun acceptCallback(token: Long): Boolean {
        if (!isWaiting(token)) return false
        phase = Phase.PROCESSING
        return true
    }

    @Synchronized
    fun mayConnect(token: Long, candidateConnected: Boolean): Boolean =
        !destroyed && token == generation && phase == Phase.PROCESSING && candidateConnected

    @Synchronized
    fun finish(token: Long, waitingOnly: Boolean = false): Completion {
        if (destroyed || token != generation || phase == Phase.IDLE ||
            (waitingOnly && phase != Phase.WAITING)) return Completion(false, null)
        if (pending) {
            pending = false
            generation++
            phase = Phase.WAITING
            return Completion(true, generation)
        }
        phase = Phase.IDLE
        return Completion(true, null)
    }

    @Synchronized
    fun destroy() {
        destroyed = true
        pending = false
        phase = Phase.IDLE
    }
}

/** Arbitrates ownership of one candidate socket without performing IO. */
internal class StartupConnectAttempt {
    private enum class State { CONNECTING, CLAIMED, CLOSED }
    private val state = java.util.concurrent.atomic.AtomicReference(State.CONNECTING)

    fun claimConnected(): Boolean = state.compareAndSet(State.CONNECTING, State.CLAIMED)
    fun cancelPending(): Boolean = state.compareAndSet(State.CONNECTING, State.CLOSED)
    fun abandonClaimed(): Boolean = state.compareAndSet(State.CLAIMED, State.CLOSED)
}
