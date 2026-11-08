# SMK-37 system-wide audio investigation

## Scope and status

The user reports that selecting SMK-37 corrupts all device playback, including
Telegram, and selecting another MIDI device immediately restores it. Audio
settings do not affect this symptom. The mechanism remains unconfirmed; changing
synth DSP settings is not an established fix.

Updated user evidence: Telegram audio itself is output through SMK-37's USB
audio output. SMK is a composite audio/MIDI device, not an independent input.
Distortion remains with Piano's stream Closed (verified), and the user reports
it also remains after Exit. USB reconnection restores sound. The alternative
selection is virtual FluidSynth/Volcano, which releases SMK rather than testing
another physical controller. Force-stop behavior has not been established.
Launching Piano can separately pause Telegram through its media audio-focus
request; the reported corruption is distortion during ongoing playback.

## Evidence

- `test_files/piano_log (6) (2).txt`, lines 71–90: SMK-37 Pro opens three output
  ports; all report zero received callbacks/bytes before disconnect. This argues
  against a MIDI message flood reaching this app during that interval, not against
  activity in the USB stack. The supplied log is a local, untracked artifact.
- `midi/MidiDeviceManager.kt`: selection closes the previous connection, opens
  the selected Android MIDI device and connects every output port. It does not
  perform direct USB transfers or reset the synth.
- The audio stream remains started and its frame counter advances. Reported
  `underruns=0` is not proof of uninterrupted playback: the OpenSL ES callback
  backend does not support the xrun count used here. Callback timing pressure is
  present before SMK selection as well.
- Audio focus is acquired before selection. No focus/routing transition explaining
  the symptom appears in the supplied log. Actual OEM routing is not established.

## Platform boundary

Android 10 AOSP `UsbMidiDevice.onDeviceStatusChanged()` activates its native USB
MIDI resources when any client port is open. `openLocked()` calls
`nativeOpen(card, device, subdeviceCount)`, creates streams for the returned
descriptors, and starts a reader using blocking `Os.poll(..., -1)`.

The corresponding JNI implementation loops over `subdevice_count` and opens
the same `/dev/snd/midiC%dD%d` node with `O_RDWR` for each rawmidi subdevice.
Consequently, an app receiving MIDI through the standard API does not control
native read-only versus read/write activation. This establishes the activation
boundary, not the cause of USB audio corruption; idle MIDI output traffic is
not demonstrated.

Opening only port 0 therefore does not establish that only one native USB/ALSA
resource is active. Generic AOSP behavior does not prove a defect in the phone's
OEM build, SMK firmware, USB controller, or kernel. No busy loop is demonstrated.

Sources, Android 10 tag `android-10.0.0_r1`:
- https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/services/usb/java/com/android/server/usb/UsbMidiDevice.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/media/java/android/media/midi/MidiDevice.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-10.0.0_r1/services/core/jni/com_android_server_UsbMidiDevice.cpp

## Discriminating checks

1. With Telegram playing and SMK selected, use the existing Audio diagnostics
   **Close our audio stream** action. Lower volume first. This intentionally stops
   this app's audio until a full process restart. Do not change MIDI selection.
   Record whether Telegram distortion persists and save App Log. Verify the log
   confirms stream closed and SMK did not disconnect; otherwise result is invalid.
   This action retains audio focus, so persistent distortion would not by itself
   rule out a focus interaction. Ordinary navigation pauses MainActivity without
   disconnecting MIDI; activity destruction would disconnect it and invalidate
   the comparison.
2. Identify whether the alternative device that restores sound is another physical
   USB device or a virtual device such as FluidSynth MIDI / Volcano MIDI Looper.
   Switching to a virtual device releases SMK and is not a physical-device control.
3. If needed, implement a temporary connection probe: device open without ports,
   port 0 only, and all ports, plus full disconnect as control. Keep DSP/focus
   unchanged and log each phase. Do not infer the audible outcome from xrun zeros.

Acceptance requires observed audible results correlated with actual connection
and stream states. Host builds cannot reproduce this hardware/system boundary.
Until those results exist, USB-MIDI activation is a leading hypothesis, not a
confirmed root cause or justification for a transport rewrite.
