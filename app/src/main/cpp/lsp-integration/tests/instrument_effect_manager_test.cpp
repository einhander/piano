#include "effects/InstrumentEffectManager.h"
#include "effects/lsp/LspEffectIds.h"
#include "effects/lsp/InstrumentEffectRouting.h"
#include <array>
#include <cassert>
#include <cmath>
#include <memory>
#include <thread>
#include <atomic>
#include <iostream>

using namespace piano;

namespace {
bool failSlotZero = false;
int fakeClipperLatency = 2;
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
class LatencyEffect final : public AudioEffect {
public:
 explicit LatencyEffect(int latency):latency(latency){}
 bool prepare(double,int) override { return available=true; }
 void process(float* l,float* r,int n) noexcept override {
  for(int i=0;i<n;++i){float a=left[(cursor-latency+128)%128],b=right[(cursor-latency+128)%128];left[cursor]=l[i];right[cursor]=r[i];l[i]=a+.25f;r[i]=b+.25f;cursor=(cursor+1)%128;}
 }
 void setParameter(uint32_t,float) noexcept override {} float getParameter(uint32_t)const noexcept override{return 0;}
 void setBypassed(bool b) noexcept override{bypassed=b;} bool isBypassed()const noexcept override{return bypassed;}
 const char* stableId()const noexcept override{return lsp::kIdClipper;} bool isAvailable()const noexcept override{return available;}
 int getLatencyFrames()const noexcept override{return latency;}
 int latency,cursor=0; float left[128]{},right[128]{}; bool available=false,bypassed=true;
};
std::unique_ptr<AudioEffect> factory(int slot) {
    if (slot == 0) { if(failSlotZero) return nullptr; return std::make_unique<FakeEffect>(); }
    if (slot == 1) return std::make_unique<FakeEffect>(false);
    if (slot == lsp::kSlotClipper) return std::make_unique<LatencyEffect>(fakeClipperLatency);
     if (slot >= lsp::kSlotFilter && slot < lsp::kInstrumentEffectCount) return std::make_unique<FakeEffect>();
     return nullptr;
}
}

