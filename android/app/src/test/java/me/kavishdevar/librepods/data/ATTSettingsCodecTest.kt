package me.kavishdevar.librepods.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class ATTSettingsCodecTest {
    private fun transparencyData(size: Int = 100): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(minOf(size / 4, 26)) { putFloat(it * 4, 0.25f) }
            putFloat(0, 1f)
            putFloat(36, -1f)
            putFloat(44, 0.75f)
            putFloat(84, 1f)
            putFloat(92, 0.5f)
        }.array()

    private fun hearingAidData(size: Int = 104): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x02, 0x02, 0x60, 0x00))
            repeat(25) { putFloat(4 + it * 4, 0.25f) }
            putFloat(36, -1f)
            putFloat(44, 0.75f)
            putFloat(84, 1f)
            putFloat(92, 0.5f)
        }.array()

    @Test
    fun transparencyRejectsNullAndEveryTruncatedLayout() {
        assertNull(parseTransparencySettingsResponse(null))
        val complete = transparencyData(104)
        for (size in 0 until 100) {
            assertNull("size=$size", parseTransparencySettingsResponse(complete.copyOf(size)))
        }
        for (size in 101..103) {
            assertNull("size=$size", parseTransparencySettingsResponse(complete.copyOf(size)))
        }
    }

    @Test
    fun transparencyPreservesBaseOptionalAndExtendedLayouts() {
        for (size in listOf(100, 104, 108)) {
            val settings = requireNotNull(parseTransparencySettingsResponse(transparencyData(size)))
            assertEquals(true, settings.enabled)
            assertArrayEquals(FloatArray(8) { 0.25f }, settings.leftEQ, 0f)
            assertArrayEquals(FloatArray(8) { 0.25f }, settings.rightEQ, 0f)
            assertEquals(-1f, settings.leftAmplification, 0f)
            assertEquals(1f, settings.rightAmplification, 0f)
            assertEquals(0f, settings.netAmplification, 0f)
            assertEquals(1f, settings.balance, 0f)
            assertEquals(true, settings.leftConversationBoost)
            assertEquals(false, settings.rightConversationBoost)
            if (size == 100) assertNull(settings.ownVoiceAmplification)
            else assertEquals(0.25f, requireNotNull(settings.ownVoiceAmplification), 0f)
        }
        // Device EQ and float booleans are not restricted to the UI slider range.
        val data = transparencyData()
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(4, 120f)
        assertEquals(120f, requireNotNull(parseTransparencySettingsResponse(data)).leftEQ[0], 0f)
    }

    @Test
    fun transparencyRejectsEveryNonFiniteFieldIncludingOptionalOwnVoice() {
        for (size in listOf(100, 104)) {
            for (index in 0 until size / 4) {
                for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                    val data = transparencyData(size)
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(index * 4, value)
                    assertNull("size=$size index=$index value=$value", parseTransparencySettingsResponse(data))
                }
            }
        }
    }

    @Test
    fun hearingAidRejectsNullTruncationAndEveryNonFiniteField() {
        assertNull(parseHearingAidSettingsResponse(null))
        val complete = hearingAidData()
        for (size in 0 until 104) {
            assertNull("size=$size", parseHearingAidSettingsResponse(complete.copyOf(size)))
        }
        for (index in 0 until 25) {
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val data = complete.copyOf()
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(4 + index * 4, value)
                assertNull("index=$index value=$value", parseHearingAidSettingsResponse(data))
            }
        }
    }

    @Test
    fun hearingAidPreservesCompleteAndExtendedLayouts() {
        for (size in listOf(104, 108)) {
            val settings = requireNotNull(parseHearingAidSettingsResponse(hearingAidData(size)))
            assertEquals(-1f, settings.leftAmplification, 0f)
            assertEquals(1f, settings.rightAmplification, 0f)
            assertEquals(true, settings.leftConversationBoost)
            assertEquals(false, settings.rightConversationBoost)
            assertEquals(0.25f, settings.ownVoiceAmplification, 0f)
            assertEquals(0f, settings.netAmplification, 0f)
            assertEquals(1f, settings.balance, 0f)
        }
    }

    @Test
    fun hearingAidEncodingLeavesReportedDataIntactAndPreservesHeaderAndTail() {
        val currentData = hearingAidData(108)
        currentData[104] = 0x5a
        val original = currentData.copyOf()
        val settings = requireNotNull(parseHearingAidSettingsResponse(currentData)).copy(
            leftEQ = FloatArray(8) { it / 10f },
            rightEQ = FloatArray(8) { -it / 10f },
            leftAmplification = 0.5f,
            rightAmplification = -0.5f,
            ownVoiceAmplification = 1f
        )
        val encoded = requireNotNull(encodeHearingAidSettings(currentData, settings))
        assertNotSame(currentData, encoded)
        assertArrayEquals(original, currentData)
        assertEquals(original[0], encoded[0])
        assertEquals(original[1], encoded[1])
        assertEquals(0x64.toByte(), encoded[2])
        assertEquals(original[3], encoded[3])
        assertArrayEquals(original.copyOfRange(104, 108), encoded.copyOfRange(104, 108))
        val decoded = requireNotNull(parseHearingAidSettingsResponse(encoded))
        assertEquals(settings.copy(ownVoiceAmplification = requireNotNull(parseHearingAidSettingsResponse(currentData)).ownVoiceAmplification), decoded)
        assertArrayEquals(original.copyOfRange(100, 104), encoded.copyOfRange(100, 104))
        assertArrayEquals(settings.leftEQ, decoded.leftEQ, 0f)
        assertArrayEquals(settings.rightEQ, decoded.rightEQ, 0f)
        encoded[0] = 0
        assertArrayEquals(original, currentData)
    }

    @Test
    fun hearingAidEncodingRejectsTruncatedSource() {
        val settings = requireNotNull(parseHearingAidSettingsResponse(hearingAidData()))
        for (size in 0 until 104) {
            assertNull("size=$size", encodeHearingAidSettings(ByteArray(size), settings))
        }
    }
}
