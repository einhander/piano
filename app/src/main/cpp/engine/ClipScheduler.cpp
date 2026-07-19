#include "ClipScheduler.h"

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

void ClipScheduler::process() {
    if (!mRunning.load(std::memory_order_acquire)) return;
    if (!mTransport || !mMidiQueue) return;

    double currentTick = mTransport->currentTick();

    // For each active clip, check if any events should fire at current tick
    for (int32_t i = 0; i < kMaxClips; i++) {
        ClipData* clip = mClips[i].clip.load(std::memory_order_acquire);
        if (!clip) continue;

        // Calculate clip-relative tick position
        double clipStartTick = static_cast<double>(clip->startTick);
        double clipRelativeTick = currentTick - clipStartTick;

        if (clipRelativeTick < 0) continue;  // Clip hasn't started yet
        if (clipRelativeTick > static_cast<double>(clip->lengthTicks)) continue;  // Clip has ended

        // Scan events for ones that should fire — start from last fired index
        for (int32_t j = mLastFiredEventIndex[i] + 1; j < clip->eventCount; j++) {
            if (clip->events[j].tick > static_cast<int64_t>(clipRelativeTick + 1)) break;  // Events sorted by tick

            // Fire this event
            MidiMessage msg;
            msg.status = clip->events[j].status;
            msg.data1 = clip->events[j].data1;
            msg.data2 = clip->events[j].data2;
            // timestamp = current tick / ticksPerFrame (simplified from clipStartTick + clipRelativeTick = currentTick)
            msg.timestamp = static_cast<int64_t>(currentTick / mTransport->ticksPerFrame);
            mMidiQueue->push(msg);

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