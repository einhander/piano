#include "effects/SharedStereoDelay.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

int main() {
    constexpr int frames=128;
    std::vector<float> buses[16];
    const float* ptrs[16]{};
    float levels[16]{};
    for(int c=0;c<16;++c) { buses[c].assign(frames*2,0); ptrs[c]=buses[c].data(); }
    buses[2][40*2]=0.25f; buses[2][40*2+1]=0.5f;
    buses[10][40*2]=0.75f; buses[10][40*2+1]=0.25f;
    levels[2]=0.5f; levels[10]=1.0f;
    float l,r;
    sumSharedDelaySends(ptrs,levels,39,l,r); assert(l==0 && r==0);
    sumSharedDelaySends(ptrs,levels,40,l,r);
    assert(std::fabs(l-0.875f)<1e-6f && std::fabs(r-0.5f)<1e-6f);

    SharedStereoDelay delay; assert(delay.prepare(48000,frames));
    delay.setEnabled(true); delay.setWet(1); delay.setFeedback(0);
    std::vector<float> sendL(frames),sendR(frames),wetL(frames),wetR(frames);
    // Explicit event-split routing. Delay only receives post-insert send sum,
    // output return is wet-only and added at matching segment offsets.
    for(int start : {0,64}) {
        int n=64;
        for(int i=0;i<n;++i) sumSharedDelaySends(ptrs,levels,start+i,sendL[i],sendR[i]);
        delay.process(sendL.data(),sendR.data(),wetL.data(),wetR.data(),n,120);
        for(int i=0;i<n;++i) {
            assert(std::isfinite(wetL[i]) && std::isfinite(wetR[i]));
            assert(wetL[i]==0.0f && wetR[i]==0.0f); // 250ms impulse cannot return in this segment
        }
    }
    // Delay continues on silence after send disappears; reset explicitly clears tail.
    assert(delay.prepare(48000,13000));
    delay.setTimeMs(1); delay.setEnabled(true); delay.setFeedback(0.5f); delay.setWet(1);
    std::vector<float> input(256),out(256),outR(256); input[127]=1;
    delay.process(input.data(),input.data(),out.data(),outR.data(),256,120);
    std::vector<float> silence(256),tail(256),tailR(256);
    bool returned=false;
    for(int i=0;i<10;++i) { delay.process(silence.data(),silence.data(),tail.data(),tailR.data(),256,120);
        for(float x:tail) returned |= std::fabs(x)>1e-5f; }
    assert(returned);
    delay.requestReset(); delay.process(silence.data(),silence.data(),tail.data(),tailR.data(),256,120);
    for(float x:tail) assert(std::fabs(x)<1e-7f);

    // Maximum accepted rate reset is logical O(1); reset callback does not
    // traverse the multi-million-byte stereo ring.
    SharedStereoDelay highRate; assert(highRate.prepare(384000,256));
    highRate.setEnabled(true); highRate.setTimeMs(2000); highRate.setWet(1);
    std::vector<float> one(1,0),oneOut(1),oneOutR(1);
    highRate.requestReset();
    highRate.process(one.data(),one.data(),oneOut.data(),oneOutR.data(),1,120);
    assert(oneOut[0]==0 && oneOutR[0]==0);
    std::puts("shared_delay_routing_test: PASS");
}
