package me.kavishdevar.librepods.data

import java.security.MessageDigest
import java.util.Locale

/** Implementations must complete each write before returning and must not swallow write failures. */
interface DeviceSettingsBackend {
    fun getString(key: String): String?
    fun getStringSet(key: String): Set<String>
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun putStringSet(key: String, value: Set<String>)
}

/**
 * Stores app-owned choices; reading an absent value never creates a default.
 * Payload semantics and the supported identifier whitelist belong to the caller's policy.
 * Addresses are hashed for namespace isolation, not encrypted. Payloads are plain hex.
 * Use one instance per backend to serialize read/modify/write operations.
 */
class DeviceSettingsStore(
    private val backend: DeviceSettingsBackend,
    private val isIdentifierAllowed: (Int) -> Boolean = { true },
) {
    @Synchronized
    fun getDesired(address: String?, identifier: Int): ByteArray? {
        requireIdentifier(identifier)
        val prefix = namespace(address) ?: return null
        return readPayload(prefix, identifier)
    }

    @Synchronized
    fun listDesired(address: String?): Map<Int, ByteArray> {
        val prefix = namespace(address) ?: return emptyMap()
        // A fixed 256-ID scan avoids a separately persisted index and its partial-write states.
        return buildMap {
            for (identifier in 0..255) {
                if (isIdentifierAllowed(identifier)) {
                    readPayload(prefix, identifier)?.let { put(identifier, it) }
                }
            }
        }
    }

    /** Legacy compatibility bridge. New callers should use [getDesired]. */
    @Synchronized
    fun getExplicitAdaptiveVolume(address: String?): Byte? {
        requireIdentifier(0x26)
        val prefix = namespace(address) ?: return null
        return readPayload(prefix, 0x26)?.singleOrNull()?.takeIf(AdaptiveVolumeRestorePolicy::isValidValue)
            ?: legacyAdaptiveVolume(prefix)
    }

    /** Legacy compatibility bridge; new writes use the generic app-owned key. */
    @Synchronized
    fun saveExplicitAdaptiveVolume(address: String?, value: Byte): Boolean {
        requireIdentifier(0x26)
        if (!AdaptiveVolumeRestorePolicy.isValidValue(value)) return false
        return saveExplicitSelection(address, 0x26, byteArrayOf(value))
    }

    /**
     * The legacy explicit 0x26 key was newer than the generic key in the previous service.
     * Copy it once before defaults or user writes. The completion marker is written only
     * after the generic value, so a failed migration can be retried safely before use.
     */
    @Synchronized
    fun migrateLegacyAdaptiveVolume(address: String?): Boolean {
        requireIdentifier(0x26)
        val prefix = namespace(address) ?: return false
        val marker = "$prefix.adaptive_volume.migrated"
        if (backend.getString(marker) == "1") return true
        legacyAdaptiveVolume(prefix)?.let { backend.putString(desiredKey(prefix, 0x26), encodeHex(byteArrayOf(it))) }
        backend.putString(marker, "1")
        return true
    }

    private fun legacyAdaptiveVolume(prefix: String): Byte? = when (backend.getString("$prefix.explicit.adaptive_volume")) {
        "01" -> 0x01.toByte()
        "02" -> 0x02.toByte()
        else -> null
    }

    /** Call only for an explicit user choice or a migration with established provenance. */
    @Synchronized
    fun saveExplicitSelection(address: String?, identifier: Int, payload: ByteArray): Boolean {
        requireIdentifier(identifier)
        require(payload.size in 1..4) { "Control payload must contain 1 to 4 bytes" }
        val prefix = namespace(address) ?: return false
        if (identifier == 0x26) migrateLegacyAdaptiveVolume(address)
        backend.putString(desiredKey(prefix, identifier), encodeHex(payload.copyOf()))
        return true
    }

    @Synchronized
    fun removeDesired(address: String?, identifier: Int): Boolean {
        requireIdentifier(identifier)
        val prefix = namespace(address) ?: return false
        backend.remove(desiredKey(prefix, identifier))
        return true
    }

    @Synchronized
    fun getReportedIdentifiers(address: String?): Set<Int> {
        val prefix = namespace(address) ?: return emptySet()
        return reported(prefix)
    }

    /** Compatibility metadata only; a report never changes the app-owned selection. */
    @Synchronized
    fun recordDeviceReport(address: String?, identifier: Int): Boolean {
        requireIdentifier(identifier)
        val prefix = namespace(address) ?: return false
        val currentReported = reported(prefix)
        if (identifier in currentReported) return true
        val identifiers = currentReported + identifier
        backend.putStringSet("$prefix.reported", identifiers.map { identifierHex(it) }.toSet())
        return true
    }

    /** Independent opcode-0x63 route; it is not an AACP control identifier. */
    @Synchronized
    fun getDesiredCustomEq(address: String?): ByteArray? {
        val prefix = namespace(address) ?: return null
        val value = backend.getString("$prefix.desired.custom_eq") ?: return null
        if (!value.matches(Regex("[0-9a-f]{8}"))) return null
        val payload = ByteArray(4) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return payload.takeIf { isValidCustomEq(it) }
    }

    /** Call only for an explicit user choice, never for defaults, reports, or restore sends. */
    @Synchronized
    fun saveExplicitCustomEq(address: String?, payload: ByteArray): Boolean {
        val snapshot = payload.copyOf()
        require(isValidCustomEq(snapshot)) { "Invalid custom EQ payload" }
        val prefix = namespace(address) ?: return false
        backend.putString("$prefix.desired.custom_eq", encodeHex(snapshot))
        return true
    }

    /** Compatibility metadata only; a report never changes the app-owned selection. */
    @Synchronized
    fun recordDeviceCustomEqReport(address: String?): Boolean {
        val prefix = namespace(address) ?: return false
        if (customEqReported(prefix)) return true
        backend.putString("$prefix.custom_eq.reported", "1")
        return true
    }

    @Synchronized
    fun isCustomEqReported(address: String?): Boolean {
        val prefix = namespace(address) ?: return false
        return customEqReported(prefix)
    }

    private fun customEqReported(prefix: String) =
        backend.getString("$prefix.custom_eq.reported") == "1"

    private fun isValidCustomEq(payload: ByteArray): Boolean =
        payload.size == 4 && payload[0].toInt() in 1..2 &&
            (1..3).all { (payload[it].toInt() and 0xff) in 0..100 }

    @Synchronized
    fun getDesiredAtt(address: String?, handle: Int): ByteArray? {
        requireAttHandle(handle)
        val prefix = namespace(address) ?: return null
        return readAttPayload(prefix, handle)
    }

    @Synchronized
    fun listDesiredAtt(address: String?): Map<Int, ByteArray> {
        val prefix = namespace(address) ?: return emptyMap()
        return buildMap {
            for (handle in ATT_HANDLES) {
                readAttPayload(prefix, handle)?.let { put(handle, it) }
            }
        }
    }

    /** Store an app-owned value; callers validate composite payload semantics. */
    @Synchronized
    fun saveExplicitAtt(address: String?, handle: Int, payload: ByteArray): Boolean {
        requireAttHandle(handle)
        val snapshot = payload.copyOf()
        require(isValidAttPayload(handle, snapshot)) { "Invalid ATT payload" }
        val prefix = namespace(address) ?: return false
        backend.putString(attDesiredKey(prefix, handle), encodeHex(snapshot))
        return true
    }

    /** Compatibility metadata only; a report never changes the app-owned selection. */
    @Synchronized
    fun recordDeviceAttReport(address: String?, handle: Int): Boolean {
        requireAttHandle(handle)
        val prefix = namespace(address) ?: return false
        val currentReported = reportedAtt(prefix)
        if (handle in currentReported) return true
        backend.putStringSet("$prefix.att.reported", (currentReported + handle).map { identifierHex(it) }.toSet())
        return true
    }

    /** Names are stored as strings per device and encoded as UTF-8 by the transport. */
    @Synchronized
    fun getDesiredName(address: String?): String? =
        namespace(address)?.let { backend.getString("$it.desired.name")?.takeIf(String::isNotBlank) }

    @Synchronized
    fun saveDesiredName(address: String?, name: String): Boolean {
        require(name.isNotBlank()) { "Name must not be blank" }
        val prefix = namespace(address) ?: return false
        backend.putString("$prefix.desired.name", name)
        return true
    }

    /** Compatibility metadata only; the reported name never changes the app-owned selection. */
    @Synchronized
    fun recordDeviceNameReport(address: String?): Boolean {
        val prefix = namespace(address) ?: return false
        if (nameReported(prefix)) return true
        backend.putString("$prefix.name.reported", "1")
        return true
    }

    @Synchronized
    fun isDeviceNameReported(address: String?): Boolean {
        val prefix = namespace(address) ?: return false
        return nameReported(prefix)
    }

    private fun nameReported(prefix: String) = backend.getString("$prefix.name.reported") == "1"

    /** Opaque app data for future composite ATT field codecs; this is never a raw ATT value. */
    @Synchronized
    fun getCompositeAttSettings(address: String?, handle: Int): String? {
        require(handle in COMPOSITE_ATT_HANDLES) { "Unsupported composite ATT handle" }
        val prefix = namespace(address) ?: return null
        return backend.getString("$prefix.desired.att_fields.${identifierHex(handle)}")
            ?.takeIf { it.isNotEmpty() && it.length <= 65536 }
    }

    @Synchronized
    fun saveCompositeAttSettings(address: String?, handle: Int, value: String): Boolean {
        require(handle in COMPOSITE_ATT_HANDLES) { "Unsupported composite ATT handle" }
        require(value.isNotEmpty() && value.length <= 65536) { "Invalid composite ATT data length" }
        val prefix = namespace(address) ?: return false
        backend.putString("$prefix.desired.att_fields.${identifierHex(handle)}", value)
        return true
    }

    @Synchronized
    fun getReportedAttHandles(address: String?): Set<Int> {
        val prefix = namespace(address) ?: return emptySet()
        return reportedAtt(prefix)
    }

    private fun requireAttHandle(handle: Int) {
        require(handle in ATT_HANDLES) { "Unsupported ATT handle" }
    }

    private fun reportedAtt(prefix: String): Set<Int> =
        backend.getStringSet("$prefix.att.reported").mapNotNull { value ->
            if (!value.matches(Regex("[0-9a-f]{2}"))) return@mapNotNull null
            value.toInt(16).takeIf { it in ATT_HANDLES }
        }.toSet()

    private fun readAttPayload(prefix: String, handle: Int): ByteArray? {
        val value = backend.getString(attDesiredKey(prefix, handle)) ?: return null
        if (value.length !in 2..1024 || value.length % 2 != 0 ||
            !value.matches(Regex("[0-9a-f]+"))) return null
        val payload = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return payload.takeIf { isValidAttPayload(handle, it) }
    }

    // Float layout/finite checks belong to the codec; preserve every accepted byte here.
    private fun isValidAttPayload(handle: Int, payload: ByteArray): Boolean = when (handle) {
        0x1b -> payload.size == 1 && payload[0].toInt() in 0..1
        0x18 -> payload.size == 100 || payload.size in 104..512
        0x2a -> payload.size in 104..512
        else -> false
    }

    private fun attDesiredKey(prefix: String, handle: Int) =
        "$prefix.desired.att.${identifierHex(handle)}"

    private companion object {
        val ATT_HANDLES = setOf(0x18, 0x1b, 0x2a)
        val COMPOSITE_ATT_HANDLES = setOf(0x18, 0x2a)
    }

    private fun requireIdentifier(identifier: Int) {
        require(identifier in 0..255 && isIdentifierAllowed(identifier)) {
            "Unsupported control identifier"
        }
    }

    private fun reported(prefix: String): Set<Int> =
        backend.getStringSet("$prefix.reported").mapNotNull { value ->
            if (!value.matches(Regex("[0-9a-f]{2}"))) return@mapNotNull null
            value.toInt(16).takeIf { isIdentifierAllowed(it) }
        }.toSet()

    private fun readPayload(prefix: String, identifier: Int): ByteArray? {
        val value = backend.getString(desiredKey(prefix, identifier)) ?: return null
        if (value.length !in 2..8 || value.length % 2 != 0 ||
            !value.matches(Regex("[0-9a-f]+"))) return null
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun namespace(address: String?): String? {
        // Accept the canonical Bluetooth form only; never let caller text become a key suffix.
        if (address == null || !address.matches(Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) {
            return null
        }
        val normalized = address.uppercase(Locale.ROOT)
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "device_settings.v1.${encodeHex(hash)}"
    }

    private fun desiredKey(prefix: String, identifier: Int) =
        "$prefix.desired.aacp.${identifierHex(identifier)}"

    private fun identifierHex(identifier: Int) = identifier.toString(16).padStart(2, '0')

    // java.util.HexFormat is unavailable on the project's lowest supported Android API (33).
    private fun encodeHex(value: ByteArray): String = buildString(value.size * 2) {
        value.forEach { byte -> append((byte.toInt() and 0xff).toString(16).padStart(2, '0')) }
    }
}
