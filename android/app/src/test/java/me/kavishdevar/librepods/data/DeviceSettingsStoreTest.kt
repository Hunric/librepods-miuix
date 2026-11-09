package me.kavishdevar.librepods.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceSettingsStoreTest {
    private val first = "AA:BB:CC:DD:EE:01"
    private val second = "AA:BB:CC:DD:EE:02"

    @Test
    fun explicitAdaptiveVolumeSurvivesReportsAndStoreReconstruction() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.recordDeviceReport(first, 0x26))
        assertNull(store.getExplicitAdaptiveVolume(first))
        assertTrue(store.saveExplicitAdaptiveVolume(first, 1))
        assertTrue(store.recordDeviceReport(first, 0x26))
        val fresh = DeviceSettingsStore(backend)
        assertEquals(1.toByte(), fresh.getExplicitAdaptiveVolume(first.lowercase()))
        assertEquals(setOf(0x26), fresh.getReportedIdentifiers(first))
        assertArrayEquals(byteArrayOf(1), fresh.getDesired(first, 0x26))
        assertTrue(backend.strings.keys.any { it.endsWith(".desired.aacp.26") })
    }

    @Test
    fun explicitAdaptiveVolumeIsPerDeviceAndUpdatesGenericDesired() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.saveExplicitSelection(first, 0x26, byteArrayOf(2)))
        assertEquals(2.toByte(), store.getExplicitAdaptiveVolume(first))
        assertTrue(store.saveExplicitAdaptiveVolume(first, 1))
        assertNull(store.getExplicitAdaptiveVolume(second))
        assertTrue(store.saveExplicitAdaptiveVolume(second, 2))
        assertEquals(1.toByte(), store.getExplicitAdaptiveVolume(first))
        assertEquals(2.toByte(), store.getExplicitAdaptiveVolume(second))
        assertTrue(store.recordDeviceReport(first, 0x26))
        assertArrayEquals(byteArrayOf(1), store.getDesired(first, 0x26))
        assertEquals(1.toByte(), DeviceSettingsStore(backend).getExplicitAdaptiveVolume(first))
        assertEquals(2.toByte(), DeviceSettingsStore(backend).getExplicitAdaptiveVolume(second))
    }

    @Test
    fun invalidAdaptiveSelectionsAndDeviceReportsDoNotCreateIntent() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertFalse(store.saveExplicitAdaptiveVolume(first, 0))
        assertFalse(store.saveExplicitAdaptiveVolume(first, 3))
        assertFalse(store.saveExplicitAdaptiveVolume(null, 1))
        assertFalse(store.saveExplicitAdaptiveVolume("invalid", 1))
        assertTrue(backend.operations.isEmpty())
        assertTrue(store.recordDeviceReport(first, 0x26))
        assertNull(store.getExplicitAdaptiveVolume(first))
        assertTrue(backend.strings.isEmpty())
    }

    @Test
    fun malformedAndFailedAdaptiveSelectionWritesAreNotUsed() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        backend.failString = true
        assertThrows(IllegalStateException::class.java) { store.saveExplicitAdaptiveVolume(first, 1) }
        assertNull(store.getExplicitAdaptiveVolume(first))
        backend.failString = false
        assertTrue(store.saveExplicitAdaptiveVolume(first, 1))
        val key = backend.strings.keys.single { it.endsWith(".desired.aacp.26") }
        for (value in listOf("", "1", "00", "03", "0100", "ff")) {
            backend.strings[key] = value
            assertNull(store.getExplicitAdaptiveVolume(first))
        }
    }

    @Test
    fun absentReadsDoNotCreateDefaults() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertNull(store.getDesired(first, 0x1a))
        assertTrue(store.listDesired(first).isEmpty())
        assertTrue(store.getReportedIdentifiers(first).isEmpty())
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun choicesAreIsolatedAndAddressCaseIsNormalized() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.saveExplicitSelection(first, 0x1a, byteArrayOf(7)))
        assertTrue(store.saveExplicitSelection(second, 0x1a, byteArrayOf(15)))
        assertArrayEquals(byteArrayOf(7), store.getDesired(first.lowercase(), 0x1a))
        assertArrayEquals(byteArrayOf(15), store.listDesired(second)[0x1a])
        assertTrue(backend.strings.keys.all {
            it.matches(Regex("device_settings\\.v1\\.[0-9a-f]{64}\\.desired\\.aacp\\.1a"))
        })
        store.removeDesired(first, 0x1a)
        assertNull(store.getDesired(first, 0x1a))
        assertArrayEquals(byteArrayOf(15), store.getDesired(second, 0x1a))
    }

    @Test
    fun returnedBytesAndSetsCannotMutateStoredChoices() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        val payload = byteArrayOf(1, 2, 0xff.toByte(), 0)
        store.saveExplicitSelection(first, 0x24, payload)
        payload[0] = 9
        store.getDesired(first, 0x24)!![1] = 9
        store.listDesired(first)[0x24]!![2] = 9
        assertArrayEquals(byteArrayOf(1, 2, 0xff.toByte(), 0), store.getDesired(first, 0x24))
        assertEquals("0102ff00", backend.strings.values.single())
    }

    @Test
    fun invalidAddressesCannotWriteOrInjectKeys() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        for (address in listOf(null, "", "$first.reported", "../$first", " AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:GG")) {
            assertFalse(store.saveExplicitSelection(address, 0x1a, byteArrayOf(7)))
            assertFalse(store.recordDeviceReport(address, 0x1a))
            assertFalse(store.removeDesired(address, 0x1a))
            assertNull(store.getDesired(address, 0x1a))
            assertTrue(store.listDesired(address).isEmpty())
        }
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun identifierAndPayloadValidationRejectInvalidWrites() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend) { it == 0x1a }
        for (id in listOf(-1, 256, 0x0d)) {
            assertThrows(IllegalArgumentException::class.java) { store.saveExplicitSelection(first, id, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { store.recordDeviceReport(first, id) }
        }
        for (payload in listOf(byteArrayOf(), ByteArray(5))) {
            assertThrows(IllegalArgumentException::class.java) { store.saveExplicitSelection(first, 0x1a, payload) }
        }
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun malformedPersistedValuesAreIgnoredWithoutRepairWrites() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend) { it == 0x1a }
        store.saveExplicitSelection(first, 0x1a, byteArrayOf(7))
        val key = backend.strings.keys.single()
        for (value in listOf("", "0", "gg", "0102030405", "../reported", "FF")) {
            backend.strings[key] = value
            assertNull(store.getDesired(first, 0x1a))
            assertTrue(store.listDesired(first).isEmpty())
        }
        backend.sets[key.substringBefore(".desired") + ".reported"] = setOf("1a", "100", "-1", "../", "0d")
        assertEquals(setOf(0x1a), store.getReportedIdentifiers(first))
        assertEquals(1, backend.operations.size)
    }

    @Test
    fun reportPersistsOnlyMetadataAndNeverSuppressesOrDeletesDesired() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitSelection(first, 0x1a, byteArrayOf(7))
        backend.operations.clear()
        assertTrue(store.recordDeviceReport(first, 0x1a))
        assertEquals(listOf("set"), backend.operations)
        assertEquals(setOf("1a"), backend.sets.values.single())
        assertEquals(setOf(0x1a), store.getReportedIdentifiers(first))
        assertArrayEquals(byteArrayOf(7), store.getDesired(first, 0x1a))
        assertTrue(store.saveExplicitSelection(first, 0x1a, byteArrayOf(15)))
        assertArrayEquals(byteArrayOf(15), DeviceSettingsStore(backend).getDesired(first, 0x1a))
        assertTrue(store.getReportedIdentifiers(second).isEmpty())
    }

    @Test
    fun markerFailureDoesNotChangeChoiceAndExistingMarkersDoNotPreventSaving() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitSelection(first, 0x1a, byteArrayOf(7))
        backend.operations.clear()
        backend.failSet = true
        assertThrows(IllegalStateException::class.java) { store.recordDeviceReport(first, 0x1a) }
        assertEquals(listOf("set"), backend.operations)
        assertArrayEquals(byteArrayOf(7), store.getDesired(first, 0x1a))
        backend.failSet = false
        assertTrue(store.recordDeviceReport(first, 0x1a))
        val fresh = DeviceSettingsStore(backend)
        assertArrayEquals(byteArrayOf(7), fresh.getDesired(first, 0x1a))
        assertArrayEquals(byteArrayOf(7), fresh.listDesired(first)[0x1a])
        assertTrue(fresh.saveExplicitSelection(first, 0x1a, byteArrayOf(15)))
    }

    @Test
    fun repeatedReportWithoutDesiredDoesNotWriteAgain() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.recordDeviceReport(first, 0x0d))
        backend.operations.clear()
        assertTrue(store.recordDeviceReport(first, 0x0d))
        assertTrue(store.recordDeviceReport(first, 0x0d))
        assertTrue(backend.operations.isEmpty())
        assertEquals(setOf(0x0d), store.getReportedIdentifiers(first))
    }

    @Test
    fun failedChoiceWritePropagatesWithoutCreatingAValue() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        backend.failString = true
        assertThrows(IllegalStateException::class.java) {
            store.saveExplicitSelection(first, 0x1a, byteArrayOf(7))
        }
        assertNull(store.getDesired(first, 0x1a))
        assertTrue(store.listDesired(first).isEmpty())
        assertTrue(store.getReportedIdentifiers(first).isEmpty())
    }

    @Test
    fun customEqAbsentReadsDoNotPersistDefaults() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertNull(store.getDesiredCustomEq(first))
        assertFalse(store.isCustomEqReported(first))
        assertTrue(backend.strings.isEmpty())
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun customEqExplicitChoicesAreCopiedAndIsolatedFromDevicesAndControlIds() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        val choice = byteArrayOf(2, 0, 50, 100)
        assertTrue(store.saveExplicitCustomEq(first, choice))
        choice[1] = 99
        store.getDesiredCustomEq(first)!![2] = 99
        assertTrue(store.saveExplicitCustomEq(second, byteArrayOf(1, 100, 0, 50)))
        assertTrue(store.saveExplicitSelection(first, 0x63, byteArrayOf(7)))
        assertArrayEquals(byteArrayOf(2, 0, 50, 100), store.getDesiredCustomEq(first.lowercase()))
        assertArrayEquals(byteArrayOf(1, 100, 0, 50), store.getDesiredCustomEq(second))
        assertArrayEquals(byteArrayOf(7), store.getDesired(first, 0x63))
        assertEquals(setOf(0x63), store.listDesired(first).keys)
        store.recordDeviceReport(first, 0x63)
        assertFalse(store.isCustomEqReported(first))
        assertArrayEquals(byteArrayOf(2, 0, 50, 100), store.getDesiredCustomEq(first))
    }

    @Test
    fun customEqRejectsInvalidPayloadAndMissingOrMaliciousAddress() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        for (payload in listOf(byteArrayOf(), byteArrayOf(1, 50, 50), byteArrayOf(1, 0, 0, 0, 0),
            byteArrayOf(0, 50, 50, 50), byteArrayOf(3, 50, 50, 50),
            byteArrayOf(1, 101, 50, 50), byteArrayOf(1, 50, (-1).toByte(), 50),
            byteArrayOf(1, 50, 50, 101))) {
            assertThrows(IllegalArgumentException::class.java) { store.saveExplicitCustomEq(first, payload) }
        }
        for (address in listOf(null, "", "../$first", "$first.custom_eq.reported")) {
            assertFalse(store.saveExplicitCustomEq(address, byteArrayOf(1, 50, 50, 50)))
            assertFalse(store.recordDeviceCustomEqReport(address))
            assertFalse(store.isCustomEqReported(address))
            assertNull(store.getDesiredCustomEq(address))
        }
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun customEqMalformedStoredPayloadsAreIgnoredWithoutRepairWrites() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitCustomEq(first, byteArrayOf(1, 50, 50, 50))
        val key = backend.strings.keys.single()
        for (value in listOf("", "013232", "0132323200", "013232gg", "00323232", "03323232",
            "01653232", "0132ff32", "01323265", "../reported", "01643A32")) {
            backend.strings[key] = value
            assertNull(store.getDesiredCustomEq(first))
        }
        backend.strings[key] = "01323232"
        backend.strings[key.substringBefore(".desired") + ".custom_eq.reported"] = "malformed"
        assertFalse(store.isCustomEqReported(first))
        assertArrayEquals(byteArrayOf(1, 50, 50, 50), store.getDesiredCustomEq(first))
        assertEquals(1, backend.operations.size)
    }

    @Test
    fun customEqReportPreservesDesiredAndAvoidsRepeatedMetadataWrites() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitCustomEq(first, byteArrayOf(2, 0, 50, 100))
        store.saveExplicitCustomEq(second, byteArrayOf(1, 50, 50, 50))
        store.saveExplicitSelection(first, 0x63, byteArrayOf(7))
        backend.operations.clear()
        assertTrue(store.recordDeviceCustomEqReport(first))
        assertEquals(listOf("string"), backend.operations)
        assertTrue(store.isCustomEqReported(first))
        assertFalse(store.isCustomEqReported(second))
        assertArrayEquals(byteArrayOf(2, 0, 50, 100), store.getDesiredCustomEq(first))
        assertTrue(store.saveExplicitCustomEq(first, byteArrayOf(1, 50, 50, 50)))
        assertArrayEquals(byteArrayOf(1, 50, 50, 50), store.getDesiredCustomEq(second))
        assertArrayEquals(byteArrayOf(7), store.getDesired(first, 0x63))
        assertEquals("1", backend.strings.filterKeys { it.endsWith(".custom_eq.reported") }.values.single())
        assertEquals(2, backend.strings.keys.count { it.endsWith(".desired.custom_eq") })
        backend.operations.clear()
        assertTrue(store.recordDeviceCustomEqReport(first))
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun customEqMetadataFailureLeavesDesiredIntact() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        backend.failString = true
        assertThrows(IllegalStateException::class.java) {
            store.saveExplicitCustomEq(first, byteArrayOf(1, 50, 50, 50))
        }
        assertNull(store.getDesiredCustomEq(first))
        backend.failString = false
        store.saveExplicitCustomEq(first, byteArrayOf(2, 0, 50, 100))
        backend.operations.clear()
        backend.failString = true
        assertThrows(IllegalStateException::class.java) { store.recordDeviceCustomEqReport(first) }
        assertEquals(listOf("string"), backend.operations)
        assertArrayEquals(byteArrayOf(2, 0, 50, 100), store.getDesiredCustomEq(first))
        backend.failString = false
        assertTrue(store.recordDeviceCustomEqReport(first))
        val fresh = DeviceSettingsStore(backend)
        assertTrue(fresh.isCustomEqReported(first))
        assertArrayEquals(byteArrayOf(2, 0, 50, 100), fresh.getDesiredCustomEq(first))
        assertTrue(fresh.saveExplicitCustomEq(first, byteArrayOf(1, 50, 50, 50)))
        backend.operations.clear()
        assertTrue(fresh.recordDeviceCustomEqReport(first))
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun attAbsentReadsCreateNoDefaultsAndInvalidAddressesCannotWrite() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        for (address in listOf(first, null, "", "../$first", "$first.att.reported")) {
            assertNull(store.getDesiredAtt(address, 0x1b))
            assertTrue(store.listDesiredAtt(address).isEmpty())
            assertTrue(store.getReportedAttHandles(address).isEmpty())
            if (address != first) {
                assertFalse(store.saveExplicitAtt(address, 0x1b, byteArrayOf(1)))
                assertFalse(store.recordDeviceAttReport(address, 0x1b))
            }
        }
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun attPreservesBytesAndIsolatesDevicesAndTransports() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        val original = ByteArray(512) { it.toByte() }
        val expected = original.copyOf()
        store.saveExplicitAtt(first, 0x18, original)
        original[0] = 99
        store.getDesiredAtt(first, 0x18)!![1] = 99
        store.listDesiredAtt(first)[0x18]!![2] = 99
        store.saveExplicitAtt(first, 0x1b, byteArrayOf(1))
        store.saveExplicitAtt(second, 0x18, ByteArray(100) { 7 })
        store.saveExplicitSelection(first, 0x18, byteArrayOf(2))
        store.saveExplicitCustomEq(first, byteArrayOf(2, 10, 20, 30))
        assertArrayEquals(expected, store.getDesiredAtt(first.lowercase(), 0x18))
        assertArrayEquals(ByteArray(100) { 7 }, store.getDesiredAtt(second, 0x18))
        assertEquals(setOf(0x18, 0x1b), store.listDesiredAtt(first).keys)
        assertArrayEquals(byteArrayOf(2), store.getDesired(first, 0x18))
        store.recordDeviceAttReport(first, 0x18)
        assertArrayEquals(byteArrayOf(2), store.getDesired(first, 0x18))
        assertArrayEquals(byteArrayOf(2, 10, 20, 30), store.getDesiredCustomEq(first))
        assertArrayEquals(byteArrayOf(1), store.getDesiredAtt(first, 0x1b))
        assertTrue(store.getReportedAttHandles(second).isEmpty())
    }

    @Test
    fun attValidatesExactHandleAndLengthBoundaries() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        for (handle in listOf(-1, 0, 0x19, 0x63, 256)) {
            assertThrows(IllegalArgumentException::class.java) { store.getDesiredAtt(first, handle) }
            assertThrows(IllegalArgumentException::class.java) { store.saveExplicitAtt(first, handle, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { store.recordDeviceAttReport(first, handle) }
        }
        for ((handle, invalidSizes) in listOf(0x18 to listOf(0, 99, 101, 102, 103, 513),
            0x2a to listOf(0, 100, 103, 513), 0x1b to listOf(0, 2))) {
            for (size in invalidSizes) {
                assertThrows(IllegalArgumentException::class.java) { store.saveExplicitAtt(first, handle, ByteArray(size)) }
            }
        }
        for (value in listOf(2.toByte(), (-1).toByte())) {
            assertThrows(IllegalArgumentException::class.java) { store.saveExplicitAtt(first, 0x1b, byteArrayOf(value)) }
        }
        assertTrue(backend.operations.isEmpty())
        for (size in listOf(100, 104, 105, 512)) assertTrue(store.saveExplicitAtt(first, 0x18, ByteArray(size)))
        for (size in listOf(104, 105, 512)) assertTrue(store.saveExplicitAtt(first, 0x2a, ByteArray(size)))
        for (value in listOf(0.toByte(), 1.toByte())) assertTrue(store.saveExplicitAtt(first, 0x1b, byteArrayOf(value)))
    }

    @Test
    fun attMalformedStoredValuesAndUnsupportedMetadataAreIgnored() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitAtt(first, 0x1b, byteArrayOf(1))
        val key = backend.strings.keys.single()
        for (value in listOf("", "0", "gg", "0000", "02", "ff", "../reported", "00".repeat(513))) {
            backend.strings[key] = value
            assertNull(store.getDesiredAtt(first, 0x1b))
            assertTrue(store.listDesiredAtt(first).isEmpty())
        }
        backend.sets[key.substringBefore(".desired") + ".att.reported"] = setOf("18", "2a", "19", "100", "../")
        assertEquals(setOf(0x18, 0x2a), store.getReportedAttHandles(first))
        assertEquals(1, backend.operations.size)
    }

    @Test
    fun attReportPreservesDesiredAndRepeatedReportDoesNotWrite() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitAtt(first, 0x1b, byteArrayOf(1))
        backend.operations.clear()
        assertTrue(store.recordDeviceAttReport(first, 0x1b))
        assertEquals(listOf("set"), backend.operations)
        assertEquals(setOf("1b"), backend.sets.values.single())
        assertArrayEquals(byteArrayOf(1), store.getDesiredAtt(first, 0x1b))
        assertArrayEquals(byteArrayOf(1), store.listDesiredAtt(first)[0x1b])
        assertTrue(store.saveExplicitAtt(first, 0x1b, byteArrayOf(0)))
        backend.operations.clear()
        assertTrue(store.recordDeviceAttReport(first, 0x1b))
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun attMetadataFailureLeavesDesiredAvailable() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        backend.failString = true
        assertThrows(IllegalStateException::class.java) { store.saveExplicitAtt(first, 0x1b, byteArrayOf(1)) }
        assertNull(store.getDesiredAtt(first, 0x1b))
        backend.failString = false
        store.saveExplicitAtt(first, 0x1b, byteArrayOf(1))
        backend.operations.clear()
        backend.failSet = true
        assertThrows(IllegalStateException::class.java) { store.recordDeviceAttReport(first, 0x1b) }
        assertEquals(listOf("set"), backend.operations)
        assertArrayEquals(byteArrayOf(1), store.getDesiredAtt(first, 0x1b))
        backend.failSet = false
        assertTrue(store.recordDeviceAttReport(first, 0x1b))
        val fresh = DeviceSettingsStore(backend)
        assertEquals(setOf(0x1b), fresh.getReportedAttHandles(first))
        assertArrayEquals(byteArrayOf(1), fresh.getDesiredAtt(first, 0x1b))
        assertArrayEquals(byteArrayOf(1), fresh.listDesiredAtt(first)[0x1b])
        assertTrue(fresh.saveExplicitAtt(first, 0x1b, byteArrayOf(0)))
        backend.operations.clear()
        assertTrue(fresh.recordDeviceAttReport(first, 0x1b))
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun seedPreservesExistingChoicesMigratesLegacyAdaptiveAndUsesCurrentNameOnce() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitSelection(first, 0x1a, byteArrayOf(7))
        val prefix = backend.strings.keys.single().substringBefore(".desired")
        // Reconstruct an old installation: the explicit 0x26 value supersedes an old generic value.
        backend.strings["$prefix.desired.aacp.26"] = "02"
        backend.strings["$prefix.explicit.adaptive_volume"] = "01"
        backend.sets["$prefix.reported"] = setOf("1a", "26")
        assertTrue(SavedSettingsDefaults.seedMissing(store, first, "耳机 🎧"))
        assertArrayEquals(byteArrayOf(7), store.getDesired(first, 0x1a))
        assertArrayEquals(byteArrayOf(1), store.getDesired(first, 0x26))
        assertEquals("耳机 🎧", store.getDesiredName(first))
        assertEquals(SavedSettingsDefaults.aacp().keys, store.listDesired(first).keys)
        assertArrayEquals(byteArrayOf(1, 50, 50, 50), store.getDesiredCustomEq(first))
        assertArrayEquals(byteArrayOf(0), store.getDesiredAtt(first, 0x1b))
        assertNull(store.getDesiredAtt(first, 0x18))
        assertNull(store.getDesiredAtt(first, 0x2a))
        assertTrue(store.saveExplicitSelection(first, 0x26, byteArrayOf(2)))
        assertTrue(SavedSettingsDefaults.seedMissing(DeviceSettingsStore(backend), first, "different"))
        assertArrayEquals(byteArrayOf(2), store.getDesired(first, 0x26))
        assertEquals("耳机 🎧", store.getDesiredName(first))
        assertNull(store.getDesiredName(second))
    }

    @Test
    fun adaptiveMigrationMarkerFailureIsRetryableBeforeAnyLaterChoice() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        store.saveExplicitSelection(first, 0x1a, byteArrayOf(7))
        val prefix = backend.strings.keys.single().substringBefore(".desired")
        backend.strings["$prefix.explicit.adaptive_volume"] = "01"
        backend.failStringSuffix = ".adaptive_volume.migrated"
        assertThrows(IllegalStateException::class.java) { store.migrateLegacyAdaptiveVolume(first) }
        assertEquals("01", backend.strings["$prefix.desired.aacp.26"])
        assertNull(backend.strings["$prefix.adaptive_volume.migrated"])
        backend.failStringSuffix = null
        assertTrue(store.migrateLegacyAdaptiveVolume(first))
        assertTrue(store.saveExplicitSelection(first, 0x26, byteArrayOf(2)))
        assertTrue(DeviceSettingsStore(backend).migrateLegacyAdaptiveVolume(first))
        assertArrayEquals(byteArrayOf(2), store.getDesired(first, 0x26))
    }

    @Test
    fun productDefaultsAreValidCopiedAndNeverReadDeviceReports() {
        val defaults = SavedSettingsDefaults.aacp()
        assertEquals(ControlSettingsPolicy.SUPPORTED_IDENTIFIERS, defaults.keys)
        defaults.forEach { (id, value) -> assertTrue("invalid default $id", ControlSettingsPolicy.isValidUserValue(id, value)) }
        defaults.getValue(0x1a)[0] = 0
        assertArrayEquals(byteArrayOf(0x0e), SavedSettingsDefaults.aacp()[0x1a])
        SavedSettingsDefaults.customEq()[0] = 2
        assertArrayEquals(byteArrayOf(1, 50, 50, 50), SavedSettingsDefaults.customEq())
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertFalse(SavedSettingsDefaults.seedMissing(store, "invalid", null))
        assertTrue(backend.operations.isEmpty())
        assertTrue(SavedSettingsDefaults.seedMissing(store, first, null))
        assertNull(store.getDesiredName(first))
        assertEquals(setOf(0x1b), store.listDesiredAtt(first).keys)
    }

    @Test
    fun namesAndOpaqueCompositeSettingsAreIsolatedAndFailuresPropagate() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.saveDesiredName(first, "听力设备 🎧"))
        assertTrue(store.saveDesiredName(second, "other"))
        assertEquals("听力设备 🎧", store.getDesiredName(first.lowercase()))
        assertEquals("other", store.getDesiredName(second))
        assertThrows(IllegalArgumentException::class.java) { store.saveDesiredName(first, "   ") }
        assertFalse(store.saveDesiredName("invalid", "valid"))
        assertTrue(store.saveCompositeAttSettings(first, 0x18, "v1:example"))
        assertEquals("v1:example", store.getCompositeAttSettings(first, 0x18))
        assertNull(store.getCompositeAttSettings(second, 0x18))
        assertNull(store.getDesiredAtt(first, 0x18))
        assertThrows(IllegalArgumentException::class.java) { store.saveCompositeAttSettings(first, 0x1b, "v1:example") }
        assertThrows(IllegalArgumentException::class.java) { store.saveCompositeAttSettings(first, 0x18, "") }
        assertThrows(IllegalArgumentException::class.java) { store.saveCompositeAttSettings(first, 0x18, "a".repeat(65537)) }
        backend.failString = true
        assertThrows(IllegalStateException::class.java) { store.saveDesiredName(first, "new") }
        assertThrows(IllegalStateException::class.java) { store.saveCompositeAttSettings(first, 0x2a, "v1:example") }
        assertEquals("听力设备 🎧", store.getDesiredName(first))
    }

    @Test
    fun nameReportMetadataIsPerDevicePersistentAndDoesNotChangeDesiredNames() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.saveDesiredName(first, "saved name"))
        assertTrue(store.saveDesiredName(second, "other name"))
        val desiredBefore = backend.strings.toMap()
        backend.operations.clear()

        assertFalse(store.isDeviceNameReported(first))
        assertTrue(store.recordDeviceNameReport(first))
        assertEquals(listOf("string"), backend.operations)
        val metadata = backend.strings.filterKeys { it.endsWith(".name.reported") }
        assertEquals(1, metadata.size)
        assertEquals("1", metadata.values.single())
        assertTrue(metadata.keys.single().matches(Regex("device_settings\\.v1\\.[0-9a-f]{64}\\.name\\.reported")))
        for ((key, value) in desiredBefore) assertEquals(value, backend.strings[key])

        val fresh = DeviceSettingsStore(backend)
        assertTrue(fresh.isDeviceNameReported(first.lowercase()))
        assertFalse(fresh.isDeviceNameReported(second))
        assertEquals("saved name", fresh.getDesiredName(first))
        assertEquals("other name", fresh.getDesiredName(second))
        backend.operations.clear()
        assertTrue(fresh.recordDeviceNameReport(first.lowercase()))
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun nameReportMetadataFailurePropagatesAndCanBeRetried() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        assertTrue(store.saveDesiredName(first, "saved name"))
        backend.operations.clear()
        backend.failStringSuffix = ".name.reported"
        assertThrows(IllegalStateException::class.java) { store.recordDeviceNameReport(first) }
        assertEquals(listOf("string"), backend.operations)
        assertFalse(store.isDeviceNameReported(first))
        assertEquals("saved name", store.getDesiredName(first))

        backend.failStringSuffix = null
        backend.operations.clear()
        assertTrue(store.recordDeviceNameReport(first))
        assertEquals(listOf("string"), backend.operations)
        val fresh = DeviceSettingsStore(backend)
        assertTrue(fresh.isDeviceNameReported(first))
        assertEquals("saved name", fresh.getDesiredName(first))
        backend.operations.clear()
        assertTrue(fresh.recordDeviceNameReport(first))
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun nameReportMetadataRejectsInvalidAddressesAndDoesNotCreateDesiredName() {
        val backend = FakeBackend()
        val store = DeviceSettingsStore(backend)
        for (address in listOf(null, "", "invalid", "../$first", "$first.name.reported")) {
            assertFalse(store.recordDeviceNameReport(address))
            assertFalse(store.isDeviceNameReported(address))
        }
        assertTrue(backend.operations.isEmpty())
        assertTrue(store.recordDeviceNameReport(second))
        assertTrue(store.isDeviceNameReported(second))
        assertNull(store.getDesiredName(second))
        assertNull(store.getDesiredName(first))
    }

    private class FakeBackend : DeviceSettingsBackend {
        val strings = mutableMapOf<String, String>()
        val sets = mutableMapOf<String, Set<String>>()
        val operations = mutableListOf<String>()
        var failSet = false
        var failRemove = false
        var failString = false
        var failStringSuffix: String? = null
        override fun getString(key: String) = strings[key]
        override fun getStringSet(key: String) = sets[key].orEmpty()
        override fun putString(key: String, value: String) {
            operations += "string"
            check(!failString && failStringSuffix?.let { key.endsWith(it) } != true) { "Simulated choice write failure" }
            strings[key] = value
        }
        override fun remove(key: String) {
            operations += "remove"
            check(!failRemove) { "Simulated deletion failure" }
            strings.remove(key)
        }
        override fun putStringSet(key: String, value: Set<String>) {
            operations += "set"
            check(!failSet) { "Simulated marker failure" }
            sets[key] = value.toSet()
        }
    }
}
