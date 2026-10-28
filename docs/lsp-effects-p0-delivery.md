# P0 instrument and shared effects delivery

## Implemented stages

- A: pinned LSP 1.2.34 sources, compiled descriptor audit and production Android build. See `app/src/main/cpp/lsp-integration/STAGE_A_AUDIT.md` and `patches/LADSPA_DESCRIPTORS.md` beneath that directory.
- B/C: separate instrument catalog; old slots 0 EQ / 1 Compressor / 2 Limiter unchanged; Chorus 3 and Drive / Clipper 4 default OFF. Master count, IDs and parameters unchanged. Chorus feedback amount controls its hidden feedback enable, with OFF at zero.
- Clipper uses supported 1 ms ODP reactivity, with LUFS/ODP/dither disabled. Measured latency is 22 frames at 44.1 kHz and 24 at 48 kHz. Dry compensation remains after first effective activation, including bypass. UI reports latency; this is a Clipper, not a claimed guitar overdrive.
- D: one original bounded native stereo Delay, not LSP Artistic Delay. Maximum 2 seconds, feedback 0.90, default OFF, individual post-insert sends, wet-only return and silence tails. Reset invalidates generation tags without clearing the ring in callback. BPM sync supports quarter, eighth, dotted eighth, sixteenth and eighth triplet.
- E: existing FluidSynth global Reverb return retained, with checked enable and room/damping/width/level controls. No second Reverb or Impulse Reverb added.
- JNI, worker controls, old v1 preferences, distinct shared keys, scoped CC Learn and UI implemented. Reverb enable retains legacy `reverb` preference. New insert and Delay enable states default OFF.

Routing: `16 dry synth buses -> instrument inserts -> channel sum + FluidSynth return + shared Delay wet return -> Mixer -> original master EQ/Compressor/Limiter -> original MasterBus -> Oboe`.

Sample-rate transitions keep callback admission closed through graph preparation. On failure, callbacks output silence, retain the requested rate for retry, and never process a mixed-rate graph.

## Automated evidence (2026-10-28)

- `./build.sh debug`: BUILD SUCCESSFUL, native code built for arm64-v8a and armeabi-v7a.
- `./gradlew :app:testDebugUnitTest`: 239 tests, zero failures/errors/skips.
- Host regressions passed: effect_chain, compressor, ladspa_defaults, instrument_effect_manager, meter_snapshot, spectrum_capture_ring; plus compiled Chorus/Clipper DSP, shared_stereo_delay, shared_delay_routing, sample_rate_gate, Reverb command payload/overflow/concurrency and shared FX segment-offset checks.
- Instrument manager tests passed AddressSanitizer/UBSan. ThreadSanitizer runtime could not start (`unexpected memory mapping`); no TSan success claimed.
- Tests cover 16/64/128/256-frame blocks, 44.1/48 kHz, latency alignment, bypass/re-enable, concurrent getter/rebuild, missing descriptor, Delay timing/decay/tails/reset, segmented routing and injected rate-transition failures. These are host tests, not real-device load measurements.
- APK ZIP inspection: `lib/arm64-v8a/liblsp-plugins-ladspa.so` present, ELF machine 183, diagnostic markers absent. No armeabi-v7a LSP bundle is shipped; optional LSP effects report unavailable there, while synth/native Delay remain supported. Runtime behavior still requires device verification.
- Production LSP source artifact SHA-256: `939c4eed49c828d320346969489bb338285494fda04e706dfdd9e971aff359dd`. Build instructions, pins and license/source caveats are in `lsp-integration/prebuilt/README.md`; binaries remain ignored and CI rebuilds them.

## Existing MasterBus ceiling issue

No MasterBus DSP change was made. A host 440 Hz, amplitude-2 tone at 48 kHz, enabled default master Limiter, master volume 1, measured settled peak **0.38505620 before MasterBus** and **0.57569379 after MasterBus**. The existing post-limiter waveshaper therefore does not preserve the Limiter output ceiling. Treat ceiling correction as a separate task; this is not a new Clipper replacing the Limiter.

Reproduce with `lsp-integration/tests/master_bus_peak_probe.cpp`, the same includes/effect sources/link flags documented in `effect_chain_test.cpp`, plus `engine/MasterBus.cpp`; pass the host bundle path to the resulting executable.

## Remaining acceptance work

- No Android device/emulator was available. Live MIDI/reconnect, presets across process restart, background/foreground, 10–15 minute playing, xruns, callback duration, CPU/memory and loopback latency remain unverified.
- No full-app before/after WAV comparison or real-device 1/2/8/16-instrument load measurements were performed. Host all-OFF identity and routing tests are narrower evidence.
- Tap tempo is not implemented. Sync uses current transport BPM; manual Delay Time remains available. No independent music clock was introduced.
- Delay tails reset on sample-rate reprepare; controls and channel send targets survive. Seamless cross-device tail resampling is not implemented.
- P2 Impulse Reverb is deliberately excluded. Artistic Delay LADSPA executor safety was not established; it is not used in the callback.
