package com.piano.sequencer

import com.piano.sequencer.midi.MAIN_INSTRUMENT_SET_ID
import com.piano.sequencer.midi.EffectCcMapping
import com.piano.sequencer.midi.EffectCcScope

/** Only old intents without an identity extra default to Main. */
internal fun instrumentEffectsSetId(hasExtra: Boolean, value: String?): String? =
    if (!hasExtra) MAIN_INSTRUMENT_SET_ID else value?.takeIf { it.isNotBlank() }

internal fun instrumentEffectsLearnTemplate(setId: String, localChannel: Int, effectId: String, parameter: EffectParameter) =
    EffectCcMapping(null, null, 0, localChannel, effectId, parameter.stableId,
        parameter.minimum, parameter.maximum, scope = EffectCcScope.INSTRUMENT, targetInstrumentSetId = setId)
