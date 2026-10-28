# Shared delay routing

`NativeEngine` owns one `SharedStereoDelay`, prepared at engine initialization
and re-prepared only in the worker's callback-quiescent sample-rate-change
path. Delay is a shared wet-only send/return, not a per-channel insert:

```text
16 FluidSynth dry channel buses → instrument inserts → [send-level sum]
                                                   → one stereo delay
                                                   → wet-only return added to track 0
FluidSynth shared FX return ────────────────────────────────┘ (unchanged)
Mixer → existing master FX chain → MasterBus → Oboe (unchanged)
```

Each event-bounded segment forms sends from post-insert buses using 16 atomic
send targets and audio-owned 1/128-per-sample level slews. Shared delay runs
once per rendered segment, including silence when synth is unavailable, so
tails are not gated on active notes. The return is added at the same frame
offset; dry audio is never routed through the delay. Send defaults to zero.

Storage: two delay rings, each `ceil(sampleRate * 2) + 2` float samples and
matching 64-bit generation tags, plus four `maxFrames` float planes (send L/R,
wet L/R). At 48 kHz this is 2,304,048 bytes of ring+tag payload +
`16 * maxFrames` bytes scratch (e.g. maxFrames=2048: 32 KiB), excluding vector
metadata. At maximum supported 384 kHz ring+tag payload is 18,432,096 bytes.
Exactly one delay instance regardless of channel
count. Explicit reset increments a generation and resets write cursor; stale
ring cells read as zero until overwritten. Reset is O(1) in callback, user-
requested only. Normal callback processing
does no allocation, lock, or logging. Time sync reads audio-owned transport
BPM; invalid BPM falls back to 120. Sample-rate changes close callback gate,
prepare/activate synth, apply transport rate, reprepare instrument inserts, and
prepare delay under one uninterrupted worker gate. Delay settings and channel
send targets survive; delay ring tails are reset at reprepare because frame
history at prior rate cannot be interpreted as same-time taps.
If synth preparation, insert preparation, delay preparation, or a finalizer
throws/fails, callback gate still opens but graph remains not-ready. Callback
emits silence before touching rate-dependent state; requested rate remains
pending and worker retry can prepare remaining stages (already-swapped synth
is reused). Transition status codes: 0 ready, 1 pending, 2 synth failure,
3 insert failure, 4 delay failure, 5 thrown finalizer. Failures log once per
status on worker thread, never from callback.

Native control IDs and ranges: `0 enabled` [0,1], default 0; `1 timeMs`
[1,2000], default 250; `2 feedback` [0,0.90], default 0.35; `3 wet` [0,1],
default 0.30; `4 sync` [0,1], default 0; `5 division` integer [0,4], default
0 (quarter, eighth, dotted eighth, sixteenth, eighth-triplet). Per-channel
send is [0,1], default 0. `getSharedDelayParameterInfo` exposes range/default
metadata; invalid IDs/channels or non-finite setter inputs return false.

Focused host checks: `tests/run_shared_stereo_delay_test.sh`,
`tests/run_shared_delay_routing_test.sh`, and `tests/run_sample_rate_gate_test.sh`.
