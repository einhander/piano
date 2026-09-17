#include "ClipScheduler.h"
#include <cmath>
#include <cstring>

ClipScheduler::ClipScheduler() { for (auto& s : mClips) s.clip.store(nullptr); std::memset(mLastFiredEventIndex, -1, sizeof mLastFiredEventIndex); }
ClipScheduler::~ClipScheduler() = default;
void ClipScheduler::init(TransportState* transport) { mTransport = transport; }
void ClipScheduler::activateSlot(int32_t slot, ClipData* clip) {
    if (!clip || slot < 0 || slot >= kMaxClips) return;
    if (!mClips[slot].clip.load()) { mRuntime[slot] = ClipRuntime{}; mClips[slot].clip.store(clip); mClipCount.fetch_add(1); }
}
void ClipScheduler::deactivateSlot(int32_t slot) {
    if (slot >= 0 && slot < kMaxClips && mClips[slot].clip.exchange(nullptr)) mClipCount.fetch_sub(1);
}
int32_t ClipScheduler::collectDueEvents(int64_t begin, int64_t end, TimedMidiEvent* out, int32_t cap) {
    if (!mRunning.load() || !mTransport || !out || cap <= 0 || end <= begin) return 0;
    double tpf = mTransport->ticksPerFrame; if (!(tpf > 0.0)) return 0;
    int n = 0;
    for (int s=0; s<kMaxClips && n<cap; ++s) {
        ClipData* c=mClips[s].clip.load(); auto& r=mRuntime[s]; if (!c || c->lengthTicks<=0) continue;
        for (int pass = 0; pass < 64 && n < cap; ++pass) {
            while (r.nextEventIndex < c->eventCount) {
            auto& e=c->events[r.nextEventIndex];
            int64_t tick=c->startTick + r.loopIndex*c->lengthTicks + e.tick;
            int64_t frame=static_cast<int64_t>(std::ceil(tick/tpf));
            if (frame >= end) break;
            if (n >= cap) break;
            auto& x=out[n++]; x.targetFrame=frame; x.sourceSlot=static_cast<uint16_t>(s+1); x.order=r.nextOrder++; x.phase=TimedMidiEvent::Scheduled;
            x.targetFrame = frame < begin ? begin : frame;
            x.message={e.status,e.data1,e.data2,x.targetFrame ? x.targetFrame : 1};
            ++r.nextEventIndex;
            uint8_t type=e.status&0xf0, ch=e.status&0x0f;
            if (type==0x90 && e.data2) { if(r.activeNoteCount<128) r.activeNotes[r.activeNoteCount++]={ch,e.data1}; }
            else if(type==0x80 || (type==0x90&&!e.data2)) for(int i=0;i<r.activeNoteCount;++i) if(r.activeNotes[i].channel==ch&&r.activeNotes[i].note==e.data1){r.activeNotes[i]=r.activeNotes[--r.activeNoteCount];break;}
            }
        if (r.nextEventIndex >= c->eventCount) {
            const int64_t loopFrame = static_cast<int64_t>(std::ceil((c->startTick + (r.loopIndex + 1) * c->lengthTicks) / tpf));
            if (loopFrame >= end) break;
            if (loopFrame < end) {
                if (r.activeNoteCount > cap - n) break;
                while (r.activeNoteCount > 0) {
                    auto note = r.activeNotes[--r.activeNoteCount]; auto& x=out[n++];
                    x.targetFrame=loopFrame < begin ? begin : loopFrame; x.sourceSlot=static_cast<uint16_t>(s+1); x.order=r.nextOrder++; x.phase=TimedMidiEvent::Cleanup;
                    x.message={static_cast<uint8_t>(0x80 | note.channel), note.note, 0, x.targetFrame ? x.targetFrame : 1};
                }
                if (r.activeNoteCount != 0) break;
            }
            r.nextEventIndex=0; ++r.loopIndex;
            if (n >= cap) break;
        }
        }
    }
    return n;
}
void ClipScheduler::start(){mRunning.store(true);} void ClipScheduler::stop(){mRunning.store(false);}