int main() {
    static_assert(sizeof(lsp::kBindings)/sizeof(lsp::kBindings[0]) == 3, "master catalog identity");
    assert(lsp::effectSlotForId(lsp::kIdParametricEq)==0);
    assert(lsp::effectSlotForId(lsp::kIdCompressor)==1);
    assert(lsp::effectSlotForId(lsp::kIdLimiter)==2);
    assert(lsp::effectSlotForId(lsp::kIdChorus)==3);
    assert(lsp::effectSlotForId(lsp::kIdClipper)==4);
    assert(lsp::effectSlotForId(lsp::kIdFilter)==5);
    assert(lsp::effectSlotForId(lsp::kIdPhaser)==6);
    assert(lsp::effectSlotForId(lsp::kIdFlanger)==7);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdParametricEq)==0);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdCompressor)==1);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdLimiter)==2);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdChorus)==3);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdClipper)==4);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdFilter)==5);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdPhaser)==6);
    assert(lsp::instrumentEffectSlotForId(lsp::kIdFlanger)==7);
    assert(lsp::instrumentEffectSlotForId("unknown") == -1);
    assert(lsp::instrumentEffectSlotForId(nullptr) == -1);
    assert(lsp::instrumentBindingForSlot(3) && !lsp::bindingForSlot(3));
    for(int slot=5;slot<8;++slot) assert(lsp::instrumentBindingForSlot(slot));
    constexpr int frames=256;
    auto managerStorage = std::make_unique<InstrumentEffectManager>(factory);
    auto& manager = *managerStorage;
    std::cerr << "InstrumentEffectManager sizeof=" << sizeof(InstrumentEffectManager) << " bytes; heap fixture\n";
    assert(manager.prepare(48000,frames));
    assert(manager.paramCount(0)>0);
    uint32_t id; float lo,hi,def; bool log,integer,toggled;
    assert(manager.paramInfo(0,0,id,lo,hi,def,log,integer,toggled));
    float read=0;
    assert(manager.getParameterChecked(4,0,id,read) && read==def);
    assert(manager.setParameterChecked(4,0,id,lo));
    assert(manager.getParameterChecked(4,0,id,read) && read==lo);
    assert(!manager.isAvailable(4,0)); // Registry not loaded; query remains lazy.

    std::array<std::array<float,frames*2>,kInstrumentChannelCount> data{};
    std::array<std::array<float,frames*2>,kInstrumentChannelCount> before{};
    std::array<float*,kInstrumentChannelCount> buses{};
    for(int c=0;c<kInstrumentChannelCount;++c) {
        buses[c]=data[c].data();
        for(int i=0;i<frames*2;++i) data[c][i]=before[c][i]=float(c+i+1);
    }
    // Default rack/slot state is off: every channel passes through unchanged.
    manager.process(buses.data(),frames);
    assert(data==before);

    assert(manager.instantiate(2,0) && manager.instantiate(7,0));
    assert(manager.instantiate(127,0));
    assert(!manager.instantiate(128,0));
    assert(!manager.setEffectEnabledChecked(3,1,true));
    assert(manager.setParameterChecked(2,0,id,lo));
    assert(manager.setParameterChecked(7,0,id,hi));
    assert(manager.getParameter(2,0,id)==lo && manager.getParameter(7,0,id)==hi);
    manager.setRackEnabled(2,true); manager.setRackEnabled(7,true);
    assert(manager.setEffectEnabledChecked(2,0,true));
    assert(manager.setEffectEnabledChecked(7,0,true));

    for(int c=0;c<kInstrumentChannelCount;++c) {
        for(int i=0;i<frames*2;++i) data[c][i]=before[c][i]=float(c+i+1);
    }
    // Unavailable slot and disabled racks are exact passthrough while other
    // channels have active effects.
    manager.process(buses.data(),frames);
    for(int c=0;c<kInstrumentChannelCount;++c) if(c!=2 && c!=7) assert(data[c]==before[c]);
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
    for(int c=0;c<kInstrumentChannelCount;++c) for(int i=0;i<frames*2;++i) data[c][i]=before[c][i]=float(c+i+1);
    manager.process(buses.data(),frames);
    for(int c=0;c<kInstrumentChannelCount;++c) if(c!=2 && c!=7) assert(data[c]==before[c]);
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
    for(int c=0;c<kInstrumentChannelCount;++c) if(c!=7) assert(data[c]==before[c]);
    assert(manager.setParameterChecked(127,0,id,hi));
    assert(manager.rackEnabled(127)==false);
    assert(manager.getParameter(127,0,id)==hi);
    assert(manager.setParameterChecked(0,0,id,lo));
    assert(manager.getParameter(0,0,id)==lo && manager.getParameter(16,0,id)==0.0f);
    assert(manager.resetChannelRange(127,1));
    assert(!manager.rackEnabled(127) && !manager.effectEnabled(127,0));
    assert(manager.getParameterChecked(127,0,id,read) && read==def);
    assert(manager.getParameter(7,0,id)==hi);
    assert(!manager.resetChannelRange(127,2));
    assert(!manager.resetChannelRange(-1,1));
    assert(manager.resetChannelRange(128,0));

    // Clipper alignment ring is warm before publication, preserves old all-OFF
    // samples exactly, and stays active when rack/slot bypassed after arming.
    auto alignedStorage = std::make_unique<InstrumentEffectManager>(factory); auto& aligned = *alignedStorage;
    assert(aligned.prepare(48000,frames));
    std::array<float,frames*2> one{}; float* oneBus[kInstrumentChannelCount]{}; oneBus[0]=one.data();
    for(int block=0;block<3;++block) {
        for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(block*frames+i+1);
        auto orig=one; aligned.process(oneBus,frames); assert(one==orig);
    }
    assert(aligned.instantiate(0,lsp::kSlotClipper));
    assert(aligned.effectLatencyFrames(0,lsp::kSlotClipper)==2);
    aligned.setRackEnabled(0,false);
    for(int block=3;block<5;++block) {
        for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(block*frames+i+1);
        auto orig=one; aligned.process(oneBus,frames); assert(one==orig);
    }
    assert(aligned.reprepareQuiescent(48000,frames)); // inactive restore must not arm compensation
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(5*frames+i+1);
    auto disabledOrig=one; aligned.process(oneBus,frames); assert(one==disabledOrig);
    aligned.setRackEnabled(0,true);
    assert(aligned.setEffectEnabledChecked(0,lsp::kSlotClipper,true));
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(6*frames+i+1);
    aligned.process(oneBus,frames);
    assert(one[0]!=0.0f);
    for(int i=frames-8;i<frames;++i) assert(std::fabs(one[i*2]-(float(6*frames+i-1)+.25f))<.001f);
    assert(aligned.setEffectEnabledChecked(0,lsp::kSlotClipper,false));
    for(int block=7;block<=8;++block) {
        for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(block*frames+i+1);
        aligned.process(oneBus,frames);
    }
    for(int i=frames-8;i<frames;++i) assert(std::fabs(one[i*2]-float(8*frames+i-1))<.001f);
    assert(aligned.setEffectEnabledChecked(0,lsp::kSlotClipper,true));
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(9*frames+i+1);
    aligned.process(oneBus,frames);
    assert(std::fabs(one[0]-(float(9*frames-1)+.25f/240.0f))<.002f); // DSP pipeline stayed current while bypassed
    for(int i=frames-8;i<frames;++i) assert(std::fabs(one[i*2]-(float(9*frames+i-1)+.25f))<.001f);
    aligned.setRackEnabled(0,false);
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(10*frames+i+1);
    aligned.process(oneBus,frames);
    for(int i=frames-8;i<frames;++i) assert(std::fabs(one[i*2]-float(10*frames+i-1))<.001f);

    auto firstUseStorage = std::make_unique<InstrumentEffectManager>(factory); auto& firstUse = *firstUseStorage; assert(firstUse.prepare(48000,frames));
    assert(firstUse.instantiate(0,lsp::kSlotClipper));
    firstUse.setRackEnabled(0,true); assert(firstUse.setEffectEnabledChecked(0,lsp::kSlotClipper,true));
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(i+1);
    firstUse.process(oneBus,frames);
    assert(one[0]==1.0f && one[1]==1.0f); // hold dry timeline until delay history valid
    for(float sample:one) assert(std::isfinite(sample));

    auto instantEnableStorage = std::make_unique<InstrumentEffectManager>(factory); auto& instantEnable = *instantEnableStorage; assert(instantEnable.prepare(48000,frames));
    for(int block=0;block<3;++block) {
        for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(block*frames+i+1);
        instantEnable.process(oneBus,frames);
    }
    assert(instantEnable.instantiate(0,lsp::kSlotClipper)); // published disabled
    instantEnable.setRackEnabled(0,true);
    assert(instantEnable.setEffectEnabledChecked(0,lsp::kSlotClipper,true)); // no callback between publish/enable
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(3*frames+i+1);
    instantEnable.process(oneBus,frames);
    assert(one[0]==3*frames+1 && one[1]==3*frames+1);
    for(float sample:one) assert(std::isfinite(sample));

    fakeClipperLatency=128;
    auto maxDelayStorage = std::make_unique<InstrumentEffectManager>(factory); auto& maxDelay = *maxDelayStorage; assert(maxDelay.prepare(48000,frames));
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(i+1);
    maxDelay.process(oneBus,frames); assert(maxDelay.instantiate(0,lsp::kSlotClipper));
    maxDelay.setRackEnabled(0,true); assert(maxDelay.setEffectEnabledChecked(0,lsp::kSlotClipper,true));
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(frames+i+1);
    maxDelay.process(oneBus,frames);
    for(int i=0;i<frames;++i) one[i*2]=one[i*2+1]=float(2*frames+i+1);
    maxDelay.process(oneBus,frames);
    for(int i=frames-8;i<frames;++i) assert(std::fabs(one[i*2]-(float(2*frames+i+1-128)+.25f))<.001f);

    fakeClipperLatency=129;
    auto invalidStorage = std::make_unique<InstrumentEffectManager>(factory); auto& invalid = *invalidStorage; assert(invalid.prepare(48000,frames));
    assert(!invalid.instantiate(0,lsp::kSlotClipper));
    fakeClipperLatency=2;

    auto stressStorage = std::make_unique<InstrumentEffectManager>(factory); auto& stress = *stressStorage; assert(stress.prepare(48000,frames));
    assert(stress.instantiate(0,0));
    std::atomic<bool> done{false};
    std::thread rebuild([&]{
        for(int i=0;i<80;++i) assert(stress.reprepareQuiescent(48000,frames));
        done.store(true,std::memory_order_release);
    });
    int reads=0;
    do {
        float value=0; assert(stress.getParameterChecked(0,0,id,value));
        (void)stress.getParameter(0,0,id);
        assert(stress.isAvailable(0,0));
        assert(stress.effectLatencyFrames(0,0)==0);
        ++reads;
    } while(!done.load(std::memory_order_acquire) || reads<1000);
    rebuild.join();
}
