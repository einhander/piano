package com.piano.sequencer

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
    rawValue: Float
): EffectParameter? {
    if (info.size < 7) return null
    val id = info[0].toInt()
    val type = when {
        info[6] != 0f -> EffectParameterType.BOOLEAN
        info[5] != 0f -> EffectParameterType.INTEGER
        else -> EffectParameterType.FLOAT
    }
    return EffectParameter(
        id.toString(), displayName.ifEmpty { "Param $id" }, EffectParameterValue(rawValue),
        info[1], info[2], info[3], type, info[4] != 0f
    )
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
                    service.getMasterEffectParameter(slot, id)
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
