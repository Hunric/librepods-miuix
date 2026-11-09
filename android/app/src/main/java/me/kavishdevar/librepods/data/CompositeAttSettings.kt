package me.kavishdevar.librepods.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import me.kavishdevar.librepods.bluetooth.ATTHandles

/** Local intent; null hearing-loss entries are not owned by the app. */
data class CompositeAttSettings(
    val handle: ATTHandles,
    val amplification: Float = 0f,
    val balance: Float = 0f,
    val tone: Float = 0f,
    val ambientNoiseReduction: Float = 0.5f,
    val conversationBoost: Boolean = false,
    val enabled: Boolean = false,
    val transparencyEq: List<Float> = List(8) { 50f },
    val leftHearingLoss: List<Float?> = List(8) { null },
    val rightHearingLoss: List<Float?> = List(8) { null }
)

sealed interface CompositeAttPatchResult {
    data class Ready(val bytes: ByteArray) : CompositeAttPatchResult
    data object BaselineUnavailable : CompositeAttPatchResult
    data object InvalidDesired : CompositeAttPatchResult
}

private const val COMPOSITE_PREFIX = "v1:"
private const val COMPOSITE_SIZE = 116
private const val COMPOSITE_TOLERANCE = 0.0001f

// Little-endian field offsets shared by the owner comparison and the device-direction reader.
private const val OFFSET_ENABLED = 0
private const val OFFSET_BAND_LEFT = 4
private const val OFFSET_GAIN_LEFT = 36
private const val OFFSET_TONE_LEFT = 40
private const val OFFSET_BOOST_LEFT = 44
private const val OFFSET_REDUCTION_LEFT = 48
private const val OFFSET_BAND_RIGHT = 52
private const val OFFSET_GAIN_RIGHT = 84
private const val OFFSET_TONE_RIGHT = 88
private const val OFFSET_BOOST_RIGHT = 92
private const val OFFSET_REDUCTION_RIGHT = 96

fun defaultCompositeAttSettings(handle: ATTHandles): CompositeAttSettings? = when (handle) {
    ATTHandles.TRANSPARENCY, ATTHandles.HEARING_AID -> CompositeAttSettings(handle)
    else -> null
}

/** The two gains must fit the UI range without clamping so the mapping is reversible. */
fun isValidCompositeAttSettings(settings: CompositeAttSettings): Boolean {
    if (settings.handle != ATTHandles.TRANSPARENCY && settings.handle != ATTHandles.HEARING_AID) return false
    if (!settings.amplification.isFinite() || settings.amplification !in -1f..1f ||
        !settings.balance.isFinite() || settings.balance !in -1f..1f ||
        !settings.tone.isFinite() || settings.tone !in -1f..1f ||
        !settings.ambientNoiseReduction.isFinite() || settings.ambientNoiseReduction !in 0f..1f) return false
    val left = settings.amplification - settings.balance / 2f
    val right = settings.amplification + settings.balance / 2f
    if (left !in -1f..1f || right !in -1f..1f) return false
    if (settings.transparencyEq.size != 8 || settings.transparencyEq.any { !it.isFinite() || it !in 0f..100f }) return false
    if (settings.leftHearingLoss.size != 8 || settings.rightHearingLoss.size != 8 ||
        (settings.leftHearingLoss + settings.rightHearingLoss).any { it != null && !it.isFinite() }) return false
    if (settings.handle == ATTHandles.TRANSPARENCY &&
        (settings.leftHearingLoss.any { it != null } || settings.rightHearingLoss.any { it != null })) return false
    return true
}

