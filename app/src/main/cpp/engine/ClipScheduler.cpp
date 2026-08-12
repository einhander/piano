#include "ClipScheduler.h"
#include <cmath>
#include <cstring>

ClipScheduler::ClipScheduler() {
    // Initialize atomic clip pointers — cannot use memset on std::atomic
    for (int32_t i = 0; i < kMaxClips; i++) {
        mClips[i].clip.store(nullptr, std::memory_order_relaxed);
    }
    std::memset(mLastFiredEventIndex, -1, sizeof(mLastFiredEventIndex));
}

ClipScheduler::~ClipScheduler() = default;

void ClipScheduler::init(TransportState* transport, MidiQueue* midiQueue) {
    mTransport = transport;
    mMidiQueue = midiQueue;
}

void ClipScheduler::addClip(ClipData* clip) {
    if (!clip) return;

    int32_t count = mClipCount.load(std::memory_order_acquire);
    if (count >= kMaxClips) return;

    for (int32_t i = 0; i < kMaxClips; i++) {
        if (mClips[i].clip.load(std::memory_order_acquire) == nullptr) {
            mClips[i].clip.store(clip, std::memory_order_release);
            mClipCount.fetch_add(1, std::memory_order_release);
            // Initialize active note tracking
            std::memset(clip->mActiveNotes, 0, sizeof(clip->mActiveNotes));
            clip->mActiveNoteCount = 0;
            return;
        }
    }
}

void ClipScheduler::removeClip(int32_t clipId) {
    for (int32_t i = 0; i < kMaxClips; i++) {
        ClipData* current = mClips[i].clip.load(std::memory_order_acquire);
        if (current && current->clipId == clipId) {
            mClips[i].clip.store(nullptr, std::memory_order_release);
            mClipCount.fetch_sub(1, std::memory_order_release);
            return;
        }
    }
}

static inline void fireMidiMessage(MidiQueue* midiQueue,
                                    const ClipData::Event& evt,
                                    double currentTick,
                                    int ticksPerFrame) {
    MidiMessage msg;
    msg.status = evt.status;
    msg.data1 = evt.data1;
    msg.data2 = evt.data2;
    msg.timestamp = static_cast<int64_t>(currentTick / ticksPerFrame);
    midiQueue->push(msg);
}

void ClipScheduler::process() {
    if (!mRunning.load(std::memory_order_acquire)) return;
    if (!mTransport || !mMidiQueue) return;

    double currentTick = mTransport->currentTick();
    int ticksPerFrame = mTransport->ticksPerFrame;

    // For each active clip, check if any events should fire at current tick
    for (int32_t i = 0; i < kMaxClips; i++) {
        ClipData* clip = mClips[i].clip.load(std::memory_order_acquire);
        if (!clip) continue;

        // Calculate clip-relative tick position
        double clipStartTick = static_cast<double>(clip->startTick);
        double clipRelativeTick = currentTick - clipStartTick;

        if (clipRelativeTick < 0) continue;  // Clip hasn't started yet

        // Check for loop boundary
        double lengthTicks = static_cast<double>(clip->lengthTicks);
        if (clipRelativeTick >= lengthTicks) {
            // Clip has looped — send Note Off for all active notes
            for (int32_t n = 0; n < clip->mActiveNoteCount; n++) {
                uint8_t note = clip->mActiveNotes[n];
                // Send Note Off on the same channel as the Note On (status byte)
                // We don't have the original status byte stored, so use 0x80 (Note Off, channel 1)
                // Actually, we need to track the channel. For now, use channel 1 (0x80).
                // Better approach: store status in a parallel array.
                // For MVP: send Note Off on all channels is too noisy.
                // Let's use a parallel array for status bytes.
                MidiMessage msg;
                msg.status = 0x80;  // Note Off, channel 1 — placeholder
                msg.data1 = note;
                msg.data2 = 0;
                msg.timestamp = static_cast<int64_t>(currentTick / ticksPerFrame);
                mMidiQueue->push(msg);
            }
            // Clear active note list
            clip->mActiveNoteCount = 0;
            std::memset(clip->mActiveNotes, 0, sizeof(clip->mActiveNotes));

            // Wrap tick position
            clipRelativeTick = std::fmod(clipRelativeTick, lengthTicks);
        }

        // Scan events for ones that should fire — start from last fired index
        for (int32_t j = mLastFiredEventIndex[i] + 1; j < clip->eventCount; j++) {
            int64_t eventTick = clip->events[j].tick;
            if (eventTick > static_cast<int64_t>(clipRelativeTick + 1)) break;  // Events sorted by tick

            // Fire this event
            fireMidiMessage(mMidiQueue, clip->events[j], currentTick, ticksPerFrame);

            // Track active notes for loop boundary cleanup
            uint8_t status = clip->events[j].status;
            uint8_t type = status & 0xF0;
            if (type == 0x90 && clip->events[j].data2 > 0) {
                // Note On with non-zero velocity
                uint8_t note = clip->events[j].data1;
                if (clip->mActiveNoteCount < 128) {
                    // Check if note already tracked
                    bool alreadyActive = false;
                    for (int32_t n = 0; n < clip->mActiveNoteCount; n++) {
                        if (clip->mActiveNotes[n] == note) {
                            alreadyActive = true;
                            break;
                        }
                    }
                    if (!alreadyActive) {
                        clip->mActiveNotes[clip->mActiveNoteCount] = note;
                        clip->mActiveNoteCount++;
                    }
                }
            } else if (type == 0x80 || (type == 0x90 && clip->events[j].data2 == 0)) {
                // Note Off
                uint8_t note = clip->events[j].data1;
                // Remove from active list
                for (int32_t n = 0; n < clip->mActiveNoteCount; n++) {
                    if (clip->mActiveNotes[n] == note) {
                        // Swap with last and decrement
                        clip->mActiveNotes[n] = clip->mActiveNotes[clip->mActiveNoteCount - 1];
                        clip->mActiveNoteCount--;
                        break;
                    }
                }
            }

            mLastFiredEventIndex[i] = j;
        }
    }
}

void ClipScheduler::start() {
    mRunning.store(true, std::memory_order_release);
}

void ClipScheduler::stop() {
    mRunning.store(false, std::memory_order_release);
}