package me.kavishdevar.librepods.data

import org.junit.Assert.*
import org.junit.Test

class EarWearingStateTest {
    private fun report(first: Int, second: Int) =
        byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x06, 0x00, first.toByte(), second.toByte())

    @Test
    fun eitherInEarStatusPermitsWornObservation() {
        for ((first, second) in listOf(0 to 1, 2 to 0, 0 to 0, 0 to 255, 255 to 0)) {
            assertEquals(EarWearingState.WORN, parseEarWearingState(report(first, second)))
        }
    }

    @Test
    fun knownOutOfEarAndInCaseCombinationsAreNotWorn() {
        for ((first, second) in listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2)) {
            assertEquals(EarWearingState.NOT_WORN, parseEarWearingState(report(first, second)))
        }
    }

    @Test
    fun otherCompleteStatusesAreUnknownAndDoNotBlock() {
        for ((first, second) in listOf(1 to 3, 2 to 255, 3 to 1, 255 to 255)) {
            assertEquals(EarWearingState.UNKNOWN, parseEarWearingState(report(first, second)))
        }
        assertTrue(EarWearingState.UNKNOWN.allowsSettingsWrites)
        assertTrue(EarWearingState.WORN.allowsSettingsWrites)
        assertFalse(EarWearingState.NOT_WORN.allowsSettingsWrites)
    }

    @Test
    fun shortAndWrongPrefixFramesAreIgnored() {
        val valid = report(1, 2)
        for (size in 0 until valid.size) assertNull(parseEarWearingState(valid.copyOf(size)))
        for (index in 0..4) {
            val wrongPrefix = valid.copyOf()
            wrongPrefix[index] = (wrongPrefix[index].toInt() xor 0x7f).toByte()
            assertNull(parseEarWearingState(wrongPrefix))
        }
    }

    @Test
    fun unsupportedEarFramesReplaceNotWornWithUnknownAndAllowWrites() {
        val before = requireNotNull(parseEarWearingState(report(1, 2)))
        assertEquals(EarWearingState.NOT_WORN, before)
        assertFalse(before.allowsSettingsWrites)

        val longFrame = report(0, 0) + byteArrayOf(0)
        val nonzeroSixthByte = report(0, 0).apply { this[5] = 0x01 }
        for (frame in listOf(longFrame, nonzeroSixthByte)) {
            val after = requireNotNull(parseEarWearingState(frame))
            assertEquals(EarWearingState.UNKNOWN, after)
            assertTrue(after.allowsSettingsWrites)
        }
    }
}
