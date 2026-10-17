#include "audio/SpectrumCaptureRing.h"
#include <cassert>
#include <cstdint>
#include <vector>

int main() {
    piano::SpectrumCaptureRing<8> ring;
    float in[20];
    for (int i=0;i<10;++i) { in[2*i]=float(i); in[2*i+1]=float(-i); }
    assert(!ring.write(in, 10));
    assert(ring.dropped()==2);
    float out[16];
    assert(ring.read(out, 3)==3);
    for(int i=0;i<3;++i) { assert(out[2*i]==float(i)); assert(out[2*i+1]==float(-i)); }
    assert(!ring.write(in+6, 7));
    assert(ring.read(out, 8)==8);
    for(int i=0;i<5;++i) { assert(out[2*i]==float(i+3)); assert(out[2*i+1]==float(-(i+3))); }
    ring.clear();
    assert(ring.read(out, 8)==0);
    assert(ring.write(in, 2));
    assert(ring.read(out, 8)==2);
}
