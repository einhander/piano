#include "InstrumentEffectManager.h"
#include "lsp/LspEffectFactory.h"
#include "lsp/LspEffectIds.h"
#include "ladspa/LadspaRegistry.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <cmath>
#include <thread>

namespace piano {

InstrumentEffectManager::InstrumentEffectManager(EffectFactory factory) noexcept : mFactory(factory) {
    for(auto& x:mRackEnabled)x.store(false,std::memory_order_relaxed);
    for(auto& channel:mEffectEnabled)for(auto& x:channel)x.store(false,std::memory_order_relaxed);
    for(auto& channel:mPublished)for(auto& x:channel)x.store(nullptr,std::memory_order_relaxed);
    for(auto& channel:mPendingSet)for(auto& effect:channel)for(auto& x:effect)x.store(false,std::memory_order_relaxed);
    for(auto& channel:mPending)for(auto& effect:channel)for(auto& x:effect)x.store(0.0f,std::memory_order_relaxed);
    mClipperTimelineActive.fill(false);
    mLeft.fill(nullptr); mRight.fill(nullptr);
}

InstrumentEffectManager::~InstrumentEffectManager() {
    // Owner must stop/quiesce audio before destruction. unique_ptr effects and
    // all scratch allocations are then reclaimed exactly once.
    for(int c=0;c<kChannels;++c){std::free(mLeft[c]);std::free(mRight[c]);}
}

bool InstrumentEffectManager::prepare(double sampleRate, int maxFrames) {
    if (sampleRate <= 0.0 || maxFrames <= 0) { mPrepareStatus.store(PrepareStatus::InvalidArguments,std::memory_order_release); return false; }
    // One-shot preparation. Reject resize/rate changes rather than freeing or
    // mutating scratch/plugin state potentially observed by the callback.
    if(mPrepared) {
        const bool same=sampleRate==mSampleRate && maxFrames==mMaxFrames;
        mPrepareStatus.store(same?PrepareStatus::Ready:PrepareStatus::ReprepareRequiresQuiescence,std::memory_order_release);
        return same;
    }
    std::array<float*,kChannels> left{},right{};
    for (int c=0;c<kChannels;++c) {
        left[c]=static_cast<float*>(std::malloc(sizeof(float)*maxFrames));
        right[c]=static_cast<float*>(std::malloc(sizeof(float)*maxFrames));
        if(!left[c]||!right[c]) { for(int i=0;i<=c;++i){std::free(left[i]);std::free(right[i]);} mPrepareStatus.store(PrepareStatus::AllocationFailed,std::memory_order_release); return false; }
    }
    mLeft=left; mRight=right;
    for (auto& channel : mWetMix) channel.fill(0.0f);
    for (auto& row:mClipperDryL) row.fill(0.0f);
    for (auto& row:mClipperDryR) row.fill(0.0f);
    mClipperDryCursor.fill(0); mClipperHistoryCount.fill(0); mClipperLatency.fill(0); mClipperTimelineActive.fill(false); mClipperAlignMix.fill(0.0f);
    mSampleRate = sampleRate;
    mMaxFrames = maxFrames;
    mPrepared=true;
    mPrepareStatus.store(PrepareStatus::Ready,std::memory_order_release);
    return true;
}

bool InstrumentEffectManager::reprepareQuiescent(double sampleRate, int maxFrames) {
    if (sampleRate <= 0.0 || maxFrames <= 0 || !std::isfinite(sampleRate)) {
        mPrepareStatus.store(PrepareStatus::InvalidArguments, std::memory_order_release);
        return false;
    }
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    while (mControlBusy.test_and_set(std::memory_order_acquire)) std::this_thread::yield();
    struct Unlock { std::atomic_flag& f; ~Unlock(){f.clear(std::memory_order_release);} } unlock{mControlBusy};
    // Stage every fallible operation before changing published state.
    std::array<float*,kChannels> left{},right{};
    for(int c=0;c<kChannels;++c) {
        left[c]=static_cast<float*>(std::malloc(sizeof(float)*maxFrames));
        right[c]=static_cast<float*>(std::malloc(sizeof(float)*maxFrames));
        if(!left[c]||!right[c]) {
            for(int i=0;i<kChannels;++i){std::free(left[i]);std::free(right[i]);}
            mPrepareStatus.store(PrepareStatus::AllocationFailed,std::memory_order_release);
            return false;
        }
    }
    std::array<std::array<std::unique_ptr<AudioEffect>,kEffects>,kChannels> staged{};
    std::array<std::array<bool,kEffects>,kChannels> rebuild{};
    for(int c=0;c<kChannels;++c) for(int s=0;s<kEffects;++s) {
        rebuild[c][s]=static_cast<bool>(mEffects[c][s]);
    }
    for(int c=0;c<kChannels;++c) for(int s=0;s<kEffects;++s) if(rebuild[c][s]) {
        std::unique_ptr<AudioEffect> candidate=mFactory?mFactory(s):std::unique_ptr<AudioEffect>(lsp::LspEffectFactory::create(s));
        if(!candidate || !candidate->prepare(sampleRate,maxFrames) || !candidate->isAvailable()) {
            for(int i=0;i<kChannels;++i){std::free(left[i]);std::free(right[i]);}
            mPrepareStatus.store(PrepareStatus::AllocationFailed,std::memory_order_release);
            return false;
        }
        if(s==lsp::kSlotClipper && (candidate->getLatencyFrames()<0 || candidate->getLatencyFrames()>kClipperDelayCapacity)) {
            for(int i=0;i<kChannels;++i){std::free(left[i]);std::free(right[i]);}
            mPrepareStatus.store(PrepareStatus::AllocationFailed,std::memory_order_release); return false;
        }
        int count=0; const auto* ds=lsp::paramDescriptors(s,count);
        for(int i=0;i<count;++i) if(ds[i].id<512 && mPendingSet[c][s][ds[i].id].load(std::memory_order_acquire))
            candidate->setParameter(ds[i].id,mPending[c][s][ds[i].id].load(std::memory_order_relaxed));
        candidate->setBypassed(false);
        staged[c][s]=std::move(candidate);
    }
    for(int c=0;c<kChannels;++c) for(int s=0;s<kEffects;++s)
        mPublished[c][s].store(nullptr,std::memory_order_release);
    for(auto& row:mEffects) for(auto& effect:row) effect.reset();
    for(int c=0;c<kChannels;++c){std::free(mLeft[c]);std::free(mRight[c]);}
    mLeft=left; mRight=right;
    for(auto& row:mClipperDryL) row.fill(0.0f);
    for(auto& row:mClipperDryR) row.fill(0.0f);
    mClipperDryCursor.fill(0); mClipperHistoryCount.fill(0); mClipperLatency.fill(0); mClipperAlignMix.fill(0.0f);
    mClipperWarmEffect.fill(nullptr); mClipperWarmFrames.fill(0);
    for(int c=0;c<kChannels;++c) for(int s=0;s<kEffects;++s) {
        mEffects[c][s]=std::move(staged[c][s]);
        if(s==lsp::kSlotClipper && mEffects[c][s]) {
            mClipperLatency[c]=mEffects[c][s]->getLatencyFrames();
        }
        mPublished[c][s].store(mEffects[c][s].get(),std::memory_order_release);
    }
    for(int c=0;c<kChannels;++c) if(mEffects[c][lsp::kSlotClipper])
        mClipperWarmEffect[c]=nullptr; // quiescent pointer replacement resets DSP warm-up
    for(auto& channel:mWetMix) channel.fill(0.0f);
    mSampleRate=sampleRate; mMaxFrames=maxFrames; mPrepared=true;
    mPrepareStatus.store(PrepareStatus::Ready,std::memory_order_release);
    return true;
}

bool InstrumentEffectManager::instantiate(int channel, int slot) {
    if (channel < 0 || channel >= kChannels || slot < 0 || slot >= kEffects || mMaxFrames <= 0) return false;
    if(mControlBusy.test_and_set(std::memory_order_acquire)) return false;
    struct Unlock { std::atomic_flag& f; ~Unlock(){f.clear(std::memory_order_release);} } unlock{mControlBusy};
    auto& effect = mEffects[channel][slot];
    if (effect) return effect->isAvailable();
    std::unique_ptr<AudioEffect> candidate = mFactory ? mFactory(slot)
        : std::unique_ptr<AudioEffect>(lsp::LspEffectFactory::create(slot));
    if (!candidate) return false;
    if (!candidate->prepare(mSampleRate, mMaxFrames) || !candidate->isAvailable()) return false;
    int count=0; const auto* descriptors=lsp::paramDescriptors(slot,count);
    for(int i=0;i<count;++i) if(descriptors[i].id < 512 && mPendingSet[channel][slot][descriptors[i].id].load())
        candidate->setParameter(descriptors[i].id,mPending[channel][slot][descriptors[i].id].load());
    candidate->setBypassed(false);
    const int clipperLatency=slot==lsp::kSlotClipper?candidate->getLatencyFrames():0;
    if(slot==lsp::kSlotClipper && (clipperLatency<0 || clipperLatency>kClipperDelayCapacity)) return false;
    effect = std::move(candidate);
    if (slot==lsp::kSlotClipper) {
        mClipperLatency[channel]=clipperLatency;
    }
    mPublished[channel][slot].store(effect.get(),std::memory_order_release);
    return true;
}
bool InstrumentEffectManager::rackEnabled(int channel) const noexcept { return channel>=0&&channel<kChannels&&mRackEnabled[channel].load(std::memory_order_acquire); }
bool InstrumentEffectManager::effectEnabled(int channel,int slot) const noexcept { return channel>=0&&channel<kChannels&&slot>=0&&slot<kEffects&&mEffectEnabled[channel][slot].load(std::memory_order_acquire); }
bool InstrumentEffectManager::setEffectEnabledChecked(int channel,int slot,bool enabled) {
    if(channel<0||channel>=kChannels||slot<0||slot>=kEffects)return false;
    if(enabled&&!instantiate(channel,slot))return false;
    setEffectEnabled(channel,slot,enabled);
    return true;
}
bool InstrumentEffectManager::setParameterChecked(int c,int s,uint32_t id,float v) noexcept { return setParameterBeforeInstantiate(c,s,id,v); }
bool InstrumentEffectManager::getParameterChecked(int c,int s,uint32_t id,float& value) const noexcept {
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    if(c<0||c>=kChannels||s<0||s>=kEffects)return false;
    int n=0; const auto* d=lsp::paramDescriptors(s,n); bool valid=false;
    for(int i=0;i<n;++i) if(d[i].id==id){valid=true;break;}
    if(!valid)return false;
    auto* e=mPublished[c][s].load(std::memory_order_acquire);
    if(e) { if(!e->isAvailable()) return false; value=e->getParameter(id); return true; }
    for(int i=0;i<n;++i) if(d[i].id==id) {
        value=mPendingSet[c][s][id].load(std::memory_order_acquire)
            ? mPending[c][s][id].load(std::memory_order_relaxed) : d[i].defaultValue;
        return true;
    }
    return false;
}
int InstrumentEffectManager::paramCount(int s) const noexcept { int n=0; if(s>=0&&s<kEffects)lsp::paramDescriptors(s,n); return n; }
bool InstrumentEffectManager::paramInfo(int s,int i,uint32_t& id,float& lo,float& hi,float& def,bool& log,bool& integer,bool& toggled) const noexcept {
    int n=0; const auto* d=(s>=0&&s<kEffects)?lsp::paramDescriptors(s,n):nullptr;
    if (!d || i < 0 || i >= n) return false;
    id = d[i].id;
    lo = d[i].minValue;
    hi = d[i].maxValue;
    def = d[i].defaultValue;
    log = d[i].logarithmic;
    integer = d[i].integer;
    toggled = d[i].toggled;
    return true;
}
const char* InstrumentEffectManager::paramName(int s,int i) const noexcept { uint32_t id;float a,b,c;bool d,e,f;return paramInfo(s,i,id,a,b,c,d,e,f)?lsp::paramDisplayName(id):""; }
bool InstrumentEffectManager::setParameterBeforeInstantiate(int channel,int slot,uint32_t id,float value) noexcept {
    if(channel<0||channel>=kChannels||slot<0||slot>=kEffects||id>=512) return false;
    int count=0; const auto* ds=lsp::paramDescriptors(slot,count); bool valid=false;
    for(int i=0;i<count;++i) if(ds[i].id==id) { value=std::max(ds[i].minValue,std::min(ds[i].maxValue,value)); valid=true; break; }
    if(!valid) return false;
    if(mControlBusy.test_and_set(std::memory_order_acquire)) return false;
    struct Unlock { std::atomic_flag& f; ~Unlock(){f.clear(std::memory_order_release);} } unlock{mControlBusy};
    mPending[channel][slot][id].store(value,std::memory_order_relaxed); mPendingSet[channel][slot][id].store(true,std::memory_order_release);
    if(auto* e=mPublished[channel][slot].load(std::memory_order_acquire)) e->setParameter(id,value);
    return true;
}

void InstrumentEffectManager::setRackEnabled(int channel, bool enabled) noexcept {
    if (channel >= 0 && channel < kChannels) mRackEnabled[channel].store(enabled, std::memory_order_release);
}
void InstrumentEffectManager::setEffectEnabled(int channel, int slot, bool enabled) noexcept {
    if (channel < 0 || channel >= kChannels || slot < 0 || slot >= kEffects) return;
    mEffectEnabled[channel][slot].store(enabled,std::memory_order_release);
}
void InstrumentEffectManager::setParameter(int channel, int slot, uint32_t id, float value) noexcept {
    if (channel < 0 || channel >= kChannels || slot < 0 || slot >= kEffects) return;
    setParameterBeforeInstantiate(channel,slot,id,value);
}
float InstrumentEffectManager::getParameter(int channel, int slot, uint32_t id) const noexcept {
    if (channel < 0 || channel >= kChannels || slot < 0 || slot >= kEffects) return 0.0f;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    const auto* effect = mPublished[channel][slot].load(std::memory_order_acquire);
    return effect ? effect->getParameter(id) : 0.0f;
}
bool InstrumentEffectManager::isAvailable(int channel, int slot) const noexcept {
    if(channel<0||channel>=kChannels||slot<0||slot>=kEffects)return false;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    auto* e=mPublished[channel][slot].load(std::memory_order_acquire);
    if(e) return e->isAvailable();
    const auto* binding=slot<lsp::kMasterEffectCount?lsp::bindingForSlot(slot):lsp::instrumentBindingForSlot(slot);
    return binding && ladspa::LadspaRegistry::instance().findByLabel(binding->label)!=nullptr;
}
int InstrumentEffectManager::effectLatencyFrames(int channel,int slot) const noexcept {
    if(channel<0||channel>=kChannels||slot<0||slot>=kEffects)return 0;
    std::lock_guard<std::mutex> lifetimeLock(mLifetimeMutex);
    auto* e=mPublished[channel][slot].load(std::memory_order_acquire);
    return e&&e->isAvailable()?e->getLatencyFrames():0;
}

void InstrumentEffectManager::process(float* const buses[kChannels], int frames) noexcept {
    if (!buses || frames <= 0 || !mPrepared || frames>mMaxFrames) return;
    for (int channel = 0; channel < kChannels; ++channel) {
        float* bus = buses[channel];
        if (!bus) continue;
        // Continuously retain a short input history without modifying output.
        // Before first Clipper publication the complete rack remains bit-exact.
        for (int slot = 0; slot < kEffects; ++slot) {
            auto* effect = mPublished[channel][slot].load(std::memory_order_acquire);
            if (slot==lsp::kSlotClipper) {
                const bool published=effect && effect->isAvailable();
                const int latency=published?mClipperLatency[channel]:0;
                float mix=mWetMix[channel][slot];
                const bool effectiveEnable=mRackEnabled[channel].load(std::memory_order_acquire) &&
                    mEffectEnabled[channel][slot].load(std::memory_order_acquire) && published && !effect->isBypassed();
                if (effect != mClipperWarmEffect[channel]) {
                    mClipperWarmEffect[channel]=effect;
                    mClipperWarmFrames[channel]=0;
                }
                const int warmFramesBefore=mClipperWarmFrames[channel];
                if (published) {
                    for(int f=0;f<frames;++f){mLeft[channel][f]=bus[f*2];mRight[channel][f]=bus[f*2+1];}
                    effect->process(mLeft[channel],mRight[channel],frames);
                    mClipperWarmFrames[channel]=std::min(kClipperDelayCapacity,warmFramesBefore+frames);
                }
                if(effectiveEnable) mClipperTimelineActive[channel]=true;
                const float alignStep=static_cast<float>(1.0/(mSampleRate*0.005));
                const float wetTarget=(mClipperTimelineActive[channel] && effectiveEnable)?1.0f:0.0f;
                const float wetStep=static_cast<float>(1.0/(mSampleRate*0.005));
                int cursor=mClipperDryCursor[channel];
                for(int f=0;f<frames;++f) {
                    const float inL=bus[f*2], inR=bus[f*2+1];
                    const bool historyReady=latency==0 || mClipperHistoryCount[channel]>=latency;
                    int read=cursor-latency; if(read<0) read+=kClipperDelayCapacity;
                    const float delayedL=latency==0?inL:mClipperDryL[channel][read];
                    const float delayedR=latency==0?inR:mClipperDryR[channel][read];
                    mClipperDryL[channel][cursor]=inL; mClipperDryR[channel][cursor]=inR;
                    if(mClipperHistoryCount[channel]<kClipperDelayCapacity) ++mClipperHistoryCount[channel];
                    float& align=mClipperAlignMix[channel];
                    const bool pluginWarm=published && warmFramesBefore+f>=latency;
                    const bool alignReady=mClipperTimelineActive[channel] && historyReady && pluginWarm;
                    if(alignReady && align<1.0f) align=std::min(1.0f,align+alignStep);
                    const float dryL=inL+(delayedL-inL)*align, dryR=inR+(delayedR-inR)*align;
                    const float frameWetTarget=alignReady?wetTarget:0.0f;
                    if(mix<frameWetTarget) mix=std::min(frameWetTarget,mix+wetStep);
                    else if(mix>frameWetTarget) mix=std::max(frameWetTarget,mix-wetStep);
                    const float wetL=published?mLeft[channel][f]:dryL;
                    const float wetR=published?mRight[channel][f]:dryR;
                    bus[f*2]=dryL+(wetL-dryL)*mix;
                    bus[f*2+1]=dryR+(wetR-dryR)*mix;
                    if(++cursor==kClipperDelayCapacity)cursor=0;
                }
                mClipperDryCursor[channel]=cursor;
                mWetMix[channel][slot]=mix;
                continue;
            }
            if (!effect || !effect->isAvailable()) continue;
            float& mix=mWetMix[channel][slot];
            const float target=(mRackEnabled[channel].load(std::memory_order_acquire) && mEffectEnabled[channel][slot].load(std::memory_order_acquire) && !effect->isBypassed()) ? 1.0f : 0.0f;
            if (mix == 0.0f && target == 0.0f) continue;
            const int n=frames;
            for(int f=0;f<n;++f){mLeft[channel][f]=bus[f*2];mRight[channel][f]=bus[f*2+1];}
            effect->process(mLeft[channel],mRight[channel],n);
            const float step=static_cast<float>(1.0/(mSampleRate*0.005));
            for(int f=0;f<n;++f){
                if(mix<target) mix=std::min(target,mix+step);
                else if(mix>target) mix=std::max(target,mix-step);
                bus[f*2] += (mLeft[channel][f]-bus[f*2])*mix;
                bus[f*2+1] += (mRight[channel][f]-bus[f*2+1])*mix;
            }
        }
    }
}
}
