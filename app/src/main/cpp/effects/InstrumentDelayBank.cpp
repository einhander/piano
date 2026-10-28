#include "InstrumentDelayBank.h"
#include <cmath>

bool InstrumentDelayBank::prepare(double rate, int frames) noexcept {
    mPrepared = false;
    for (auto& delay : mDelays) if (!delay.prepare(rate, frames)) return false;
    mMaxFrames = frames;
    mPrepared = true;
    return true;
}

bool InstrumentDelayBank::setParameter(int ch, int id, float v) noexcept {
    if (!mPrepared || ch < 0 || ch >= kChannels || !std::isfinite(v)) return false;
    auto& d = mDelays[ch];
    switch (id) {
    case 0: if(v!=0 && v!=1)return false; d.setEnabled(v!=0); return true;
    case 1: if(v<1 || v>SharedStereoDelay::kMaxTimeMs)return false; d.setTimeMs(v); return true;
    case 2: if(v<0 || v>SharedStereoDelay::kMaxFeedback)return false; d.setFeedback(v); return true;
    case 3: if(v<0 || v>SharedStereoDelay::kMaxWet)return false; d.setWet(v); return true;
    case 4: if(v!=0 && v!=1)return false; d.setSync(v!=0); return true;
    case 5: if(v<0 || v>4 || std::floor(v)!=v)return false; d.setDivision(static_cast<int>(v)); return true;
    default:return false;
    }
}
float InstrumentDelayBank::getParameter(int ch,int id) const noexcept {
    if(!mPrepared || ch<0 || ch>=kChannels)return NAN;
    const auto& d=mDelays[ch];
    switch(id){case 0:return d.isEnabled()?1:0;case 1:return d.timeMs();case 2:return d.feedback();case 3:return d.wet();case 4:return d.sync()?1:0;case 5:return static_cast<float>(d.division());default:return NAN;}
}
bool InstrumentDelayBank::getParameterInfo(int id,float& lo,float& hi,float& def,bool& integer,bool& toggled) const noexcept {
    integer=toggled=false;
    switch(id){case 0:lo=0;hi=1;def=0;integer=toggled=true;return true;
    case 1:lo=1;hi=SharedStereoDelay::kMaxTimeMs;def=250;return true;
    case 2:lo=0;hi=SharedStereoDelay::kMaxFeedback;def=.35f;return true;
    case 3:lo=0;hi=SharedStereoDelay::kMaxWet;def=.30f;return true;
    case 4:lo=0;hi=1;def=0;integer=toggled=true;return true;
    case 5:lo=0;hi=4;def=0;integer=true;return true;default:return false;}
}
void InstrumentDelayBank::process(float* const buses[kChannels],int frames,double bpm) noexcept {
    if(!mPrepared || !buses || frames<=0)return;
    for(int ch=0;ch<kChannels;++ch){
        if(!buses[ch])continue;
        // bounded per-frame stack scratch; process chunks to avoid allocation.
        float inL[256],inR[256],wetL[256],wetR[256];
        const int chunk = mMaxFrames < 256 ? mMaxFrames : 256;
        for(int off=0;off<frames;off+=chunk){int n=frames-off<chunk?frames-off:chunk;
            // delay's prepared maxFrames contract applies to each chunk.
            for(int i=0;i<n;++i){inL[i]=buses[ch][(off+i)*2];inR[i]=buses[ch][(off+i)*2+1];}
            mDelays[ch].process(inL,inR,wetL,wetR,n,bpm);
            for(int i=0;i<n;++i){buses[ch][(off+i)*2]+=wetL[i];buses[ch][(off+i)*2+1]+=wetR[i];}
        }
    }
}
void InstrumentDelayBank::reset(int ch) noexcept { if(ch>=0&&ch<kChannels)mDelays[ch].requestReset(); }
