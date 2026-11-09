package me.kavishdevar.librepods.services

/** Allows one connection notice per disconnected-to-connected transition. */
internal class ConnectionIslandGate {
    private var connected = false
    private var pending = false
    private var hasEarDetectionState = false
    private var wasAnyEarWorn = false

    @Synchronized
    fun onConnected(): Boolean {
        if (connected) return false
        connected = true
        pending = true
        resetEarDetectionState()
        return true
    }

    @Synchronized
    fun onDisconnected() {
        connected = false
        pending = false
        resetEarDetectionState()
    }

    @Synchronized
    fun consumeIfReady(hasValidBattery: Boolean): Boolean {
        if (!connected || !pending || !hasValidBattery) return false
        pending = false
        return true
    }

    @Synchronized
    fun onConnectedIslandShown() {
        pending = false
    }

    @Synchronized
    fun shouldCloseIslandOnEarDetection(leftInEar: Boolean, rightInEar: Boolean): Boolean {
        if (!connected) return false

        val anyEarWorn = leftInEar || rightInEar
        val shouldClose = hasEarDetectionState && wasAnyEarWorn && !anyEarWorn
        hasEarDetectionState = true
        wasAnyEarWorn = anyEarWorn
        return shouldClose
    }

    private fun resetEarDetectionState() {
        hasEarDetectionState = false
        wasAnyEarWorn = false
    }

    val isPending: Boolean
        @Synchronized get() = connected && pending
}
