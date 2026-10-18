#include "effects/InstrumentEffectManager.h"
#include "effects/lsp/LspEffectIds.h"
#include <array>
#include <cassert>
#include <cmath>
#include <memory>

using namespace piano;

namespace {
bool failSlotZero = false;
class FakeEffect final : public AudioEffect {
public:
    explicit FakeEffect(bool usable = true) : usable(usable) {}
    bool prepare(double rate, int) override { sampleRate=rate; available = usable; return usable; }
    void process(float* l, float* r, int n) noexcept override {
        for (int i=0;i<n;++i) { l[i] += value; r[i] += value; }
    }
    void setParameter(uint32_t, float v) noexcept override { value=v; }
    float getParameter(uint32_t) const noexcept override { return value; }
    void setBypassed(bool b) noexcept override { bypassed=b; }
    bool isBypassed() const noexcept override { return bypassed; }
    const char* stableId() const noexcept override { return "fake.eq"; }
    bool isAvailable() const noexcept override { return available; }
    int getLatencyFrames() const noexcept override { return 0; }
    float value=0; double sampleRate=0; bool bypassed=true, available=false, usable=true;
};
std::unique_ptr<AudioEffect> factory(int slot) {
    if (slot == 0) { if(failSlotZero) return nullptr; return std::make_unique<FakeEffect>(); }
    if (slot == 1) return std::make_unique<FakeEffect>(false);
    return nullptr;
}
}

int main() {
    constexpr int frames=256;
    InstrumentEffectManager manager(factory);
    assert(manager.prepare(48000,frames));
    assert(manager.paramCount(0)>0);
    uint32_t id; float lo,hi,def; bool log,integer,toggled;
    assert(manager.paramInfo(0,0,id,lo,hi,def,log,integer,toggled));
    float read=0;
    assert(manager.getParameterChecked(4,0,id,read) && read==def);
    assert(manager.setParameterChecked(4,0,id,lo));
    assert(manager.getParameterChecked(4,0,id,read) && read==lo);
    assert(!manager.isAvailable(4,0)); // Registry not loaded; query remains lazy.

    std::array<std::array<float,frames*2>,16> data{};
    std::array<std::array<float,frames*2>,16> before{};
    std::array<float*,16> buses{};
    for(int c=0;c<16;++c) {
        buses[c]=data[c].data();
        for(int i=0;i<frames*2;++i) data[c][i]=before[c][i]=float(c+i+1);
    }
    // Default rack/slot state is off: every channel passes through unchanged.
    manager.process(buses.data(),frames);
    assert(data==before);

    assert(manager.instantiate(2,0) && manager.instantiate(7,0));
    assert(!manager.setEffectEnabledChecked(3,1,true));
    assert(manager.setParameterChecked(2,0,id,lo));
    assert(manager.setParameterChecked(7,0,id,hi));
    assert(manager.getParameter(2,0,id)==lo && manager.getParameter(7,0,id)==hi);
    manager.setRackEnabled(2,true); manager.setRackEnabled(7,true);
    assert(manager.setEffectEnabledChecked(2,0,true));
    assert(manager.setEffectEnabledChecked(7,0,true));

    for(int c=0;c<16;++c) {
        for(int i=0;i<frames*2;++i) data[c][i]=before[c][i]=float(c+i+1);
    }
    // Unavailable slot and disabled racks are exact passthrough while other
    // channels have active effects.
    manager.process(buses.data(),frames);
    for(int c=0;c<16;++c) if(c!=2 && c!=7) assert(data[c]==before[c]);
    assert(data[2][2] == before[2][2]);
    assert(data[2][(frames-1)*2] >= before[2][(frames-1)*2]);

    // Failed replacement must retain old published instances and configuration.
    // Trigger failure on subsequent slot-zero creations via the shared factory flag.
    failSlotZero=true;
    assert(!manager.reprepareQuiescent(44100,frames));
    assert(manager.prepareStatus()==InstrumentEffectManager::PrepareStatus::AllocationFailed);
    assert(manager.getParameter(2,0,id)==lo && manager.getParameter(7,0,id)==hi);
    failSlotZero=false;
    assert(manager.reprepareQuiescent(44100,frames));
    assert(manager.getParameter(2,0,id)==lo);
    assert(manager.getParameter(2,0,id)==lo && manager.getParameter(7,0,id)==hi);
    assert(manager.rackEnabled(2) && manager.rackEnabled(7));
    assert(manager.effectEnabled(2,0) && manager.effectEnabled(7,0));
    for(int c=0;c<16;++c) for(int i=0;i<frames*2;++i) data[c][i]=before[c][i]=float(c+i+1);
    manager.process(buses.data(),frames);
    for(int c=0;c<16;++c) if(c!=2 && c!=7) assert(data[c]==before[c]);
    assert(data[2][2] == before[2][2]);

    manager.setEffectEnabled(2,0,false);
    before=data;
    manager.process(buses.data(),frames);
    assert(data[2][0] <= before[2][0]);
    assert(data[2][0] >= before[2][0]-lo);
    manager.setRackEnabled(7,false);
    before=data;
    manager.process(buses.data(),frames);
    assert(data[7][0] >= before[7][0]-hi);
    assert(data[7][0] <= before[7][0]+hi);
    for(int c=0;c<16;++c) if(c!=7) assert(data[c]==before[c]);
}
