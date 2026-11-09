package me.kavishdevar.librepods.data

/** App product defaults, not a claim about any model's factory settings. */
object SavedSettingsDefaults {
    private val controls = linkedMapOf(
        0x01 to byteArrayOf(0), 0x0a to byteArrayOf(1), 0x0d to byteArrayOf(3),
        0x17 to byteArrayOf(0), 0x18 to byteArrayOf(0), 0x1a to byteArrayOf(0x0e),
        0x1b to byteArrayOf(2), 0x1f to byteArrayOf(75, 0x50), 0x20 to byteArrayOf(1),
        0x23 to byteArrayOf(1), 0x24 to byteArrayOf(0, 2), 0x25 to byteArrayOf(1),
        0x26 to byteArrayOf(2), 0x28 to byteArrayOf(2), 0x2c to byteArrayOf(1, 2),
        0x2e to byteArrayOf(50), 0x2f to byteArrayOf(2), 0x33 to byteArrayOf(2),
        0x34 to byteArrayOf(1), 0x35 to byteArrayOf(2), 0x37 to byteArrayOf(2),
        0x3b to byteArrayOf(2)
    )
    private val equalizer = byteArrayOf(1, 50, 50, 50)
    private val attValues = mapOf(0x1b to byteArrayOf(0))

    fun aacp(): Map<Int, ByteArray> = controls.mapValues { it.value.copyOf() }
    fun customEq(): ByteArray = equalizer.copyOf()
    fun att(): Map<Int, ByteArray> = attValues.mapValues { it.value.copyOf() }

    /**
     * Persist only absent or invalid app-owned values. Never derive them from remote reports.
     * A missing/blank current name is left unset, rather than inventing a replacement name.
     * Backend errors propagate; the caller must not use a partially seeded snapshot.
     */
    fun seedMissing(store: DeviceSettingsStore, address: String?, currentName: String?): Boolean {
        if (!store.migrateLegacyAdaptiveVolume(address)) return false
        for ((identifier, default) in controls) {
            val saved = store.getDesired(address, identifier)
            if (saved == null || !ControlSettingsPolicy.isValidUserValue(identifier, saved)) {
                if (!store.saveExplicitSelection(address, identifier, default)) return false
            }
        }
        if (store.getDesiredCustomEq(address) == null && !store.saveExplicitCustomEq(address, equalizer)) return false
        if (store.getDesiredAtt(address, 0x1b) == null && !store.saveExplicitAtt(address, 0x1b, attValues.getValue(0x1b))) return false
        if (store.getDesiredName(address) == null && !currentName.isNullOrBlank()) {
            if (!store.saveDesiredName(address, currentName)) return false
        }
        return true
    }
}
