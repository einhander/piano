package com.piano.sequencer

import com.piano.sequencer.midi.sharedFxParameterFromNativeInfo

/** Order is the native instrument slot identity; append, never reorder. */
internal val instrumentInsertCardIds = listOf(
    "lsp.parametric_eq", "lsp.compressor", "lsp.limiter", "lsp.chorus", "lsp.clipper",
    "lsp.filter", "lsp.phaser", "lsp.flanger"
)

/** Worker-only at call sites. Instrument cards never contain global effects. */
internal val instrumentDelayCardIds = listOf("native.delay")

internal fun instrumentDelayControls(
    channel: Int,
    metadata: (Int) -> FloatArray?,
    current: (Int, Int) -> Float
): List<EffectParameter> {
    require(channel in 0..15)
    return (0..5).mapNotNull { id ->
        metadata(id)?.let { sharedFxParameterFromNativeInfo("native.delay", id, it, current(channel, id)) }
    }.map { sharedPresentation("native.delay", it) }
}

/** Presentation only. Native descriptors own bounds, flags and defaults. */
internal fun instrumentPresentation(effect: String, p: EffectParameter): EffectParameter = when (effect) {
    "lsp.chorus" -> when (p.stableId.toInt()) {
        300 -> p.copy(displayName = "Rate", unit = "Hz")
        // Pinned chorus 1.0.16 voices_list: enum 0..14 means 2..16 voices.
        301 -> p.copy(displayName = "Voices", enumValues = (2..16).mapIndexed { i, n -> i.toFloat() to n.toString() })
        302 -> p.copy(displayName = "Depth", unit = "ms")
        303 -> p.copy(unit = "%", scale = "fraction-percent")
        304 -> p.copy(displayName = "Dry/Wet", unit = "%")
        341 -> p.copy(displayName = "Dry gain", unit = "dB", scale = "linear-amplitude")
        342 -> p.copy(displayName = "Wet gain", unit = "dB", scale = "linear-amplitude")
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
    "lsp.filter" -> when (p.stableId.toInt()) {
        360 -> p.copy(displayName = "Frequency", unit = "Hz")
        361 -> p.copy(displayName = "Q", unit = null)
        362 -> p.copy(displayName = "Type", enumValues = listOf("Lo-pass", "Hi-pass", "Lo-shelf", "Hi-shelf", "Bell", "Bandpass", "Notch", "Resonance", "Ladder-pass", "Ladder-rej", "Allpass").mapIndexed { i, name -> i.toFloat() to name })
        363 -> p.copy(displayName = "Gain", unit = "dB", scale = "linear-amplitude")
        364 -> p.copy(displayName = "Width", unit = "octaves", scale = null)
        else -> p
    }
    "lsp.phaser" -> when (p.stableId.toInt()) {
        380 -> p.copy(displayName = "Rate", unit = "Hz")
        381 -> p.copy(displayName = "Depth", unit = "%", scale = "fraction-percent")
        382 -> p.copy(displayName = "Stages", enumValues = (1..8).mapIndexed { i, n -> i.toFloat() to n.toString() })
        383 -> p.copy(displayName = "Feedback", unit = "%", scale = "fraction-percent")
        384 -> p.copy(displayName = "Dry/Wet", unit = "%")
        else -> p
    }
    "lsp.flanger" -> when (p.stableId.toInt()) {
        400 -> p.copy(displayName = "Rate", unit = "Hz")
        401 -> p.copy(displayName = "Depth", unit = "ms")
        402 -> p.copy(displayName = "Feedback", unit = "%", scale = "fraction-percent")
        403 -> p.copy(displayName = "Dry/Wet", unit = "%")
        else -> p
    }
    else -> p
}

internal fun sharedPresentation(effect: String, p: EffectParameter): EffectParameter = when {
    effect == "native.delay" -> when (p.stableId.toInt()) {
        1 -> p.copy(unit = "ms")
        2, 3 -> p.copy(unit = "%", scale = "fraction-percent")
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
    else if (p.unit == "octaves") "${formatEffectParameterValue(p.copy(unit = null), raw)} octaves"
    else formatEffectParameterValue(p, raw)

/** Pinned Filter filter_has_width/adjust_gain, for the catalog's RLC type mapping. */
internal fun instrumentFilterControlVisible(parameterId: String, type: Float): Boolean = when (parameterId) {
    "363" -> type in listOf(2f, 3f, 4f, 7f, 8f, 9f)
    "364" -> type in listOf(5f, 8f, 9f)
    else -> true
}
