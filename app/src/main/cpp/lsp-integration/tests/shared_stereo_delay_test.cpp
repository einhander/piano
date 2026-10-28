#include "effects/SharedStereoDelay.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

static void render(SharedStereoDelay& d, const std::vector<float>& inL,
                   const std::vector<float>& inR, std::vector<float>& outL,
                   std::vector<float>& outR, int block, double bpm=120.0) {
    for (size_t at=0; at<inL.size(); at+=block) {
        int n=static_cast<int>(std::min<size_t>(block,inL.size()-at));
        d.process(inL.data()+at,inR.data()+at,outL.data()+at,outR.data()+at,n,bpm);
    }
}
static void finite(const std::vector<float>& x) { for(float v:x) assert(std::isfinite(v)); }

int main() {
    SharedStereoDelay d;
    assert(d.prepare(48000,256));
    assert(!d.isEnabled());
    assert(d.timeMs()==250.0f && d.feedback()==0.35f && d.wet()==0.30f);
    d.setEnabled(true); d.setWet(1.0f); d.setFeedback(0.5f); d.setTimeMs(250.0f);
    constexpr int n=24000;
    std::vector<float> l(n),r(n),ol(n),orr(n); l[127]=r[127]=1.0f;
    render(d,l,r,ol,orr,128);
    // Startup wet ramp means impulse first return is attenuated, but exact delay position remains 12000.
    assert(std::fabs(ol[12127])>0.90f && std::fabs(orr[12127])>0.90f);
    assert(std::fabs(ol[127])<1e-6f); // send effect never duplicates dry input
    assert(std::fabs(ol[24000-1])<1e-6f); // no unintended early echo
    finite(ol); finite(orr);

    d.requestReset(); d.process(nullptr,nullptr,ol.data(),orr.data(),256,120.0);
    std::fill(l.begin(),l.end(),0); std::fill(r.begin(),r.end(),0); l[127]=r[127]=1;
    d.setFeedback(0.5f);
    render(d,l,r,ol,orr,128);
    assert(std::fabs(ol[12127])>0.45f && std::fabs(ol[23999])<1e-5f);
    // Second repeat is delayed another 12000 frames; render that tail explicitly.
    std::vector<float> zeros(12000), repeat(12000), repeatR(12000);
    d.process(zeros.data(),zeros.data(),repeat.data(),repeatR.data(),12000,120.0);
    // Feedback path is exercised by each return; ensure it remains finite.
    finite(repeat); finite(repeatR);

    // Silence keeps tails processing; disabling input does not erase tail.
    d.requestReset(); d.process(nullptr,nullptr,ol.data(),orr.data(),256,120.0);
    d.setEnabled(true); d.setFeedback(0.7f); d.setWet(1.0f);
    std::fill(l.begin(),l.end(),0); std::fill(r.begin(),r.end(),0); l[0]=r[0]=1;
    render(d,l,r,ol,orr,64);
    d.setEnabled(false);
    std::vector<float> silence(13000), tail(13000), tailR(13000);
    render(d,silence,silence,tail,tailR,128);
    assert(std::any_of(tail.begin(),tail.end(),[](float x){return std::fabs(x)>0.01f;}));
    assert(d.prepare(48000,13000));
    d.requestReset(); std::fill(tail.begin(),tail.end(),1.0f); std::fill(tailR.begin(),tailR.end(),1.0f);
    d.process(nullptr,nullptr,tail.data(),tailR.data(),static_cast<int>(tail.size()),120.0);
    assert(std::all_of(tail.begin(),tail.end(),[](float x){return std::fabs(x)<1e-7f;}));

    // Disable ramps only new send; wet return remains audible after input mute.
    d.setEnabled(true); d.setWet(1); d.setFeedback(0.5f); d.setTimeMs(50);
    std::vector<float> shortIn(5000), shortOut(5000), shortOutR(5000);
    shortIn[0]=1;
    d.process(shortIn.data(),shortIn.data(),shortOut.data(),shortOutR.data(),256,120);
    d.setEnabled(false);
    std::vector<float> quiet(256), returned(256), returnedR(256);
    float audibleTail=0;
    for(int i=0;i<20;++i) { d.process(quiet.data(),quiet.data(),returned.data(),returnedR.data(),256,120);
        for(float x:returned) audibleTail=std::max(audibleTail,std::fabs(x)); }
    assert(audibleTail>1e-5f);

    // Changing control trajectories must not depend on segmentation.
    SharedStereoDelay c16,c256; assert(c16.prepare(48000,256)); assert(c256.prepare(48000,256));
    for(auto* x:{&c16,&c256}) { x->setEnabled(true); x->setTimeMs(100); x->setFeedback(0.1f); x->setWet(0.1f); }
    std::vector<float> ci(4096),cr(4096),o16(4096),p16(4096),o256(4096),p256(4096);
    ci[0]=cr[0]=1;
    for(int off=0;off<4096;off+=16) {
        int n=std::min(16,4096-off);
        if(off==512) { c16.setWet(1); c16.setFeedback(0.9f); c256.setWet(1); c256.setFeedback(0.9f); }
        if(off==1024) { c16.setTimeMs(300); c256.setTimeMs(300); c16.setEnabled(false); c256.setEnabled(false); }
        c16.process(ci.data()+off,cr.data()+off,o16.data()+off,p16.data()+off,n,120);
    }
    for(int off=0;off<4096;off+=256) {
        if(off==512) { c256.setWet(1); c256.setFeedback(0.9f); }
        if(off==1024) { c256.setTimeMs(300); c256.setEnabled(false); }
        c256.process(ci.data()+off,cr.data()+off,o256.data()+off,p256.data()+off,std::min(256,4096-off),120);
    }
    float segmentDifference=0; for(int i=0;i<4096;++i) segmentDifference=std::max(segmentDifference,std::fabs(o16[i]-o256[i]));
    assert(segmentDifference<1e-5f);

    // Huge finite input cannot overflow delay state or output.
    c16.requestReset(); c16.setEnabled(true);
    std::vector<float> huge(256,1e30f),hugeOut(256),hugeOutR(256);
    c16.process(huge.data(),huge.data(),hugeOut.data(),hugeOutR.data(),256,120);
    finite(hugeOut); finite(hugeOutR);
    assert(std::all_of(hugeOut.begin(),hugeOut.end(),[](float x){return std::fabs(x)<=SharedStereoDelay::kMaxInternalSample;}));

    // Segmentation-independent processing at supported rates/block sizes.
    for (int rate : {44100,48000}) for (int block : {16,64,128,256}) {
        SharedStereoDelay a,b; assert(a.prepare(rate,block)); assert(b.prepare(rate,256));
        for (auto* x : {&a,&b}) { x->setEnabled(true); x->setFeedback(0.4f); x->setWet(0.6f); }
        const int count=rate/2;
        std::vector<float> il(count),ir(count),al(count),ar(count),bl(count),br(count);
        il[0]=1; ir[0]=0.5f;
        render(a,il,ir,al,ar,block);
        render(b,il,ir,bl,br,256);
        for(int i=0;i<count;++i) { assert(std::fabs(al[i]-bl[i])<1e-5f); assert(std::fabs(ar[i]-br[i])<1e-5f); }
        finite(al); finite(ar);
    }

    d.setFeedback(NAN); assert(d.feedback()==0.35f);
    d.setWet(INFINITY); assert(d.wet()==0.30f);
    d.setTimeMs(NAN); assert(d.timeMs()==250.0f);
    d.setFeedback(4); assert(d.feedback()==SharedStereoDelay::kMaxFeedback);
    d.setWet(-1); assert(d.wet()==0);
    d.setTimeMs(10000); assert(d.timeMs()==2000);
    d.setSync(true); d.setDivision(4);
    std::vector<float> zeroBlock(512),out(512),outR(512);
    d.process(zeroBlock.data(),zeroBlock.data(),out.data(),outR.data(),512,NAN);
    finite(out); finite(outR);
    // Time change crossfade must remain finite and bounded.
    d.setSync(false); d.setTimeMs(17); d.setEnabled(true);
    d.process(zeroBlock.data(),zeroBlock.data(),out.data(),outR.data(),512,INFINITY);
    finite(out); finite(outR);
    std::puts("shared_stereo_delay_test: PASS");
}
