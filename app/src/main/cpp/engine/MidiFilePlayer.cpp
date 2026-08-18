#include "MidiFilePlayer.h"
#include "MidiFileParser.h"
#include "realtime/MidiQueue.h"

#include <fstream>
#include <algorithm>
#include <cstring>
#include <vector>
#include <utility>
#include <chrono>
#include <cmath>
#include <thread>

// ── Lock-free command queue (MPSC, follows existing MidiQueue pattern) ──
// Monotonic counters: w >= r always holds, so (w - r) is the correct occupied count.
// No masking needed for the full-check — masking is only for array indexing.

static bool cmdQueuePush(MidiFileCmd* buffer, std::atomic<uint32_t>* writePos,
                         std::atomic<uint32_t>* readPos, std::atomic<int32_t>* dropped,
                         const MidiFileCmd& cmd) {
    while (true) {
        uint32_t w = writePos->load(std::memory_order_relaxed);
        uint32_t r = readPos->load(std::memory_order_acquire);
        // Monotonic subtraction: no wrap-around false-drops
        if (w - r >= static_cast<uint32_t>(MidiFilePlayer::kCmdQueueCapacity)) {
            dropped->fetch_add(1, std::memory_order_relaxed);
            return false; // drop on overflow
        }
        uint32_t expected = w;
        if (!writePos->compare_exchange_weak(expected, w + 1,
                std::memory_order_release, std::memory_order_relaxed)) {
            continue;
        }
        buffer[w & (MidiFilePlayer::kCmdQueueCapacity - 1)] = cmd;
        return true;
    }
}

static bool cmdQueuePop(MidiFileCmd* buffer, std::atomic<uint32_t>* writePos,
                        std::atomic<uint32_t>* readPos, MidiFileCmd& out) {
    uint32_t w = writePos->load(std::memory_order_acquire);
    uint32_t r = readPos->load(std::memory_order_relaxed);
    if (r >= w) return false;
    out = buffer[r & (MidiFilePlayer::kCmdQueueCapacity - 1)];
    readPos->store(r + 1, std::memory_order_release);
    return true;
}

MidiFilePlayer::MidiFilePlayer() {
    // mSlots: in-class initializers cover all fields. The events[]/activeNotes[]
    // arrays are only read after the LOAD/START/FREE handlers have written them.
    std::memset(mCmdBuffer, 0, sizeof(mCmdBuffer));
}

MidiFilePlayer::~MidiFilePlayer() = default;

// ── Worker thread: load ──

