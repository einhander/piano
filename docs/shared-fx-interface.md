# Shared FX JNI/service interface

All service methods below call JNI directly. Invoke only from worker threads, never from UI callbacks or audio callbacks. Setters return `false` on invalid values, unavailable engine, or rejected native command; callers must persist only after success.

## Generic parameters

`PlaybackService.setSharedFxParameter(scope, parameterId, value): Boolean`, `getSharedFxParameter(scope, parameterId): Float`, `getSharedFxParamInfo(scope, parameterId): FloatArray?` and `isSharedFxAvailable(scope): Boolean`.

Metadata follows `[id,min,max,default,logarithmic,integer,toggled]`. Unknown scope/ID returns false/null/NaN. Scopes: `native.delay` (safe bounded native fallback, not LSP Artistic Delay) and `fluidsynth.reverb` (existing global FluidSynth return).

### `native.delay`

| ID | Parameter | Range / unit | Default | Type |
|---:|---|---|---:|---|
| 0 | enabled | 0..1 | 0 | toggle |
| 1 | time | 1..max delay ms | 250 | float |
| 2 | feedback | 0..bounded maximum | 0.35 | float |
| 3 | wet | 0..bounded maximum | 0.30 | float |
| 4 | sync | 0..1 | 0 | toggle |
| 5 | division | 0..4 | 0 | integer enum |

`setDelaySend(channel, level)` / `getDelaySend(channel)` control channel 0..15 send in 0..1. `resetSharedDelay()` requests tail reset; true means request accepted. Availability means shared delay prepared.

### `fluidsynth.reverb`

| ID | Parameter | Range / unit | Default |
|---:|---|---|---|
| 0 | enabled | 0..1 toggle | 1 |
| 1 | room size | 0..1 | native FluidSynth default |
| 2 | damping | 0..1 | native FluidSynth default |
| 3 | width | 0..100 | native FluidSynth default |
| 4 | level | 0..1 | native FluidSynth default |

Existing `setReverb(on)` remains supported. Reverb is global, not per-channel send.

## Instrument latency

`getInstrumentEffectLatencyFrames(channel, effectId)` returns frames; `getInstrumentEffectLatencyMillis(channel,effectId)` converts using actual current Oboe sample rate. Invalid/unavailable effects report zero frames. IDs include `lsp.chorus` and `lsp.clipper` where available.

## MIDI CC Learn helper APIs

`InstrumentEffectCcController.globalTargets()` returns `fluidsynth.reverb` parameters 0..4. Native instrument delay is an INSTRUMENT mapping (IDs 0..5) and uses `targetInstrumentChannel`, never incoming MIDI channel. Legacy stored `GLOBAL/native.delay` IDs 0..5 are converted to INSTRUMENT mappings on decode; legacy send ID 6 is disabled (no mapping), because per-instrument inserts have no send and remapping would alter behavior. Legacy global delay preferences migrate only channels with positive send; send attenuation is multiplied into wet. Legacy preferences remain intact; migration marker is written only after native calls succeed. Per-channel keys use `instrument_delay_v1_<channel>_<parameter>`; absent settings default disabled.

For instrument insert enable Learn, construct `EffectCcMapping(..., effectId = "lsp.chorus" /* or lsp.clipper */, parameterId = "-1", minRaw = 0f, maxRaw = 1f)` (default scope INSTRUMENT). Virtual ID `-1` dispatches `setInstrumentEffectEnabled` and persists only after success. It is distinct from all real parameter IDs, including existing parameter 0. Existing regular mappings retain their INSTRUMENT default scope. `manualParameterChanged(target,effect,parameter,value,scope = INSTRUMENT)` accepts GLOBAL for shared controls.

Legacy Delay Send mappings are ignored; there is no shared delay/send control in new MIDI Learn.
