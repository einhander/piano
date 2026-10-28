#include "realtime/CallbackAdmissionGate.h"
#include "realtime/SampleRateGraphState.h"
#include <cassert>
#include <cstdio>
#include <stdexcept>

struct Context { CallbackAdmissionGate* gate; int failAt=0; bool throwAt=false; };
static bool inserts(void* p,int) { auto& c=*static_cast<Context*>(p); assert(!c.gate->tryEnter()); if(c.throwAt&&c.failAt==1)throw std::runtime_error("insert"); if(c.failAt==1)return false; return true; }
static bool delay(void* p,int) { auto& c=*static_cast<Context*>(p); assert(!c.gate->tryEnter()); if(c.throwAt&&c.failAt==2)throw std::runtime_error("delay"); return c.failAt!=2; }
static void activate(void* p,int) { auto& c=*static_cast<Context*>(p); assert(!c.gate->tryEnter()); if(c.throwAt&&c.failAt==3)throw std::runtime_error("activate"); }

int main() {
    CallbackAdmissionGate gate;
    SampleRateGraphState state;
    assert(gate.tryEnter());
    gate.leave();
    Context ctx{&gate};
    auto attempt=[&](int failure,bool throws,int expectedStatus) {
        gate.closeAndWait(); state.begin(48000); ctx.failAt=failure; ctx.throwAt=throws;
        const bool ok=finalizeSampleRateGraph(state,48000,&ctx,inserts,delay,activate);
        assert(!ok && !state.ready() && state.pendingRate()==48000);
        assert(state.status()==expectedStatus);
        gate.open(); assert(gate.tryEnter()); gate.leave(); // failed transition always releases gate
    };
    // Synth-preparation failure occurs before finalizer; retained request allows retry.
    gate.closeAndWait(); state.begin(48000); state.fail(2); gate.open();
    assert(!state.ready() && state.status()==2 && state.pendingRate()==48000);
    attempt(1,false,3); // inserts fail
    attempt(1,true,5);  // thrown insert preparation
    attempt(2,false,4); // delay fail
    attempt(2,true,5);  // thrown delay preparation
    gate.closeAndWait(); state.begin(48000); ctx.failAt=0; ctx.throwAt=false;
    assert(finalizeSampleRateGraph(state,48000,&ctx,inserts,delay,activate));
    assert(state.ready() && state.status()==0 && state.pendingRate()==0);
    gate.open(); assert(gate.tryEnter()); gate.leave();
    std::puts("sample_rate_gate_test: PASS");
}
