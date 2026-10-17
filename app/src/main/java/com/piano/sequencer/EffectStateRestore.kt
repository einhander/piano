package com.piano.sequencer

import android.content.Context
import com.piano.sequencer.service.PlaybackService

/** Shared preference restoration for a newly loaded master effect chain. */
internal object EffectStateRestore {
    fun restore(context: Context, service: PlaybackService, effectCount: Int) {
        val prefs = context.getSharedPreferences("piano_prefs", Context.MODE_PRIVATE)
        for (slot in 0 until effectCount) {
            val paramCount = runCatching { service.getMasterEffectParamCount(slot) }.getOrDefault(0)
            for (index in 0 until paramCount) {
                val info = runCatching { service.getMasterEffectParamInfo(slot, index) }.getOrNull() ?: continue
                if (info.size < 4) continue
                val paramId = info[0].toInt()
                val key = "fx_param_${slot}_$paramId"
                if (prefs.contains(key)) {
                    runCatching { service.setMasterEffectParameter(slot, paramId, prefs.getFloat(key, info[3])) }
                }
            }
            runCatching { service.setMasterEffectEnabled(slot, prefs.getBoolean("fx_enabled_$slot", false)) }
        }
    }
}
