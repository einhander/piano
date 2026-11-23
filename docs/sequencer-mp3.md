# MP3 files in sequencer cells

Sequencer FILE cells accept MIDI or MP3 assets. Existing saved cells default to
MIDI; CHORD mode is unchanged. Import copies the source into private app storage
and validates it before replacing an assignment. An invalid or over-limit file
must leave the old assignment intact. Export copies the original source file,
including its `.mp3` extension, rather than converting audio to MIDI.

## Playback

MP3 uses the existing cell triggers and 16-slot allocation (15 performance slots
and one test-play slot). A cell uses one playback backend at a time; MIDI and MP3
in different cells can play together. MP3 plays at its natural speed. BPM,
MIDI channel, track selection and instrument sets do not affect MP3 and are not
offered as audio controls. Loop repeats decoded audio until explicit Stop;
one-shot playback ends at its decoded frame count. No time-stretch is performed.

Android MediaExtractor/MediaCodec decode `audio/mpeg` on a worker, normalizing
mono/stereo PCM16 or float output into interleaved stereo PCM16. Native rendering
resamples from the source rate to the actual engine rate and mixes through the
common master effects/MasterBus. It does not require a loaded SoundFont.

The callback does not decode, read files, allocate/free PCM, take worker locks or
call JNI. Immutable buffers are adopted through a preallocated command queue;
retirement acknowledgements allow worker-side deletion after callback use ends.
Native decoded-frame progress, not a UI timer, controls MP3 duration and looping.

## Limits and errors

- Compressed import is limited to 256 MiB.
- Decoded stereo PCM16 is limited to 128 MiB per asset: approximately 12.7
  minutes at 44.1 kHz or 11.6 minutes at 48 kHz. Duration depends on the
  source rate; the decoder's direct buffer is sized from the container
  duration and capped at this limit.
- Native PCM accounting has a 256 MiB resident budget (user-configurable in
  Settings, 64–512 MiB, `pcm_budget_mb`; changing it at runtime only affects
  new loads — resident buffers are kept until replaced or freed), covering
  all slots, pending/retired assets and a conservative upload-copy
  allowance. Two copies of a full-size asset (a prewarmed pad slot plus the
  test-play slot) sit at the default boundary; the test-play borrows the pad
  slot's idle copy of the same file so a preview always fits.
- Unsupported formats, malformed data, queue pressure and memory limits must
  be reported rather than silently truncating audio.

## PCM cache and prewarming

Import decodes each MP3 once (for validation) and persists the normalized
PCM, content-addressed by the file's SHA-256, under `<filesDir>/mp3_pcm_cache/`
(512 MiB cap, oldest entries evicted first). Slot loads read that file
natively on a worker thread (single copy, no Java heap, no MediaCodec) and
fall back to a live decode — re-caching afterwards — only when the cache is
missing or stale, so the first press, a test-play, and a startup prewarm are
all cheap: decode cost is paid exactly once, at import.

At engine boot (`preloadAll`) and after each MP3 import/replace, every
assigned MP3 cell with a usable trigger is prewarmed into its own slot on the
worker executor, filling the resident budget in cell order. A press then only
flips a resident buffer, like a MIDI cell. A cell whose prewarm did not fit
simply falls back to the on-demand load path (a cache read, not a decode).

## Validation

Run `app/src/test/cpp/run_pcm_slot_test.sh` for native slot rendering and
concurrent replacement/free tests with ASan/UBSan, including the PCM cache
file reader (header validation, corruption and budget rejection). JVM tests
cover legacy asset types, import validation/cleanup, preview-state policy,
and the content-addressed PCM cache (round-trip, corruption detection,
trimming). `./build.sh check`
builds both ARM ABIs and runs the JVM suite.

Device checks still required: import real MP3s (mono/stereo, 44.1/48 kHz), play
with no SF2, exercise Test/Stop/Loop and MIDI triggers, mix MIDI and MP3 cells,
replace/remove an active assignment, restart and reload assignments, export the
original MP3, and verify bad/oversized imports preserve the old cell. Platform
codec behavior and audible quality cannot be established by host tests alone.
