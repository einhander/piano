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
- Decoded stereo PCM16 is limited to 64 MiB per asset: approximately 5.8 minutes
  at 48 kHz, or 6.3 minutes at 44.1 kHz. Duration depends on the source rate.
- Native PCM accounting has a 128 MiB budget, including pending/retired assets
  and a conservative upload-copy allowance. The decoder also uses a bounded
  64 MiB direct buffer; codec-internal allocations are outside these PCM limits.
- Assets are loaded on demand, not eagerly decoded for every stored cell.
  Unsupported formats, malformed data, queue pressure and memory limits must be
  reported rather than silently truncating audio.

## Validation

Run `app/src/test/cpp/run_pcm_slot_test.sh` for native slot rendering and
concurrent replacement/free tests with ASan/UBSan. JVM tests cover legacy asset
types, import validation/cleanup and preview-state policy. `./build.sh check`
builds both ARM ABIs and runs the JVM suite.

Device checks still required: import real MP3s (mono/stereo, 44.1/48 kHz), play
with no SF2, exercise Test/Stop/Loop and MIDI triggers, mix MIDI and MP3 cells,
replace/remove an active assignment, restart and reload assignments, export the
original MP3, and verify bad/oversized imports preserve the old cell. Platform
codec behavior and audible quality cannot be established by host tests alone.
