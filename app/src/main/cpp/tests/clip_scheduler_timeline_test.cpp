#include "engine/ClipScheduler.h"
#include <cassert>
int main() {
    TransportState t; t.tpf=1.0; ClipScheduler s; s.init(&t); ClipData c{}; c.startTick=0; c.lengthTicks=8; c.eventCount=1; c.events[0]={2,0x90,60,100}; s.activateSlot(0,&c); s.start();
    TimedMidiEvent e[3]; assert(s.collectDueEvents(0,4,e,2)==1); assert(e[0].targetFrame==2 && e[0].sourceSlot==1 && e[0].message.timestamp!=0);
    assert(e[0].phase == TimedMidiEvent::Scheduled);
    ClipData late{}; late.lengthTicks=8; late.eventCount=2; late.events[0]={1,0x90,61,100}; late.events[1]={3,0x90,62,100};
    ClipScheduler retry; retry.init(&t); retry.activateSlot(0,&late); retry.start();
    TimedMidiEvent one[1]; assert(retry.collectDueEvents(4,8,one,1)==1); assert(one[0].targetFrame==4);
    TimedMidiEvent two[2]; assert(retry.collectDueEvents(4,8,two,2)==1); assert(two[0].targetFrame==4);
    assert(two[0].message.data1==62);
    ClipData loop{}; loop.lengthTicks=8; loop.eventCount=1; loop.events[0]={2,0x90,64,90};
    ClipScheduler boundary; boundary.init(&t); boundary.activateSlot(0,&loop); boundary.start();
    TimedMidiEvent boundaryEvents[2]; assert(boundary.collectDueEvents(0,8,boundaryEvents,2)==1);
    assert(boundary.collectDueEvents(8,9,boundaryEvents,2)==1);
    assert(boundaryEvents[0].phase==TimedMidiEvent::Cleanup);
    assert(boundaryEvents[0].message.status==0x80 && boundaryEvents[0].message.data1==64);
    assert(boundaryEvents[0].targetFrame==8 && boundaryEvents[0].message.timestamp!=0);
    assert(boundaryEvents[0].sourceSlot==1);
    assert(boundaryEvents[0].order>0);
    assert(boundaryEvents[0].message.data2==0);
    ClipData zero{}; zero.lengthTicks=0; zero.eventCount=1; zero.events[0]={0,0x90,1,1};
    ClipScheduler zeroScheduler; zeroScheduler.init(&t); zeroScheduler.activateSlot(0,&zero); zeroScheduler.start();
    assert(zeroScheduler.collectDueEvents(0,8,e,2)==0);
    TransportState ceilTransport; ceilTransport.tpf=0.6; ClipData fractional{}; fractional.lengthTicks=8; fractional.eventCount=1; fractional.events[0]={1,0x90,2,3};
    ClipScheduler ceilScheduler; ceilScheduler.init(&ceilTransport); ceilScheduler.activateSlot(0,&fractional); ceilScheduler.start();
    assert(ceilScheduler.collectDueEvents(0,3,e,2)==1 && e[0].targetFrame==2);
    ClipData atEnd{}; atEnd.lengthTicks=8; atEnd.eventCount=1; atEnd.events[0]={2,0x90,3,4};
    ClipScheduler endScheduler; endScheduler.init(&t); endScheduler.activateSlot(0,&atEnd); endScheduler.start();
    assert(endScheduler.collectDueEvents(0,2,e,2)==0);
    assert(endScheduler.collectDueEvents(2,3,e,2)==1);
    assert(e[0].message.data1==3 && e[0].targetFrame==2);
    ClipData retrigger{}; retrigger.lengthTicks=4; retrigger.eventCount=1; retrigger.events[0]={0,0x90,5,6};
    ClipScheduler retriggerScheduler; retriggerScheduler.init(&t); retriggerScheduler.activateSlot(0,&retrigger); retriggerScheduler.start();
    assert(retriggerScheduler.collectDueEvents(0,5,e,3)==3 && e[0].phase==TimedMidiEvent::Scheduled && e[1].phase==TimedMidiEvent::Cleanup && e[2].phase==TimedMidiEvent::Scheduled);
    assert(e[0].targetFrame==0 && e[1].targetFrame==4);
    assert(e[2].targetFrame==4);
    ClipData deferred{}; deferred.lengthTicks=2; deferred.eventCount=1; deferred.events[0]={1,0x90,7,8};
    ClipScheduler deferredScheduler; deferredScheduler.init(&t); deferredScheduler.activateSlot(0,&deferred); deferredScheduler.start();
    assert(deferredScheduler.collectDueEvents(0,2,e,2)==1);
    assert(deferredScheduler.collectDueEvents(2,3,e,2)==2);
    assert(deferredScheduler.collectDueEvents(2,3,e,2)==0);
    return 0;
}