/** Versioned binary payload so local storage does not depend on Android JSON stubs. */
fun serializeCompositeAttSettings(settings: CompositeAttSettings): String? {
    if (!isValidCompositeAttSettings(settings)) return null
    val hearing = settings.leftHearingLoss + settings.rightHearingLoss
    var mask = 0
    hearing.forEachIndexed { index, value -> if (value != null) mask = mask or (1 shl index) }
    val flags = (if (settings.enabled) 1 else 0) or (if (settings.conversationBoost) 2 else 0)
    val buffer = ByteBuffer.allocate(COMPOSITE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(settings.handle.value.toByte())
    buffer.put(flags.toByte())
    buffer.putShort(mask.toShort())
    buffer.putFloat(settings.amplification)
    buffer.putFloat(settings.balance)
    buffer.putFloat(settings.tone)
    buffer.putFloat(settings.ambientNoiseReduction)
    settings.transparencyEq.forEach { buffer.putFloat(it) }
    hearing.forEach { buffer.putFloat(it ?: 0f) }
    return COMPOSITE_PREFIX + Base64.getEncoder().encodeToString(buffer.array())
}

fun parseCompositeAttSettings(handle: ATTHandles, encoded: String): CompositeAttSettings? {
    if (!encoded.startsWith(COMPOSITE_PREFIX)) return null
    val payload = try { Base64.getDecoder().decode(encoded.substring(COMPOSITE_PREFIX.length)) }
    catch (_: IllegalArgumentException) { return null }
    if (payload.size != COMPOSITE_SIZE) return null
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    if ((buffer.get().toInt() and 0xff) != handle.value) return null
    val flags = buffer.get().toInt() and 0xff
    if ((flags and 0xfc) != 0) return null
    val mask = buffer.short.toInt() and 0xffff
    val amplification = buffer.float
    val balance = buffer.float
    val tone = buffer.float
    val reduction = buffer.float
    val eq = List(8) { buffer.float }
    val hearingValues = List(16) { buffer.float }
    if (hearingValues.any { !it.isFinite() }) return null
    val hearing = hearingValues.mapIndexed { index, value ->
        if ((mask and (1 shl index)) != 0) value else null
    }
    val settings = CompositeAttSettings(
        handle = handle, amplification = amplification, balance = balance, tone = tone,
        ambientNoiseReduction = reduction, conversationBoost = (flags and 2) != 0,
        enabled = (flags and 1) != 0, transparencyEq = eq,
        leftHearingLoss = hearing.take(8), rightHearingLoss = hearing.drop(8)
    )
    return settings.takeIf(::isValidCompositeAttSettings)
}

/**
 * Import ordinary controls from an explicitly user-saved legacy payload only.
 * A whole legacy packet does not prove that any personal hearing threshold was edited.
 * Return null instead of guessing when asymmetric ear values cannot be represented.
 */
fun compositeAttSettingsFromLegacy(handle: ATTHandles, raw: ByteArray?): CompositeAttSettings? {
    if (!isValidCompositeBaseline(handle, raw)) return null
    val buffer = ByteBuffer.wrap(raw!!).order(ByteOrder.LITTLE_ENDIAN)
    fun number(offset: Int): Float = buffer.getFloat(offset)
    fun equal(left: Int, right: Int): Boolean = abs(number(left) - number(right)) <= COMPOSITE_TOLERANCE
    fun flag(offset: Int): Boolean = number(offset) > 0.5f
    if (!equal(40, 88) || !equal(48, 96) || flag(44) != flag(92)) return null
    if (handle == ATTHandles.TRANSPARENCY && (0 until 8).any { !equal(4 + it * 4, 52 + it * 4) }) return null
    val leftGain = number(36)
    val rightGain = number(84)
    val settings = CompositeAttSettings(
        handle = handle,
        amplification = (leftGain + rightGain) / 2f,
        balance = rightGain - leftGain,
        tone = number(40),
        ambientNoiseReduction = number(48),
        conversationBoost = flag(44),
        enabled = handle == ATTHandles.TRANSPARENCY && flag(0),
        transparencyEq = if (handle == ATTHandles.TRANSPARENCY) List(8) { number(4 + it * 4) } else List(8) { 50f }
    )
    return settings.takeIf(::isValidCompositeAttSettings)
}

/** Construct a wire value only from a fresh, complete device value. */
fun patchFromDesired(settings: CompositeAttSettings, baseline: ByteArray?): CompositeAttPatchResult {
    if (!isValidCompositeAttSettings(settings)) return CompositeAttPatchResult.InvalidDesired
    if (!isValidCompositeBaseline(settings.handle, baseline)) return CompositeAttPatchResult.BaselineUnavailable
    val result = baseline!!.copyOf()
    val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    if (settings.handle == ATTHandles.HEARING_AID) {
        // Preserve the pre-existing write convention; this byte is not a local setting.
        buffer.put(2, 0x64)
        settings.leftHearingLoss.forEachIndexed { index, value -> value?.let { buffer.putFloat(4 + index * 4, it) } }
        settings.rightHearingLoss.forEachIndexed { index, value -> value?.let { buffer.putFloat(52 + index * 4, it) } }
    } else {
        buffer.putFloat(0, if (settings.enabled) 1f else 0f)
        settings.transparencyEq.forEachIndexed { index, value ->
            buffer.putFloat(4 + index * 4, value)
            buffer.putFloat(52 + index * 4, value)
        }
    }
    buffer.putFloat(36, settings.amplification - settings.balance / 2f)
    buffer.putFloat(84, settings.amplification + settings.balance / 2f)
    buffer.putFloat(40, settings.tone)
    buffer.putFloat(88, settings.tone)
    buffer.putFloat(44, if (settings.conversationBoost) 1f else 0f)
    buffer.putFloat(92, if (settings.conversationBoost) 1f else 0f)
    buffer.putFloat(48, settings.ambientNoiseReduction)
    buffer.putFloat(96, settings.ambientNoiseReduction)
    return CompositeAttPatchResult.Ready(result)
}

/** Compare only app-owned fields in a fresh remote value, never an ATT write ACK. */
fun matchesOwned(settings: CompositeAttSettings, actual: ByteArray?): Boolean {
    if (!isValidCompositeAttSettings(settings) || !isValidCompositeBaseline(settings.handle, actual)) return false
    val buffer = ByteBuffer.wrap(actual!!).order(ByteOrder.LITTLE_ENDIAN)
    fun same(offset: Int, expected: Float): Boolean = abs(buffer.getFloat(offset) - expected) <= COMPOSITE_TOLERANCE
    fun sameBoolean(offset: Int, expected: Boolean): Boolean = (buffer.getFloat(offset) > 0.5f) == expected
    if (!same(OFFSET_GAIN_LEFT, settings.amplification - settings.balance / 2f) ||
        !same(OFFSET_GAIN_RIGHT, settings.amplification + settings.balance / 2f) ||
        !same(OFFSET_TONE_LEFT, settings.tone) || !same(OFFSET_TONE_RIGHT, settings.tone) ||
        !same(OFFSET_REDUCTION_LEFT, settings.ambientNoiseReduction) ||
        !same(OFFSET_REDUCTION_RIGHT, settings.ambientNoiseReduction) ||
        !sameBoolean(OFFSET_BOOST_LEFT, settings.conversationBoost) ||
        !sameBoolean(OFFSET_BOOST_RIGHT, settings.conversationBoost)) return false
    return if (settings.handle == ATTHandles.TRANSPARENCY) {
        sameBoolean(OFFSET_ENABLED, settings.enabled) && settings.transparencyEq.indices.all { index ->
            same(OFFSET_BAND_LEFT + index * 4, settings.transparencyEq[index]) &&
                same(OFFSET_BAND_RIGHT + index * 4, settings.transparencyEq[index])
        }
    } else {
        settings.leftHearingLoss.indices.all { index ->
            settings.leftHearingLoss[index]?.let { same(OFFSET_BAND_LEFT + index * 4, it) } ?: true
        } && settings.rightHearingLoss.indices.all { index ->
            settings.rightHearingLoss[index]?.let { same(OFFSET_BAND_RIGHT + index * 4, it) } ?: true
        }
    }
}

/**
 * Read a device-reported payload into the app-owned fields with the same offsets and
 * semantics as [matchesOwned]. A payload whose left and right halves disagree where the
 * local model has a single field, or whose gains do not fit the UI range, yields null
 * rather than a guessed value.
 */
fun compositeAttSettingsFromDevice(handle: ATTHandles, raw: ByteArray): CompositeAttSettings? {
    if (!isValidCompositeBaseline(handle, raw)) return null
    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    fun number(offset: Int): Float = buffer.getFloat(offset)
    fun equal(left: Int, right: Int): Boolean = abs(number(left) - number(right)) <= COMPOSITE_TOLERANCE
    fun flag(offset: Int): Boolean = number(offset) > 0.5f
    if (!equal(OFFSET_TONE_LEFT, OFFSET_TONE_RIGHT) ||
        !equal(OFFSET_REDUCTION_LEFT, OFFSET_REDUCTION_RIGHT) ||
        flag(OFFSET_BOOST_LEFT) != flag(OFFSET_BOOST_RIGHT)) return null
    val leftGain = number(OFFSET_GAIN_LEFT)
    val rightGain = number(OFFSET_GAIN_RIGHT)
    if (!leftGain.isFinite() || !rightGain.isFinite()) return null
    val settings = when (handle) {
        ATTHandles.TRANSPARENCY -> {
            // One shared band list cannot express an asymmetric equalizer.
            if ((0 until 8).any { !equal(OFFSET_BAND_LEFT + it * 4, OFFSET_BAND_RIGHT + it * 4) }) return null
            CompositeAttSettings(
                handle = handle,
                amplification = (leftGain + rightGain) / 2f,
                balance = rightGain - leftGain,
                tone = number(OFFSET_TONE_LEFT),
                ambientNoiseReduction = number(OFFSET_REDUCTION_LEFT),
                conversationBoost = flag(OFFSET_BOOST_LEFT),
                enabled = flag(OFFSET_ENABLED),
                transparencyEq = List(8) { number(OFFSET_BAND_LEFT + it * 4) }
            )
        }
        ATTHandles.HEARING_AID -> CompositeAttSettings(
            handle = handle,
            amplification = (leftGain + rightGain) / 2f,
            balance = rightGain - leftGain,
            tone = number(OFFSET_TONE_LEFT),
            ambientNoiseReduction = number(OFFSET_REDUCTION_LEFT),
            conversationBoost = flag(OFFSET_BOOST_LEFT),
            // The device reports concrete per-ear thresholds, so every entry is owned.
            leftHearingLoss = List(8) { number(OFFSET_BAND_LEFT + it * 4) },
            rightHearingLoss = List(8) { number(OFFSET_BAND_RIGHT + it * 4) }
        )
        else -> return null
    }
    return settings.takeIf(::isValidCompositeAttSettings)
}

private fun isValidCompositeBaseline(handle: ATTHandles, value: ByteArray?): Boolean = when (handle) {
    ATTHandles.HEARING_AID -> value != null && value.size in 104..512 && parseHearingAidSettingsResponse(value) != null
    ATTHandles.TRANSPARENCY -> value != null && value.size <= 512 && parseTransparencySettingsResponse(value) != null
    else -> false
}