int MidiFilePlayer::load(int slot, const char* filePath, float bpm, bool loop, int channel) {
    if (slot < 0 || slot >= kMaxSlots) return -1;
    if (!filePath) return -1;
    // channel < -1 or > 15 → invalid
    if (channel < -1 || channel > 15) return -1;

    MidiFileSlot* s = &mSlots[slot];

    // Worker-thread exclusion: only one worker may load this slot at a time
    // (two rapid UI actions on the same key must not interleave their copies).
    bool expected = false;
    if (!s->workerBusy.compare_exchange_strong(expected, true,
                                               std::memory_order_acquire,
                                               std::memory_order_relaxed)) {
        return -4; // another worker is loading this slot
    }
    struct BusyGuard {
        std::atomic<bool>& flag;
        ~BusyGuard() { flag.store(false, std::memory_order_release); }
    } busyGuard{s->workerBusy};

    // If slot is currently active/playing, enqueue FREE and wait for audio thread to consume
    // (worker blocking IS allowed here; timeout ~50ms → return error)
    if (s->active.load(std::memory_order_acquire) ||
        s->playing.load(std::memory_order_acquire)) {
        // Enqueue FREE command
        MidiFileCmd freeCmd;
        std::memset(&freeCmd, 0, sizeof(freeCmd));
        freeCmd.type = MidiFileCmdType::FREE;
        freeCmd.slot = slot;
        cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, freeCmd);

        // Wait for the audio thread to consume the FREE (bounded spin)
        if (!waitForFree(slot, 50)) {
            return -4; // slot busy — couldn't free in time
        }
    }

    // Parse the MIDI file on the worker thread (file I/O is allowed here)
    std::vector<RecordedMidiEvent> parsedEvents;
    std::vector<std::pair<int64_t, uint32_t>> tempoMap;
    std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
    int32_t ppq = 960; // default

    MidiFileParser parser;
    if (!parser.parse(filePath, parsedEvents, tempoMap, timeSigs, &ppq)) {
        return -1; // parse failed
    }

    // Check event count before clearing the slot (m2: don't destroy active slot state on failure)
    if (static_cast<int32_t>(parsedEvents.size()) > kMaxEventsPerSlot) {
        return -2; // file too long
    }

    // Re-check: a START command may have been consumed during the parse (user pressed
    // the pad while the file was loading). If the slot is now active/playing, free it
    // (flushes its notes) before overwriting the event buffer.
    if (s->active.load(std::memory_order_acquire) ||
        s->playing.load(std::memory_order_acquire)) {
        MidiFileCmd freeCmd;
        std::memset(&freeCmd, 0, sizeof(freeCmd));
        freeCmd.type = MidiFileCmdType::FREE;
        freeCmd.slot = slot;
        cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, freeCmd);
        if (!waitForFree(slot, 50)) {
            return -4;
        }
    }

    // Clamp BPM to sane range (n9)
    if (bpm <= 0.0f) {
        // Use initial tempo from file
        float initialTempo = 120.0f;
        if (!tempoMap.empty()) {
            uint32_t usPerQuarter = tempoMap[0].second;
            initialTempo = 60000000.0f / usPerQuarter;
        }
        bpm = initialTempo;
    }
    if (bpm < 20.0f) bpm = 20.0f;
    if (bpm > 300.0f) bpm = 300.0f;

    // Now clear the slot and copy events. active=true is a busy marker: the audio
    // thread skips the slot (playing=false) and the START/STOP/FREE handlers no-op
    // (loaded=false) until the LOAD command below finalizes the slot.
    s->playing.store(false, std::memory_order_release);
    s->loaded.store(false, std::memory_order_release);
    s->active.store(true, std::memory_order_release);
    s->eventCount = 0;
    s->eventIndex = 0;
    s->currentTick = 0.0;
    std::memset(s->activeNotes, 0, sizeof(s->activeNotes));

    // Copy events into slot buffer, normalizing velocity-0 note-ons to note-offs.
    // N3: local counter — s->eventCount is written once after the copy, so a FREE
    // consumed mid-copy (which zeros slot data) cannot race with the worker's
    // per-event increment.
    int64_t maxTick = 0;
    int32_t count = 0;
    for (const auto& evt : parsedEvents) {
        MidiFileEvent outEvt;
        outEvt.tick = evt.tick;
        outEvt.status = evt.status;
        outEvt.data1 = evt.data1;
        outEvt.data2 = evt.data2;
        outEvt.trackId = evt.trackId;

        // Velocity-0 note-on → treat as note-off (MIDI spec)
        uint8_t type = evt.status & 0xF0;
        if (type == 0x90 && evt.data2 == 0) {
            outEvt.status = 0x80; // convert to note-off
        }

        // D3: channel remap — when channel >= 0, remap all events to that channel.
        // Safe because MidiFileParser only stores channel-voice events (0x80-0xEF);
        // meta (0xFF) and sysex (0xF0+) are dropped by the parser.
        if (channel >= 0) {
            outEvt.status = (outEvt.status & 0xF0) | static_cast<uint8_t>(channel);
        }

        s->events[count] = outEvt;
        count++;
        if (evt.tick > maxTick) maxTick = evt.tick;
    }
    s->eventCount = count;

    // Compute initial tempo from tempo map
    float initialTempo = 120.0f;
    if (!tempoMap.empty()) {
        uint32_t usPerQuarter = tempoMap[0].second;
        initialTempo = 60000000.0f / usPerQuarter;
    }

    // Enqueue LOAD command for audio thread to finalize
    MidiFileCmd cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.type = MidiFileCmdType::LOAD;
    cmd.slot = slot;
    cmd.lengthTicks = maxTick;
    cmd.eventCount = count;
    cmd.ppq = ppq;
    cmd.bpm = bpm;
    cmd.loop = loop;

    // Store initialTempo directly on the slot (before enqueueing)
    // so getSlotInfo returns it correctly
    s->initialTempo = initialTempo;

    if (!cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, cmd)) {
        return -3; // command queue full
    }

    return 0;
}

