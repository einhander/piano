#include "../../effects/InstrumentDelayBank.h"
#include <cassert>
#include <cmath>
#include <vector>

int main() {
    InstrumentDelayBank bank;
    assert(!bank.setParameter(0,0,1));
    assert(bank.prepare(48000,512));
    assert(bank.getParameter(1,0)==0 && bank.allocatedCount()==0);
    assert(bank.setParameter(127,2,.5f) && bank.allocatedCount()==0);
    assert(!bank.setParameter(128,0,1));
    assert(bank.setParameter(0,0,1));
    assert(bank.allocatedCount()==1);
    assert(bank.setParameter(0,1,1));
    assert(bank.setParameter(0,2,.5f));
    assert(bank.setParameter(0,3,1));
    assert(!bank.setParameter(128,0,1));
    assert(!bank.setParameter(0,1,0));
    assert(!bank.setParameter(0,9,0));
    assert(bank.setParameter(1,1,20));
    std::vector<float> a(512*2),b(512*2); a[0]=a[1]=1;
    float* buses[InstrumentDelayBank::kChannels]{}; buses[0]=a.data(); buses[1]=b.data();
    bank.process(buses,512,120);
    bool heardA=false,heardB=false;
    for(int i=1;i<512;++i){heardA|=std::fabs(a[i*2])>1e-5f;heardB|=std::fabs(b[i*2])>1e-5f;}
    assert(heardA && !heardB);
    assert(bank.prepare(44100,512));
    assert(bank.getParameter(0,0)==1 && bank.getParameter(1,0)==0);
    assert(bank.getParameter(127,2)==.5f && bank.allocatedCount()==1);
    assert(bank.resetChannelRange(0,1));
    assert(bank.allocatedCount()==0 && bank.getParameter(0,0)==0 && bank.getParameter(0,1)==250);
    assert(bank.getParameter(127,2)==.5f);
    assert(bank.setParameter(127,0,1) && bank.allocatedCount()==1);
    assert(bank.resetChannelRange(127,1) && bank.allocatedCount()==0);
    InstrumentDelayBank invalid; assert(!invalid.prepare(0,512));
}
