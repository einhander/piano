# Audio diagnostics menu

Main screen → **Audio diagnostics** exposes independent tests in one APK.
Diagnostic timing remains enabled in both Debug and Release during investigation.

## Close only our stream

1. Reproduce distorted audio, lower volume, and open the diagnostics screen.
2. Choose **Close our audio stream** and confirm.
3. Check the verified closed state, then try the other music player without
   force-stopping Piano Sequencer or reconnecting the USB device.
4. Save the App Log and note whether the player recovered.

The operation stops and closes Oboe, not the native engine. SoundFonts and channel
settings remain in memory. Audio focus is deliberately not abandoned by this
operation, so stream closure is tested separately from focus release. Another
player can still request focus through Android. Active audio recording must be
stopped first. Recovery, activity rebinds, and focus gain cannot reopen our stream
after manual closure; a full process restart is required.

## API and sample-rate tests

Save the next-start API and rate, use **Exit** on the main screen (or Android
Force stop), and relaunch. Leaving the activity is not a process restart. Saving
does not reconfigure the current stream, and ordinary stream recovery retains
the active process profile.

Compare one change at a time, using the same SoundFont, effects, USB connection
sequence, and passage. For example:

| Test | Requested API | Requested rate |
| --- | --- | --- |
| Reference | AAudio | 48,000 Hz |
| Rate | AAudio | System |
| Rate | AAudio | 44,100 Hz |
| API | OpenSL ES | 48,000 Hz |

Explicit rates retain a system-rate fallback if opening fails. Inspect the
**actual** API/rate/format and requested values in the current stream report and
App Log; a successful request alone does not prove the USB device's physical
format or clock. Oboe default is also available, but may choose a different API
and is less useful than an explicit API for a controlled comparison.

Record startup silence/distortion, delayed note bursts, playback stability,
reconnect behavior, and whether the other player recovers after stream closure.
No profile is yet established as a fix for the reported device problem.
