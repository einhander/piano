#pragma once

#include <atomic>
#include <cstdint>
#include <cstring>

// Pre-allocated event for a single slot
struct MidiFileEvent {
    int64_t tick;
    uint8_t status;
    uint8_t data1;
    uint8_t data2;
    uint8_t trackId;
};

// Command types for the worker→audio command queue
enum class MidiFileCmdType : uint8_t {
    LOAD = 0,
    START,
    STOP,
    FREE,
    SET_LOOP,
    SET_TEMPO
};

// Command passed through the lock-free queue
struct MidiFileCmd {
    MidiFileCmdType type;
    int32_t slot;
    int64_t lengthTicks;      // for LOAD: file length in ticks
    int32_t eventCount;       // for LOAD: number of events
    int32_t ppq;              // for LOAD: pulses per quarter
    float bpm;                // for LOAD/SET_TEMPO: playback BPM
    bool loop;                // for LOAD/SET_LOOP: loop flag
};

// Per-slot state — all pre-allocated in a static array
struct MidiFileSlot {
    // Event buffer (pre-allocated, 8192 events max)
    MidiFileEvent events[8192];
    int32_t eventCount = 0;

    // Active-note tracking: bit 0-127 per channel (0-15)
    // 16 channels × 128 notes = 2048 bits = 32 uint64_t
    uint64_t activeNotes[16][4]; // 16 ch × 128 notes (4 × 32-bit words per ch)

    // Playback position
    double currentTick = 0.0;     // double accumulator to avoid drift
    int64_t lengthTicks = 0;
    int32_t ppq = 960;
    float bpm = 120.0f;
    float initialTempo = 120.0f;

    // Control flags — std::atomic: worker threads read them in
    // isSlotPlaying()/waitForFree()/load(); the audio thread writes them in the
    // command handlers. All cross-thread accesses must use .load()/.store().
    std::atomic<bool> loop{false};
    std::atomic<bool> active{false};
    std::atomic<bool> loaded{false};
    std::atomic<bool> playing{false};

    // Worker-thread exclusion for load(): only one worker may load a given
    // slot at a time (two rapid UI actions on the same key must not interleave
    // their event-buffer copies). The audio thread never touches this.
    std::atomic<bool> workerBusy{false};

    // Event index pointer (reset on loop wrap)
    int32_t eventIndex = 0;
};

// Forward declaration for the live MIDI queue push
struct MidiQueue;

class MidiFilePlayer {
public:
    static constexpr int32_t kMaxSlots = 16;
    static constexpr int32_t kMaxEventsPerSlot = 8192;
    static constexpr int32_t kCmdQueueCapacity = 256;

    MidiFilePlayer();
    ~MidiFilePlayer();

    // Non-copyable, non-movable
    MidiFilePlayer(const MidiFilePlayer&) = delete;
    MidiFilePlayer& operator=(const MidiFilePlayer&) = delete;

    // Worker-thread: parse file and enqueue LOAD command.
    // Returns 0 on success, -1 (path invalid/no free slot), -2 (file too long),
    // -3 (command queue full), -4 (slot is active/playing — busy).
    int load(int slot, const char* filePath, float bpm, bool loop, int channel = -1);

    // Audio-thread: process all active slots for this audio frame.
    // frameCount: number of audio frames in this callback.
    // sampleRate: device sample rate.
    // liveMidiQueue: pointer to the live MIDI queue to push fired events into.
    void process(int frameCount, int sampleRate, MidiQueue* liveMidiQueue);

    // Worker-thread: enqueue START command (via command queue).
    // Safe to call from worker thread; never the audio callback.
    void start(int slot);

    // Worker-thread: enqueue STOP command (via command queue).
    // Safe to call from worker thread; never the audio callback.
    void stop(int slot);

    // Worker-thread: enqueue SET_LOOP command (via command queue).
    void setLoop(int slot, bool loop);

    // Worker-thread: enqueue SET_TEMPO command (via command queue).
    // Clamps bpm to [20, 300].
    void setTempo(int slot, float bpm);

    // Worker-thread: enqueue FREE command (via command queue).
    void freeSlot(int slot);

    // Non-audio-thread: check if a slot is playing (reads atomic flag).
    bool isSlotPlaying(int slot) const;

    // Non-audio-thread: get slot info (plain reads — stable during playback).
    struct SlotInfo {
        int32_t eventCount = 0;
        int64_t lengthTicks = 0;
        int32_t ppq = 960;
        float initialTempo = 120.0f;
    };
    SlotInfo getSlotInfo(int slot) const;

private:
    // Flush all active notes for a slot (send Note Offs).
    // MUST be called with a valid liveMidiQueue pointer (audio-thread only).
    void flushActiveNotes(int slot, MidiQueue* liveMidiQueue);

    // Fire pending events up to currentTick.
    void firePendingEvents(int slot, MidiQueue* liveMidiQueue);

    // Handle loop wrap for a slot.
    void handleLoopWrap(int slot, MidiQueue* liveMidiQueue);

    // Wait for the audio thread to consume a FREE command (bounded spin on worker).
    bool waitForFree(int slot, int timeoutMs);

    // Slot storage (pre-allocated, no heap in audio path)
    MidiFileSlot mSlots[kMaxSlots];

    // Command queue (lock-free, pre-allocated)
    MidiFileCmd mCmdBuffer[kCmdQueueCapacity];
    std::atomic<uint32_t> mCmdWritePos{0};
    std::atomic<uint32_t> mCmdReadPos{0};
    std::atomic<int32_t> mCmdDroppedCount{0};
};