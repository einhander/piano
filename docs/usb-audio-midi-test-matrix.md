# USB Audio/MIDI diagnostic matrix

## Purpose and limits

SMK-37 Pro carries both USB MIDI input and USB audio output. MIDI selection is
correlated with distortion of other apps using that output. The existing log
does not establish the defective operation or component. These controls isolate
operations; they are not a confirmed fix and do not change the normal ALL-port
default. Keys and pads must remain usable together.

## Live port comparison

Open **Audio diagnostics** directly from the main screen. Record the selected
device, actual Android MIDI port numbers/names, per-port results and timings.
Compare **No ports**, **Ports 0 + 2**, and **ALL ports** using Apply. No ports may
retain a MIDI device handle: the report distinguishes an open handle from open
ports. Excluding port 1 is experimental, not a permanent routing decision.

For each case, play the same Telegram audio through SMK, then test keys and pads
where ports are enabled. Wait at least three seconds for follow-up snapshots.
Export the diagnostic report before leaving or terminating the process. Mark
the audible outcome separately; successful port opens and xrun zeros do not
prove uninterrupted playback.

## Cold-start orders

Schedule a one-shot next-launch plan, use **Exit**, then relaunch. Back/navigation
is not a cold process restart. A scheduled plan is consumed on first launch;
its active order remains visible across Activity recreation in that process.

| Plan | Intended operation order |
|---|---|
| AUDIO_THEN_MIDI | Existing audio/engine initialization, then selected MIDI mode |
| MIDI_THEN_AUDIO | MIDI connection result, then existing audio initialization |
| MIDI_ONLY | MIDI connection only; no PlaybackService/native/audio-stream startup |
| AUDIO_ONLY | Audio initialization without opening MIDI ports |

MIDI_ONLY reports stream not created, not merely stopped or closed. Use the main
screen's direct diagnostics entry, not Settings, to avoid creating unrelated
services during this experiment. Missing devices and failures must be recorded,
not treated as successful port activation.

## Audio lifecycle comparison

Lower volume before Stop/Close. Stop retains the stream; Close releases it while
preserving engine state and SoundFonts. Explicit Resume/Reopen validates the
negotiated rate and capacity against the existing engine before starting. A
changed rate or insufficient preallocated capacity requires a process restart;
no silent synth reinitialization or callback-time buffer allocation is allowed.

Record stream generation/state, requested and actual callback parameters,
sample rate, actual Oboe device ID, focus state and xrun availability before and
after each operation. Android output enumeration shows advertised capabilities,
not Telegram's actual route. OpenSL ES callback-mode xruns may be unavailable.

## Report and acceptance

Export includes the runtime snapshot, startup plan, advertised outputs and a
bounded App Log. Logs are passive: no raw USB transfers, interface claims or
USB-serial access. On-device `dumpsys midi`, `dumpsys usb`,
`dumpsys media.audio_policy`, `dumpsys media.audio_flinger` and logcat are useful
only when available on a separate diagnostic setup; they are not available on
this development machine and must be correlated with the experiment timestamps.

Repeat device switching, unplug/replug and third-party synth recovery as controls.
Test another controller/output where available. Root-cause acceptance requires
the hardware result, simultaneous keys/pads, no Telegram corruption and no
material latency regression. Host compilation and unit tests cannot establish
those criteria.
