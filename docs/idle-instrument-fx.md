# Idle instrument insert optimization

The production profile retains 128 independent MIDI channels and stereo buses.
Callback size, FluidSynth routing, synth quality, and effect defaults are unchanged.

`InstrumentEffectManager` skips a channel only when no clipper is published,
no clipper timeline/alignment/wet transition is pending, and no other insert
is active or fading. This is not a note-activity optimization: sustain,
release voices, and delay tails are not used to decide whether to skip work.
Published clippers retain their existing history and alignment processing.

## Focused host evidence

Run `bash app/src/main/cpp/lsp-integration/tests/run_instrument_effect_manager_test.sh`.
The same test and compiler flags compared the original manager with the optimized
manager, using 128 non-null channel buffers, 64-frame blocks, 300 warmup blocks,
and 1000 measured blocks. Representative median / p95 times in microseconds:

| Configuration | Original | Optimized |
| --- | --- | --- |
| Unpublished idle inserts | 25.027 / 25.128 | 0.972 / 1.042 |
| One active non-clipper insert on channel 127 | 25.348 / 26.180 | 1.192 / 1.233 |
| Published, disabled clippers | 39.816 / 44.605 | 39.204 / 44.073 |

These are host measurements of insert management only, not Android callback
timings or proof that device crackling is fixed. Startup restoration does not
instantiate clippers for every unused channel, so unpublished idle channels are
a relevant case. Already-published clippers do not benefit from this fast path.

## Device acceptance

Compare release APKs using the same SoundFont, output API/rate, buffer, diagnostic
settings, and workload. Check callback overruns and per-stage costs, keyboard and
MIDI-file playback together, sustain and long releases, effect enable/disable,
delay tails, Panic, and channel isolation across channels 0, 16, and 127.
If crackling persists, profile the remaining measured bottleneck before changing
bus routing or increasing output latency.
