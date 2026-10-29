# Callback diagnostics

Debug builds log `diag=on` with each 1 Hz `[perf]` snapshot. `cb_wall_max_us`
and `cb_gap_max_us` are maxima since prior snapshot; `cb_over_budget` counts
callbacks whose measured wall duration exceeded `numFrames / sampleRate`;
`cb_frames` and `cb_diag_count` give interval totals. Snapshot fields are
independently exchanged and approximate. Release builds log `diag=off` and
zero timing values.

Measurements use `clock_gettime(CLOCK_MONOTONIC)` at callback entry/exit solely
for diagnostics—not musical time. This adds two clock reads per debug callback.
Linux/Android commonly serves this through vDSO, but syscall-free operation is
not guaranteed. Wall duration includes scheduling/preemption, so a high value
does not prove DSP overload; entry gaps include callback scheduling and idle
time, not specifically USB/audio-device starvation. Correlate with Oboe's
underrun count. These metrics do not identify per-underrun callback timings.

Run host aggregation check: `sh app/src/test/cpp/run_callback_timing_test.sh`.