// ── Audio thread: process ──

void MidiFilePlayer::process(int frameCount, int sampleRate, MidiQueue* liveMidiQueue) {
    if (!liveMidiQueue) return;

    // Drain commands from the queue (worker thread pushes, audio thread consumes)
    MidiFileCmd cmd;
    while (cmdQueuePop(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, cmd)) {
        switch (cmd.type) {
            case MidiFileCmdType::LOAD: {
                if (cmd.slot < 0 || cmd.slot >= kMaxSlots) continue;
                MidiFileSlot* s = &mSlots[cmd.slot];
                // initialTempo was already set on the worker thread before enqueueing
                s->lengthTicks = cmd.lengthTicks;
                s->eventCount = cmd.eventCount;
                s->ppq = cmd.ppq;
                s->bpm = cmd.bpm;
                s->loop.store(cmd.loop, std::memory_order_release);
                s->playing.store(false, std::memory_order_release);
                s->loaded.store(true, std::memory_order_release);
                s->active.store(true, std::memory_order_release); // last: gates process()
                s->eventIndex = 0;
                s->currentTick = 0.0;
                std::memset(s->activeNotes, 0, sizeof(s->activeNotes));
                break;
            }
            case MidiFileCmdType::START: {
                if (cmd.slot < 0 || cmd.slot >= kMaxSlots) continue;
                MidiFileSlot* s = &mSlots[cmd.slot];
                if (!s->loaded.load(std::memory_order_acquire)) continue;
                // m1: reset when !playing (not just !active) so replay after non-looped end works
                if (!s->playing.load(std::memory_order_acquire)) {
                    s->currentTick = 0.0;
                    s->eventIndex = 0;
                    std::memset(s->activeNotes, 0, sizeof(s->activeNotes));
                }
                s->playing.store(true, std::memory_order_release);
                break;
            }
            case MidiFileCmdType::STOP: {
                if (cmd.slot < 0 || cmd.slot >= kMaxSlots) continue;
                MidiFileSlot* s = &mSlots[cmd.slot];
                if (s->playing.load(std::memory_order_acquire)) {
                    flushActiveNotes(cmd.slot, liveMidiQueue);
                    s->playing.store(false, std::memory_order_release);
                }
                break;
            }
            case MidiFileCmdType::FREE: {
                if (cmd.slot < 0 || cmd.slot >= kMaxSlots) continue;
                MidiFileSlot* s = &mSlots[cmd.slot];
                // N3: busy state (worker mid-copy in load(): active && !loaded &&
                // !playing) — don't zero the data; the in-progress LOAD re-finalizes
                // the slot. This stale FREE is overridden by the newer LOAD.
                if (s->active.load(std::memory_order_acquire) &&
                    !s->loaded.load(std::memory_order_acquire) &&
                    !s->playing.load(std::memory_order_acquire)) {
                    break;
                }
                flushActiveNotes(cmd.slot, liveMidiQueue);
                s->playing.store(false, std::memory_order_release);
                s->loaded.store(false, std::memory_order_release);
                s->active.store(false, std::memory_order_release); // last: unlocks waitForFree
                s->eventCount = 0;
                s->eventIndex = 0;
                s->currentTick = 0.0;
                std::memset(s->activeNotes, 0, sizeof(s->activeNotes));
                break;
            }
            case MidiFileCmdType::SET_LOOP: {
                if (cmd.slot < 0 || cmd.slot >= kMaxSlots) continue;
                mSlots[cmd.slot].loop.store(cmd.loop, std::memory_order_release);
                break;
            }
            case MidiFileCmdType::SET_TEMPO: {
                if (cmd.slot < 0 || cmd.slot >= kMaxSlots) continue;
                // n9: clamp bpm to sane range
                float clamped = cmd.bpm;
                if (clamped < 20.0f) clamped = 20.0f;
                if (clamped > 300.0f) clamped = 300.0f;
                mSlots[cmd.slot].bpm = clamped;
                break;
            }
        }
    }

    // Process each active slot
    for (int i = 0; i < kMaxSlots; i++) {
        MidiFileSlot* s = &mSlots[i];
        if (!s->active.load(std::memory_order_acquire) ||
            !s->playing.load(std::memory_order_acquire)) continue;

        // n7: auto-stop empty slots
        if (s->eventCount == 0) {
            flushActiveNotes(i, liveMidiQueue);
            s->playing.store(false, std::memory_order_release);
            continue;
        }

        // n9: guard against NaN/zero bpm
        double effectiveBpm = s->bpm;
        if (effectiveBpm <= 0.0 || std::isnan(effectiveBpm)) {
            effectiveBpm = 120.0;
        }

        // ticksPerFrame = bpm * ppq / 60.0 / sampleRate
        double ticksPerFrame = (effectiveBpm * s->ppq) / (60.0 * sampleRate);

        // Advance current tick by frameDelta (double accumulator)
        s->currentTick += ticksPerFrame * frameCount;

        // Fire pending events
        firePendingEvents(i, liveMidiQueue);

        // N5: degenerate case — all events at tick 0 (lengthTicks == 0, eventCount > 0):
        // one-shot semantics — events fire once, then stop
        if (s->lengthTicks <= 0) {
            s->playing.store(false, std::memory_order_release);
            continue;
        }

        // n6: while loop for wrap + re-fire (handles files shorter than one callback).
        // N1: check playing — the non-loop branch of handleLoopWrap sets
        // currentTick == lengthTicks and playing=false; without this the loop would
        // spin forever in the audio callback.
        while (s->playing.load(std::memory_order_acquire) &&
               s->currentTick >= s->lengthTicks && s->lengthTicks > 0) {
            handleLoopWrap(i, liveMidiQueue);
            // After wrap, re-fire events at the new tick position
            firePendingEvents(i, liveMidiQueue);
        }
    }
}

