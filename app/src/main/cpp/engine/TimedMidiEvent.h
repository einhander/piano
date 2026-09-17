#pragma once
#include "realtime/MidiQueue.h"
#include <cstdint>
struct TimedMidiEvent { enum Phase : uint8_t { Cleanup=0, Scheduled=1 }; int64_t targetFrame=0; MidiMessage message{}; uint32_t order=0; uint16_t sourceSlot=0; Phase phase=Scheduled; };
inline bool timedMidiEventLess(const TimedMidiEvent&a,const TimedMidiEvent&b){if(a.targetFrame!=b.targetFrame)return a.targetFrame<b.targetFrame;if(a.phase!=b.phase)return a.phase<b.phase;if(a.sourceSlot!=b.sourceSlot)return a.sourceSlot<b.sourceSlot;return a.order<b.order;}
