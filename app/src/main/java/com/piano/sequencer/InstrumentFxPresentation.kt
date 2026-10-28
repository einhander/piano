package com.piano.sequencer

import com.piano.sequencer.midi.sharedFxParameterFromNativeInfo

/** Worker-only at call sites: virtual Send is not a native shared parameter. */
internal fun sharedInstrumentControls(
    effect: String,
    metadata: (Int) -> FloatArray?,
    current: (Int) -> Float,
    currentSend: () -> Float
): List<EffectParameter> {
    val native = (0..if (effect == "native.delay") 5 else 4).mapNotNull { index ->
        metadata(index)?.let { sharedFxParameterFromNativeInfo(effect, index, it, current(index)) }
    }
    val controls = if (effect == "native.delay") native + EffectParameter(
        "6", "Delay Send", EffectParameterValue(currentSend()), 0f, 1f, 0f,
        EffectParameterType.FLOAT, false
    ) else native
    return controls.map { sharedPresentation(effect, it) }
}

/** Presentation only. Native descriptors own bounds, flags and defaults. */
internal fun instrumentPresentation(effect: String, p: EffectParameter): EffectParameter = when (effect) {
    "lsp.chorus" -> when (p.stableId.toInt()) {
        300 -> p.copy(displayName = "Rate", unit = "Hz")
        // Pinned chorus 1.0.16 voices_list: enum 0..14 means 2..16 voices.
        301 -> p.copy(displayName = "Voices", enumValues = (2..16).mapIndexed { i, n -> i.toFloat() to n.toString() })
        302 -> p.copy(displayName = "Depth", unit = "ms")
        303 -> p.copy(unit = "%", scale = "fraction-percent")
        304 -> p.copy(displayName = "Mix", unit = "%")
        else -> p
    }
    "lsp.clipper" -> when (p.stableId.toInt()) {
        320 -> p.copy(displayName = "Input drive", unit = "dB", scale = "linear-amplitude")
        // Pinned clipper sigmoid_functions; numeric codes are not drive amounts.
        321 -> p.copy(displayName = "Curve", enumValues = listOf("Hard clip", "Parabolic", "Sine", "Logistic", "Arctangent", "Hyperbolic tangent", "Guidermannian", "Error function", "Smoothstep", "Smootherstep", "Circle").mapIndexed { i, name -> i.toFloat() to name })
        322 -> p.copy(displayName = "Threshold", unit = "dB")
        323 -> p.copy(displayName = "Output gain", unit = "dB", scale = "linear-amplitude")
        else -> p
    }
    else -> p
}

internal fun sharedPresentation(effect: String, p: EffectParameter): EffectParameter = when {
    effect == "native.delay" -> when (p.stableId.toInt()) {
        1 -> p.copy(unit = "ms")
        2, 3, 6 -> p.copy(unit = "%", scale = "fraction-percent")
        4 -> p.copy(displayName = "Sync to current transport BPM")
        5 -> p.copy(enumValues = listOf("1/4", "1/8", "Dotted 1/8", "1/16", "Triplet 1/8").mapIndexed { i, name -> i.toFloat() to name })
        else -> p
    }
    p.stableId == "3" -> p.copy(unit = "%")
    p.stableId in listOf("1", "2", "4") -> p.copy(unit = "%", scale = "fraction-percent")
    else -> p
}

internal fun instrumentValueLabel(p: EffectParameter, raw: Float): String =
    if (p.scale == "fraction-percent") formatEffectParameterValue(p.copy(scale = null), raw * 100f)
    else formatEffectParameterValue(p, raw)
