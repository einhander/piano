package com.piano.sequencer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Immutable control-layer representation of native effect metadata. */
enum class EffectParameterType { BOOLEAN, INTEGER, FLOAT }

data class EffectParameterValue(val rawValue: Float)

data class EffectParameter(
    val stableId: String,
    val displayName: String,
    val value: EffectParameterValue,
    val minimum: Float,
    val maximum: Float,
    val defaultValue: Float,
    val type: EffectParameterType,
    val logarithmic: Boolean,
    val unit: String? = null,
    val scale: String? = null,
    val enumValues: List<Pair<Float, String>> = emptyList(),
    val writable: Boolean = true
)

data class EffectDescriptor(val stableId: String, val displayName: String, val parameters: List<EffectParameter>)

data class EffectInstance(
    val slot: Int,
    val descriptor: EffectDescriptor,
    val enabled: Boolean,
    val parameters: List<EffectParameter>,
    val available: Boolean = true
)

/** Pure decoder for native [id, min, max, default, log, integer, toggled] metadata. */
internal fun effectParameterFromNativeInfo(
    info: FloatArray,
    displayName: String,
    rawValue: Float,
    effectStableId: String? = null
): EffectParameter? {
    if (info.size < 7) return null
    val id = info[0].toInt()
    val type = when {
        info[6] != 0f -> EffectParameterType.BOOLEAN
        info[5] != 0f -> EffectParameterType.INTEGER
        else -> EffectParameterType.FLOAT
    }
    val parameter = EffectParameter(
        id.toString(), displayName.ifEmpty { "Param $id" }, EffectParameterValue(rawValue),
        info[1], info[2], info[3], type, info[4] != 0f
    )
    if (effectStableId != "lsp.parametric_eq") return parameter
    // Stable IDs: effects/lsp/LspEffectIds.h. Names: pinned LSP 1.2.34
    // modules/lsp-plugins-para-equalizer/src/main/meta/para_equalizer.cpp.
    // Only presentation lives here; all bounds/defaults remain native-owned.
    val bandField = when {
        id in 30..35 -> id - 30
        id in 130..275 && id % 10 in 0..5 -> id % 10
        else -> -1
    }
    return when {
        id == 1 || id == 2 || bandField == 4 -> parameter.copy(unit = "dB", scale = "linear-amplitude")
        bandField == 0 -> parameter.copy(enumValues = EQ_FILTER_TYPES)
        bandField == 1 -> parameter.copy(enumValues = EQ_FILTER_MODES)
        bandField == 3 -> parameter.copy(
            displayName = parameter.displayName.replace(Regex("\\bfreq\\b", RegexOption.IGNORE_CASE), "Frequency"),
            unit = "Hz")
        bandField == 5 -> parameter
        else -> parameter
    }
}

private val EQ_FILTER_TYPES = listOf(
    "Off", "Bell", "Hi-pass", "Hi-shelf", "Lo-pass", "Lo-shelf", "Notch",
    "Resonance", "Allpass", "Bandpass", "Ladder-pass", "Ladder-rej"
).mapIndexed { index, name -> index.toFloat() to name }

private val EQ_FILTER_MODES = listOf(
    "RLC (BT)", "RLC (MT)", "BWC (BT)", "BWC (MT)", "LRX (BT)", "LRX (MT)", "APO (DR)"
).mapIndexed { index, name -> index.toFloat() to name }

/** Presentation only: never feed converted units back into DSP or preferences. */
internal fun formatEffectParameterValue(
    p: EffectParameter,
    value: Float,
    on: String = "On",
    off: String = "Off",
    locale: Locale = Locale.getDefault()
): String = when {
    p.enumValues.isNotEmpty() -> {
        val code = if (value.isFinite()) value.roundToInt() else null
        p.enumValues.firstOrNull {
            code != null && it.first == code.toFloat() && abs(value - code.toFloat()) < 0.001f
        }?.second ?: "Unknown (${if (code != null && value == code.toFloat()) code.toString() else value.toString()})"
    }
    p.type == EffectParameterType.BOOLEAN -> if (value >= 0.5f) on else off
    p.scale == "linear-amplitude" -> {
        val raw = value.coerceIn(p.minimum, p.maximum)
        if (raw <= 0f) "−∞ dB" else {
            val db = 20.0 * log10(raw.toDouble())
            // Suppress both signed zero and rounding-induced -0.0 near unity.
            if (abs(db) < 0.05) "0.0 dB" else String.format(locale, "%+.1f dB", db)
        }
    }
    p.unit == "Hz" -> String.format(locale, "%.1f Hz", value)
    p.type == EffectParameterType.INTEGER -> value.toInt().toString()
    else -> String.format(locale, "%.3f", value)
}

/** Converts native descriptor metadata to immutable snapshots; call only off the UI thread. */
class EffectRepository(private val service: com.piano.sequencer.service.PlaybackService) {
    fun snapshot(): List<EffectInstance> {
        if (!service.isMasterEffectChainAvailable()) return emptyList()
        return (0 until service.getMasterEffectCount()).map { slot ->
            val stable = service.getMasterEffectStableId(slot)
            val params = (0 until service.getMasterEffectParamCount(slot)).mapNotNull { index ->
                val info = service.getMasterEffectParamInfo(slot, index) ?: return@mapNotNull null
                val id = info.firstOrNull()?.toInt() ?: return@mapNotNull null
                effectParameterFromNativeInfo(
                    info, service.getMasterEffectParamName(slot, index),
                    service.getMasterEffectParameter(slot, id), stable
                )
            }
            val title = when (stable) {
                "lsp.parametric_eq" -> "Parametric EQ"
                "lsp.compressor" -> "Compressor"
                "lsp.limiter" -> "Limiter"
                else -> stable
            }
            val descriptor = EffectDescriptor(stable, title, params)
            EffectInstance(slot, descriptor, service.isMasterEffectEnabled(slot), params,
                service.isMasterEffectAvailable(slot))
        }
    }

    fun setEnabled(slot: Int, enabled: Boolean) = service.setMasterEffectEnabled(slot, enabled)
    fun setParameter(slot: Int, parameterId: Int, value: Float) =
        service.setMasterEffectParameter(slot, parameterId, value)
    fun meterSnapshot(slot: Int): FloatArray? = service.getMasterEffectMeterSnapshot(slot)
    fun setSpectrumCaptureEnabled(enabled: Boolean) = service.setSpectrumCaptureEnabled(enabled)
    fun readSpectrumCapture(output: FloatArray, maxFrames: Int): Int = service.readSpectrumCapture(output, maxFrames)
    fun getSpectrumCaptureDropped(): Int = service.getSpectrumCaptureDropped()
    fun getSampleRate(): Int = service.getSampleRate()
}
