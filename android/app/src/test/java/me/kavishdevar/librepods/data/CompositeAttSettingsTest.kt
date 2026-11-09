package me.kavishdevar.librepods.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import me.kavishdevar.librepods.bluetooth.ATTHandles
import org.junit.Assert.*
import org.junit.Test

class CompositeAttSettingsTest {
    private fun baseline(handle: ATTHandles, size: Int): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(if (handle == ATTHandles.HEARING_AID) 25 else minOf(size / 4, 26)) {
                val offset = if (handle == ATTHandles.HEARING_AID) 4 + 4 * it else 4 * it
                putFloat(offset, (it + 1).toFloat())
            }
            if (handle == ATTHandles.HEARING_AID) {
                put(0, 2); put(1, 2); put(2, 0x60); put(3, 0)
            }
            for (i in 104 until size) put(i, (i * 3).toByte())
        }.array()

    @Test
    fun defaultsAndHearingOwnershipSurviveVersionedStorage() {
        for (handle in listOf(ATTHandles.HEARING_AID, ATTHandles.TRANSPARENCY)) {
            val defaults = requireNotNull(defaultCompositeAttSettings(handle))
            assertEquals(0f, defaults.amplification, 0f)
            assertEquals(0f, defaults.balance, 0f)
            assertEquals(0f, defaults.tone, 0f)
            assertEquals(0.5f, defaults.ambientNoiseReduction, 0f)
            assertFalse(defaults.conversationBoost)
            assertFalse(defaults.enabled)
            assertEquals(List(8) { 50f }, defaults.transparencyEq)
            assertEquals(List<Float?>(8) { null }, defaults.leftHearingLoss)
            assertEquals(defaults, parseCompositeAttSettings(handle, requireNotNull(serializeCompositeAttSettings(defaults))))
        }
        assertNull(defaultCompositeAttSettings(ATTHandles.LOUD_SOUND_REDUCTION))
        val desired = CompositeAttSettings(ATTHandles.HEARING_AID,
            leftHearingLoss = List(8) { if (it == 2) 27.5f else null },
            rightHearingLoss = List(8) { if (it == 7) -5f else null })
        val encoded = requireNotNull(serializeCompositeAttSettings(desired))
        assertEquals(desired, parseCompositeAttSettings(ATTHandles.HEARING_AID, encoded))
        assertNull(parseCompositeAttSettings(ATTHandles.TRANSPARENCY, encoded))
        assertNull(parseCompositeAttSettings(ATTHandles.HEARING_AID, "v2:" + encoded.substringAfter(':')))
        assertNull(parseCompositeAttSettings(ATTHandles.HEARING_AID, "v1:not base64"))
    }

    @Test
    fun defaultsPreserveClinicalFieldsHeaderAndUnknownTail() {
        val raw = baseline(ATTHandles.HEARING_AID, 116)
        val saved = raw.copyOf()
        val desired = CompositeAttSettings(ATTHandles.HEARING_AID)
        val wire = (patchFromDesired(desired, raw) as CompositeAttPatchResult.Ready).bytes
        assertArrayEquals(saved, raw)
        assertArrayEquals(saved.copyOfRange(0, 2), wire.copyOfRange(0, 2))
        assertEquals(0x64.toByte(), wire[2])
        assertEquals(saved[3], wire[3])
        assertArrayEquals(saved.copyOfRange(4, 36), wire.copyOfRange(4, 36))
        assertArrayEquals(saved.copyOfRange(52, 84), wire.copyOfRange(52, 84))
        assertArrayEquals(saved.copyOfRange(104, 116), wire.copyOfRange(104, 116))
        assertArrayEquals(saved.copyOfRange(100, 104), wire.copyOfRange(100, 104))
        assertTrue(matchesOwned(desired, wire))
        wire[2] = 0x60 // read header need not equal the write convention
        ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).putFloat(100, 0.33f)
        assertTrue(matchesOwned(desired, wire))
    }

    @Test
    fun aSingleHearingEditNeverOwnsTheOtherFrequenciesOrEar() {
        val raw = baseline(ATTHandles.HEARING_AID, 108)
        val desired = CompositeAttSettings(ATTHandles.HEARING_AID,
            leftHearingLoss = List(8) { if (it == 3) 42.5f else null })
        val wire = (patchFromDesired(desired, raw) as CompositeAttPatchResult.Ready).bytes
        val b = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42.5f, b.getFloat(16), 0f)
        for (i in 0 until 8) {
            if (i != 3) assertArrayEquals(raw.copyOfRange(4 + 4 * i, 8 + 4 * i), wire.copyOfRange(4 + 4 * i, 8 + 4 * i))
        }
        assertArrayEquals(raw.copyOfRange(52, 84), wire.copyOfRange(52, 84))
        val differentUnowned = wire.copyOf()
        ByteBuffer.wrap(differentUnowned).order(ByteOrder.LITTLE_ENDIAN).putFloat(52, 999f)
        assertTrue(matchesOwned(desired, differentUnowned))
        ByteBuffer.wrap(differentUnowned).order(ByteOrder.LITTLE_ENDIAN).putFloat(16, 43f)
        assertFalse(matchesOwned(desired, differentUnowned))
    }

    @Test
    fun transparencyRetainsOptionalShapeAndExtendedBytes() {
        for (size in listOf(100, 104, 109, 116)) {
            val raw = baseline(ATTHandles.TRANSPARENCY, size)
            val desired = CompositeAttSettings(ATTHandles.TRANSPARENCY)
            val wire = (patchFromDesired(desired, raw) as CompositeAttPatchResult.Ready).bytes
            assertEquals(size, wire.size)
            if (size > 104) assertArrayEquals(raw.copyOfRange(104, size), wire.copyOfRange(104, size))
            val buffer = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0f, buffer.getFloat(0), 0f)
            for (i in 0 until 8) {
                assertEquals(50f, buffer.getFloat(4 + i * 4), 0f)
                assertEquals(50f, buffer.getFloat(52 + i * 4), 0f)
            }
            if (size >= 104) assertArrayEquals(raw.copyOfRange(100, 104), wire.copyOfRange(100, 104))
            assertTrue(matchesOwned(desired, wire))
        }
    }

    @Test
    fun rejectsBadLayoutsNonFiniteDataAndUnrepresentableGainPairs() {
        val desired = CompositeAttSettings(ATTHandles.TRANSPARENCY)
        assertEquals(CompositeAttPatchResult.BaselineUnavailable, patchFromDesired(desired, null))
        for (size in listOf(0, 99, 101, 102, 103, 513)) {
            assertEquals(CompositeAttPatchResult.BaselineUnavailable, patchFromDesired(desired, ByteArray(size)))
        }
        val raw = baseline(ATTHandles.TRANSPARENCY, 104)
        val notFinite = raw.copyOf()
        ByteBuffer.wrap(notFinite).order(ByteOrder.LITTLE_ENDIAN).putFloat(44, Float.NaN)
        assertEquals(CompositeAttPatchResult.BaselineUnavailable, patchFromDesired(desired, notFinite))
        assertEquals(CompositeAttPatchResult.InvalidDesired, patchFromDesired(desired.copy(tone = Float.NaN), raw))
        assertNull(serializeCompositeAttSettings(desired.copy(amplification = 1f, balance = 1f)))
        assertNull(serializeCompositeAttSettings(desired.copy(transparencyEq = List(8) { Float.POSITIVE_INFINITY })))
        val bytes = Base64.getDecoder().decode(requireNotNull(serializeCompositeAttSettings(desired)).substringAfter(':'))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(52, Float.NaN) // even an unowned frequency must be finite
        assertNull(parseCompositeAttSettings(ATTHandles.TRANSPARENCY, "v1:" + Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun comparisonChecksBothWireGainsAndUsesOnlySmallFloatTolerance() {
        val desired = CompositeAttSettings(ATTHandles.TRANSPARENCY, amplification = 0.3f, balance = 0.6f)
        val wire = (patchFromDesired(desired, baseline(ATTHandles.TRANSPARENCY, 104)) as CompositeAttPatchResult.Ready).bytes
        val buffer = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, buffer.getFloat(36), 0.000001f)
        assertEquals(0.6f, buffer.getFloat(84), 0.000001f)
        buffer.putFloat(40, desired.tone + 0.00005f)
        assertTrue(matchesOwned(desired, wire))
        buffer.putFloat(40, desired.tone + 0.0002f)
        assertFalse(matchesOwned(desired, wire))
        buffer.putFloat(40, desired.tone)
        buffer.putFloat(36, 0.1f)
        buffer.putFloat(84, 0.5f) // unchanged average, different balance
        assertFalse(matchesOwned(desired, wire))
    }

    @Test
    fun legacyTransparencyEncoderDoesNotTruncateUnknownTail() {
        val raw = baseline(ATTHandles.TRANSPARENCY, 112)
        val settings = requireNotNull(parseTransparencySettingsResponse(raw)).copy(enabled = false, ownVoiceAmplification = 999f)
        val wire = requireNotNull(encodeTransparencySettings(raw, settings))
        assertEquals(raw.size, wire.size)
        assertArrayEquals(raw.copyOfRange(100, 104), wire.copyOfRange(100, 104))
        assertArrayEquals(raw.copyOfRange(104, 112), wire.copyOfRange(104, 112))
        assertNull(encodeTransparencySettings(raw.copyOf(103), settings))
    }

    @Test
    fun legacyImportNeverInventsPersonalFrequencyOwnershipOrHidesAsymmetry() {
        for (handle in listOf(ATTHandles.HEARING_AID, ATTHandles.TRANSPARENCY)) {
            val prior = CompositeAttSettings(handle, amplification = 0.25f, balance = 0.5f,
                tone = -0.25f, ambientNoiseReduction = 0.3f, conversationBoost = true,
                enabled = handle == ATTHandles.TRANSPARENCY,
                leftHearingLoss = List(8) { if (handle == ATTHandles.HEARING_AID && it == 1) 40f else null })
            val wire = (patchFromDesired(prior, baseline(handle, 112)) as CompositeAttPatchResult.Ready).bytes
            val imported = requireNotNull(compositeAttSettingsFromLegacy(handle, wire))
            assertEquals(prior.copy(leftHearingLoss = List(8) { null }), imported)
            assertTrue(imported.rightHearingLoss.all { it == null })
            ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).putFloat(88, 0.75f)
            assertNull(compositeAttSettingsFromLegacy(handle, wire))
        }
    }
}