// ── Audio thread: helpers ──

void MidiFilePlayer::firePendingEvents(int slot, MidiQueue* liveMidiQueue) {
    MidiFileSlot* s = &mSlots[slot];
    if (!s->loaded.load(std::memory_order_acquire) || s->eventIndex >= s->eventCount) return;

    while (s->eventIndex < s->eventCount &&
           s->events[s->eventIndex].tick <= s->currentTick) {

        const MidiFileEvent& evt = s->events[s->eventIndex];

        // Track active notes
        uint8_t type = evt.status & 0xF0;
        uint8_t channel = evt.status & 0x0F;

        if (type == 0x90 && evt.data2 > 0) {
            // Note on (velocity > 0) → mark as active
            int wordIdx = evt.data1 / 64;
            int bitIdx = evt.data1 % 64;
            s->activeNotes[channel][wordIdx] |= (1ULL << bitIdx);
        } else if (type == 0x80) {
            // n4: vel-0 note-ons are already normalized to 0x80 at load time
            // Note off → mark as inactive
            int wordIdx = evt.data1 / 64;
            int bitIdx = evt.data1 % 64;
            s->activeNotes[channel][wordIdx] &= ~(1ULL << bitIdx);
        }

        // Push event into live MIDI queue with tick timestamp
        // Convention: player events carry tick > 0; live keyboard events carry timestamp == 0
        MidiMessage msg;
        msg.status = evt.status;
        msg.data1 = evt.data1;
        msg.data2 = evt.data2;
        msg.timestamp = static_cast<int64_t>(s->currentTick);
        liveMidiQueue->push(msg);

        s->eventIndex++;
    }
}

void MidiFilePlayer::flushActiveNotes(int slot, MidiQueue* liveMidiQueue) {
    MidiFileSlot* s = &mSlots[slot];
    if (!s->loaded.load(std::memory_order_acquire)) return;

    for (int ch = 0; ch < 16; ch++) {
        for (int note = 0; note < 128; note++) {
            int wordIdx = note / 64;
            int bitIdx = note % 64;
            if (s->activeNotes[ch][wordIdx] & (1ULL << bitIdx)) {
                // Send Note Off
                MidiMessage msg;
                msg.status = 0x80 | static_cast<uint8_t>(ch);
                msg.data1 = static_cast<uint8_t>(note);
                msg.data2 = 0;
                msg.timestamp = static_cast<int64_t>(s->currentTick);
                liveMidiQueue->push(msg);

                // Clear the bit
                s->activeNotes[ch][wordIdx] &= ~(1ULL << bitIdx);
            }
        }
    }
}

