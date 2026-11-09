/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.ATTHandles
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TransparencySettings(
    val enabled: Boolean,
    val leftEQ: FloatArray,
    val rightEQ: FloatArray,
    val leftAmplification: Float,
    val rightAmplification: Float,
    val leftTone: Float,
    val rightTone: Float,
    val leftConversationBoost: Boolean,
    val rightConversationBoost: Boolean,
    val leftAmbientNoiseReduction: Float,
    val rightAmbientNoiseReduction: Float,
    val netAmplification: Float,
    val balance: Float,
    val ownVoiceAmplification: Float? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransparencySettings

        if (enabled != other.enabled) return false
        if (leftAmplification != other.leftAmplification) return false
        if (rightAmplification != other.rightAmplification) return false
        if (leftTone != other.leftTone) return false
        if (rightTone != other.rightTone) return false
        if (leftConversationBoost != other.leftConversationBoost) return false
        if (rightConversationBoost != other.rightConversationBoost) return false
        if (leftAmbientNoiseReduction != other.leftAmbientNoiseReduction) return false
        if (rightAmbientNoiseReduction != other.rightAmbientNoiseReduction) return false
        if (!leftEQ.contentEquals(other.leftEQ)) return false
        if (!rightEQ.contentEquals(other.rightEQ)) return false
        if (ownVoiceAmplification != other.ownVoiceAmplification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + leftAmplification.hashCode()
        result = 31 * result + rightAmplification.hashCode()
        result = 31 * result + leftTone.hashCode()
        result = 31 * result + rightTone.hashCode()
        result = 31 * result + leftConversationBoost.hashCode()
        result = 31 * result + rightConversationBoost.hashCode()
        result = 31 * result + leftAmbientNoiseReduction.hashCode()
        result = 31 * result + rightAmbientNoiseReduction.hashCode()
        result = 31 * result + leftEQ.contentHashCode()
        result = 31 * result + rightEQ.contentHashCode()
        result = 31 * result + (ownVoiceAmplification?.hashCode() ?: 0)
        return result
    }
}

fun parseTransparencySettingsResponse(data: ByteArray?): TransparencySettings? {
    // 25 floats are required; the optional own-voice field must also be complete.
    if (data == null || data.size < 100 || data.size in 101..103) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val floatCount = if (data.size >= 104) 26 else 25
    if ((0 until floatCount).any { !buffer.getFloat(it * 4).isFinite() }) return null

    val enabled = buffer.float

    val leftEQ = FloatArray(8)
    for (i in 0..7) {
        leftEQ[i] = buffer.float
    }
    val leftAmplification = buffer.float
    val leftTone = buffer.float
    val leftConvFloat = buffer.float
    val leftConversationBoost = leftConvFloat > 0.5f
    val leftAmbientNoiseReduction = buffer.float

    val rightEQ = FloatArray(8)
    for (i in 0..7) {
        rightEQ[i] = buffer.float
    }

    val rightAmplification = buffer.float
    val rightTone = buffer.float
    val rightConvFloat = buffer.float
    val rightConversationBoost = rightConvFloat > 0.5f
    val rightAmbientNoiseReduction = buffer.float

    val ownVoiceAmplification = if (buffer.remaining() >= 4) {
        buffer.float
    } else {
        null
    }

    val avg = (leftAmplification + rightAmplification) / 2
    val amplification = avg.coerceIn(-1f, 1f)
    val diff = rightAmplification - leftAmplification
    val balance = diff.coerceIn(-1f, 1f)

    return TransparencySettings(
        enabled = enabled > 0.5f,
        leftEQ = leftEQ,
        rightEQ = rightEQ,
        leftAmplification = leftAmplification,
        rightAmplification = rightAmplification,
        leftTone = leftTone,
        rightTone = rightTone,
        leftConversationBoost = leftConversationBoost,
        rightConversationBoost = rightConversationBoost,
        leftAmbientNoiseReduction = leftAmbientNoiseReduction,
        rightAmbientNoiseReduction = rightAmbientNoiseReduction,
        netAmplification = amplification,
        balance = balance,
        ownVoiceAmplification = ownVoiceAmplification
    )
}

private var debounceJob: Job? = null

internal fun encodeTransparencySettings(currentData: ByteArray, settings: TransparencySettings): ByteArray? {
    if (currentData.size > 512 || parseTransparencySettingsResponse(currentData) == null ||
        settings.leftEQ.size != 8 || settings.rightEQ.size != 8) return null
    val floats = settings.leftEQ.toList() + settings.rightEQ.toList() + listOf(
        settings.leftAmplification, settings.rightAmplification, settings.leftTone, settings.rightTone,
        settings.leftAmbientNoiseReduction, settings.rightAmbientNoiseReduction
    )
    if (floats.any { !it.isFinite() }) return null
    // Optional fields and unknown trailing bytes belong to the original device value.
    val buffer = ByteBuffer.wrap(currentData.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putFloat(0, if (settings.enabled) 1f else 0f)
    settings.leftEQ.forEachIndexed { i, value -> buffer.putFloat(4 + i * 4, value) }
    buffer.putFloat(36, settings.leftAmplification)
    buffer.putFloat(40, settings.leftTone)
    buffer.putFloat(44, if (settings.leftConversationBoost) 1f else 0f)
    buffer.putFloat(48, settings.leftAmbientNoiseReduction)
    settings.rightEQ.forEachIndexed { i, value -> buffer.putFloat(52 + i * 4, value) }
    buffer.putFloat(84, settings.rightAmplification)
    buffer.putFloat(88, settings.rightTone)
    buffer.putFloat(92, if (settings.rightConversationBoost) 1f else 0f)
    buffer.putFloat(96, settings.rightAmbientNoiseReduction)
    return buffer.array()
}

fun sendTransparencySettings(
    writer: (ATTHandles, ByteArray) -> Unit,
    transparencySettings: TransparencySettings,
    baseline: ByteArray? = null
): Job {
    val snapshot = baseline?.copyOf()
    debounceJob?.cancel()
    val job = CoroutineScope(Dispatchers.IO).launch {
        delay(100)
        try {
            val data = snapshot?.let { encodeTransparencySettings(it, transparencySettings) } ?: return@launch
            writer(ATTHandles.TRANSPARENCY, data)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    debounceJob = job
    return job
}
