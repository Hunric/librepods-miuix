package me.kavishdevar.librepods.services

/** Allows one startup recovery before any normal AACP response consumes the timeout path. */
internal class StartupRecoveryPolicy(
    private val recoveryAllowed: Boolean,
    private val timeoutRecoveryAllowed: Boolean = recoveryAllowed
) {
    private var validAacpResponseSeen = false
    private var recoveryConsumed = false
    private var timeoutRecoveryConsumed = false

    @Synchronized
    fun onTimeout(): Boolean {
        if (!timeoutRecoveryAllowed || recoveryConsumed || timeoutRecoveryConsumed) return false
        timeoutRecoveryConsumed = true
        return true
    }

    @Synchronized
    fun onPacket(handshakeStatus: Int?, isValidAacpResponse: Boolean): Boolean {
        if (isValidAacpResponse) validAacpResponseSeen = true
        if (!recoveryAllowed || recoveryConsumed || validAacpResponseSeen || handshakeStatus != 0x85) {
            return false
        }
        recoveryConsumed = true
        return true
    }
}
