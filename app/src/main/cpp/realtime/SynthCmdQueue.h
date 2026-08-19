#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>

// Lock-free MPSC queue of synth control commands.
//
// Producers: JNI/settings threads (polyphony, gain, reverb, chorus, interps,
// channel programs, panic, direct note on/off). Multiple producers are safe:
// the write position is claimed with a CAS (same pattern as MidiQueue).
// Consumer: the audio callback (single consumer) — drained in the real-time
// path, so pop() must be lock-free and allocation-free.
//
// This is the mechanism that removes the mutex from the FluidSynth render
// path: instead of the settings/JNI thread calling fluid_synth_* directly
// (which raced with the audio thread's render under mSynthMutex), every
// control command is enqueued here and applied by the audio thread, so ALL
// FluidSynth C API calls happen on a single thread (the audio thread).

// Command types. The fields (a, b, f) are interpreted per type:
//   NOTE_ON:           a=channel, b=note, f=velocity
//   NOTE_OFF:          a=channel, b=note
//   CC:                a=channel, b=controller, f=value
//   PROGRAM_CHANGE:    a=channel, b=program
//   PITCH_BEND:        a=channel, b=value (0-16383)
//   CHANNEL_PRESSURE:  a=channel, b=value
//   PANIC:             (no fields)
//   SET_POLYPHONY:     a=polyphony
//   SET_GAIN:          f=gain
//   SET_REVERB:        a=on (0/1)
//   SET_CHORUS:        a=on (0/1)
//   SET_INTERPS:       a=interp method (0/1/4)
//   SET_CHANNEL_PROGRAM: a=channel, b=bank, f=program
enum class SynthCmdType : uint8_t {
    NOTE_ON = 0,
    NOTE_OFF,
    CC,
    PROGRAM_CHANGE,
    PITCH_BEND,
    CHANNEL_PRESSURE,
    PANIC,
    SET_POLYPHONY,
    SET_GAIN,
    SET_REVERB,
    SET_CHORUS,
    SET_INTERPS,
    SET_CHANNEL_PROGRAM,
};

struct SynthCmd {
    SynthCmdType type;
    int32_t a;
    int32_t b;
    float f;
};

class SynthCmdQueue {
public:
    explicit SynthCmdQueue(int32_t capacity);
    ~SynthCmdQueue();

    // Non-copyable, non-movable
    SynthCmdQueue(const SynthCmdQueue&) = delete;
    SynthCmdQueue& operator=(const SynthCmdQueue&) = delete;
    SynthCmdQueue(SynthCmdQueue&&) = delete;
    SynthCmdQueue& operator=(SynthCmdQueue&&) = delete;

    // Push a command (returns true if successful, false if queue full).
    // MPSC-safe: uses CAS to handle concurrent producers.
    bool push(const SynthCmd& cmd);

    // Pop a command (returns true if successful, false if queue empty).
    // Single consumer (audio callback) — lock-free.
    bool pop(SynthCmd& cmd);

    // Number of available commands (approximate, for diagnostics).
    int32_t size() const;

    // Capacity
    int32_t capacity() const { return mCapacity; }

    // Dropped count (queue overflow) — for diagnostics.
    int32_t droppedCount() const { return mDroppedCount.load(); }

private:
    SynthCmd* mData = nullptr;
    int32_t mCapacity = 0;
    std::atomic<uint32_t> mWritePos{0};
    std::atomic<uint32_t> mReadPos{0};
    std::atomic<int32_t> mDroppedCount{0};
};