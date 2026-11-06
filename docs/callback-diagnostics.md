# Callback diagnostics

Gradle selects native instrumentation explicitly: Debug passes
`-DPIANO_CALLBACK_DIAGNOSTICS=ON`; Release also explicitly passes `ON` for
the current investigation. CMake exports the
matching `PIANO_CALLBACK_DIAGNOSTICS=1/0` compiler definition. This avoids
assuming that C/C++ `NDEBUG` tracks Android Gradle build types (the earlier
`NDEBUG` gate caused diagnostic builds to report `diag=off`).

Debug and Release builds log `diag=on` with each 1 Hz `[perf]` snapshot. `cb_wall_max_us`
and `cb_gap_max_us` are maxima since prior snapshot; `cb_over_budget` counts
callbacks whose measured wall duration exceeded `numFrames / sampleRate`;
`cb_frames` and `cb_diag_count` give interval totals. Snapshot fields are
independently exchanged and approximate. Builds configured with diagnostics
disabled log `diag=off` and zero timing values.

Measurements use `clock_gettime(CLOCK_MONOTONIC)` at callback entry/exit solely
for diagnostics—not musical time. This adds two clock reads per enabled callback.
Linux/Android commonly serves this through vDSO, but syscall-free operation is
not guaranteed. Wall duration includes scheduling/preemption, so a high value
does not prove DSP overload; entry gaps include callback scheduling and idle
time, not specifically USB/audio-device starvation. Correlate with Oboe's
underrun count. These metrics do not identify per-underrun callback timings.

Run host aggregation check: `sh app/src/test/cpp/run_callback_timing_test.sh`.

The first engine callback and every 64th thereafter sample thread CPU time
(`engine_cpu_max_us`) and accumulated FluidSynth `renderChannels` wall time
(`synth_wall_max_us`), with `engine_sample_count` samples per approximate window.
These maxima are not paired observations. Thread CPU excludes time spent
descheduled, but clock reads have diagnostic overhead; the engine metric excludes
Oboe's XRun query and latency tuner, and callback guard destructors.

`cb_enter_total` and `cb_exit_total` are cumulative callback progress counters.
`cb_stage` indicates IDLE, ENTER, ENGINE, XRUN, TUNER or EXIT. Repeated unchanged
counters with ENGINE/XRUN/TUNER suggest an in-flight callback stuck in that
section; unchanged counters with IDLE suggest callbacks are not entering.
Snapshots are independent atomic reads, so interpret repeated windows rather
than one mismatched count. Stream lifecycle gaps are not DSP measurements.

Paired `sample_*` fields describe the last completed sampled engine callback,
not window maxima: total engine CPU, synth CPU, instrument FX CPU, Delay CPU,
bus summation CPU, mixer CPU, master CPU, unaccounted CPU, and synth wall time.
They permit stage comparisons within one sample. They do not necessarily
describe the callback with the largest duration in the window, and remain
unchanged if no new sample completes. A bounded reader returns zeros if it
cannot obtain a stable sample. Instrumentation overhead is included, especially
in unaccounted CPU; integer-microsecond rounding can affect stage sums.

## Evidence-first investigation

Use a short report: **observation → hypothesis → discriminating check → result**.
Label a cause as confirmed only when the check distinguishes it from alternatives.

1. Identify the newest relevant log by both file timestamp and event timestamps.
   Record its filename, app build SHA/version, actual API/rate/callback frames,
   buffer, controller/ports, SoundFont, effects, and CPU diagnostic settings when
   available. A log from another build is historical evidence, not a regression
   reproduction. State missing settings instead of guessing them.
2. Compare underrun **deltas** over equal windows and correlate with interval
   callback counts/frames and budget `actual frames / actual sample rate`.
   Separate startup, stream recovery, settings changes, and steady playback.
3. Distinguish measurements: wall time includes preemption; entry gap includes
   processing and idle/scheduling time; thread CPU is sampled; paired `sample_*`
   values describe one sampled callback, not the worst callback in the window.
   Callback totals or frames-per-callback alone cannot establish throughput or
   processor utilization without an elapsed interval.
4. Keep alternatives explicit: excess synth voices, DSP cost, callback scheduling,
   stream lifecycle, and duplicated MIDI delivery. Equal per-port input byte
   counts do not prove duplicate synth dispatch; verify the post-policy path.
5. For a regression, compare the known-good and failing builds with the same
   passage, controller, SoundFont and settings. Change one variable per run.
   Do not repeatedly recommend a buffer/callback setting the user already tested
   unsuccessfully unless new evidence justifies a controlled retest.

If current logs cannot distinguish the hypotheses, name the missing observation
and smallest next capture. Obtain approval before adding persistent diagnostics;
keep collection bounded and keep file I/O/logging outside the audio callback.
