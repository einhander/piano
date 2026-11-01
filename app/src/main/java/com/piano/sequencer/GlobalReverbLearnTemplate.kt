package com.piano.sequencer

import com.piano.sequencer.midi.EffectCcMapping
import com.piano.sequencer.midi.EffectCcScope

/** Range comes from the native descriptor, including the enable toggle. */
internal fun globalReverbLearnTemplate(p: EffectParameter) = EffectCcMapping(
    null, null, 0, effectId = "fluidsynth.reverb", parameterId = p.stableId,
    minRaw = p.minimum, maxRaw = p.maximum, scope = EffectCcScope.GLOBAL
)