void MidiFilePlayer::handleLoopWrap(int slot, MidiQueue* liveMidiQueue) {
    MidiFileSlot* s = &mSlots[slot];
    if (!s->loaded.load(std::memory_order_acquire)) return;

    // Flush all active notes before wrap
    flushActiveNotes(slot, liveMidiQueue);

    if (s->loop.load(std::memory_order_acquire)) {
        // Loop: wrap tick position, reset event index
        s->currentTick = s->currentTick - s->lengthTicks;
        // Keep fractional part to avoid drift
        if (s->currentTick < 0) s->currentTick = 0;
        s->eventIndex = 0;
    } else {
        // No loop: auto-stop
        s->playing.store(false, std::memory_order_release);
        s->currentTick = s->lengthTicks;
    }
}

// ── Worker-thread methods: all enqueue commands (M2) ──

void MidiFilePlayer::start(int slot) {
    if (slot < 0 || slot >= kMaxSlots) return;
    MidiFileCmd cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.type = MidiFileCmdType::START;
    cmd.slot = slot;
    cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, cmd);
}

void MidiFilePlayer::stop(int slot) {
    // M1: enqueue STOP command instead of direct flush (avoids null-deref)
    if (slot < 0 || slot >= kMaxSlots) return;
    MidiFileCmd cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.type = MidiFileCmdType::STOP;
    cmd.slot = slot;
    cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, cmd);
}

void MidiFilePlayer::setLoop(int slot, bool loop) {
    if (slot < 0 || slot >= kMaxSlots) return;
    MidiFileCmd cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.type = MidiFileCmdType::SET_LOOP;
    cmd.slot = slot;
    cmd.loop = loop;
    cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, cmd);
}

void MidiFilePlayer::setTempo(int slot, float bpm) {
    if (slot < 0 || slot >= kMaxSlots) return;
    MidiFileCmd cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.type = MidiFileCmdType::SET_TEMPO;
    cmd.slot = slot;
    cmd.bpm = bpm;
    cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, cmd);
}

void MidiFilePlayer::freeSlot(int slot) {
    if (slot < 0 || slot >= kMaxSlots) return;
    MidiFileCmd cmd;
    std::memset(&cmd, 0, sizeof(cmd));
    cmd.type = MidiFileCmdType::FREE;
    cmd.slot = slot;
    cmdQueuePush(mCmdBuffer, &mCmdWritePos, &mCmdReadPos, &mCmdDroppedCount, cmd);
}

bool MidiFilePlayer::isSlotPlaying(int slot) const {
    if (slot < 0 || slot >= kMaxSlots) return false;
    // Reads the atomic flag — safe from non-audio threads
    return mSlots[slot].playing.load(std::memory_order_acquire);
}

MidiFilePlayer::SlotInfo MidiFilePlayer::getSlotInfo(int slot) const {
    if (slot < 0 || slot >= kMaxSlots) {
        return SlotInfo{};
    }
    const MidiFileSlot& s = mSlots[slot];
    SlotInfo info;
    info.eventCount = s.eventCount;
    info.lengthTicks = s.lengthTicks;
    info.ppq = s.ppq;
    info.initialTempo = s.initialTempo;
    return info;
}

// ── Worker-thread: wait for FREE command to be consumed ──

bool MidiFilePlayer::waitForFree(int slot, int timeoutMs) {
    auto start = std::chrono::steady_clock::now();
    while (std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now() - start).count() < timeoutMs) {
        if (!mSlots[slot].active.load(std::memory_order_acquire) &&
            !mSlots[slot].loaded.load(std::memory_order_acquire) &&
            !mSlots[slot].playing.load(std::memory_order_acquire)) {
            return true;
        }
        // Brief yield
        std::this_thread::yield();
    }
    return false;
}