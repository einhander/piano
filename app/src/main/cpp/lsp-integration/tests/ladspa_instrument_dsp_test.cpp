#include "effects/ladspa/LadspaEffect.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <vector>

using namespace piano;
using DescriptorFn = const LADSPA_Descriptor* (*)(unsigned long);
static constexpr int sizes[]={16,64,128,256};
static bool finite(const std::vector<float>& v) { return std::all_of(v.begin(),v.end(),[](float x){return std::isfinite(x);}); }
static const lsp::LadspaBinding* binding(int s) { return s<3?lsp::bindingForSlot(s):lsp::instrumentBindingForSlot(s); }
static void runPlugin(DescriptorFn get, int slot, double rate) {
 const auto* b=binding(slot); assert(b);
 // Descriptor enumeration index is not UniqueID; search by actual compiled Label.
 auto* d=static_cast<const LADSPA_Descriptor*>(nullptr);
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d && d->UniqueID==b->uniqueId);
  if(slot==lsp::kSlotChorus) assert(d->PortCount==182 && std::strcmp(d->PortNames[8],"Oversampling")==0);
  if(slot==lsp::kSlotClipper) assert(d->PortCount==58 && std::strcmp(d->PortNames[20],"Overdrive protection reactivity (ms)")==0 && std::strcmp(d->PortNames[57],"latency")==0);
   if(slot==lsp::kSlotFilter) assert(d->PortCount==29 && std::strcmp(d->PortNames[20],"Filter type")==0 && std::strcmp(d->PortNames[28],"latency")==0);
   if(slot==lsp::kSlotPhaser) assert(d->PortCount==96 && std::strcmp(d->PortNames[20],"Number of filters")==0 && std::strcmp(d->PortNames[95],"latency")==0);
   if(slot==lsp::kSlotFlanger) assert(d->PortCount==45 && std::strcmp(d->PortNames[23],"Depth (ms)")==0 && std::strcmp(d->PortNames[44],"latency")==0);
 for(int n:sizes) {
  ladspa::LadspaEffect fx(slot,b,d);
   const bool prepared=fx.prepare(rate,256);
    if(slot==lsp::kSlotClipper && !prepared) { assert(!fx.isAvailable()); return; }
    assert(prepared && fx.isAvailable()); fx.setBypassed(false);
   if(slot==lsp::kSlotChorus) fx.setParameter(lsp::kParamChorusVoices,3);
   else if(slot==lsp::kSlotClipper) { fx.setParameter(lsp::kParamClipperDrive,4.0f); fx.setParameter(lsp::kParamClipperThreshold,-12.0f); }
   else if(slot==lsp::kSlotFilter) fx.setParameter(lsp::kParamFilterCutoff,180.0f);
   else if(slot==lsp::kSlotPhaser) fx.setParameter(lsp::kParamPhaserRate,1.7f);
   else fx.setParameter(lsp::kParamFlangerRate,1.7f);
  std::vector<float> l(n),r(n),original(n);
  for(int i=0;i<n;++i) l[i]=r[i]=original[i]=0.65f*std::sin(2*3.141592653589793*440*(i+1)/rate);
  fx.process(l.data(),r.data(),n); assert(finite(l)&&finite(r));
   if(slot==lsp::kSlotChorus || slot==lsp::kSlotPhaser || slot==lsp::kSlotFlanger) {
   if(n==256) { double delta=0; for(int i=0;i<n;++i) delta+=std::abs(l[i]-original[i]); assert(delta>0.01); }
  } else {
   if(n==256) {
    double dc=0; float peak=0;
    for(int i=0;i<n;++i){dc+=l[i]; peak=std::max(peak,std::abs(l[i]));}
    assert(std::abs(dc/n)<0.2); assert(peak<2.0f);
   }
  }
   assert(fx.isAvailable());
    if(slot==lsp::kSlotClipper) assert(fx.getLatencyFrames()==static_cast<int>(std::lround(rate*0.0005)));
   else assert(fx.getLatencyFrames()==0);
 }
 ladspa::LadspaEffect fx(slot,b,d); assert(fx.prepare(rate,256)); fx.setBypassed(true);
 std::vector<float> l(64,.25f),r(64,-.5f); fx.process(l.data(),r.data(),64);
 for(int i=0;i<64;++i) assert(l[i]==.25f && r[i]==-.5f);
}
static double rmsTone(ladspa::LadspaEffect& fx,double rate,double hz) {
 std::vector<float> l(256),r(256); double sum=0; int count=0;
 for(int block=0;block<24;++block) {
  for(int i=0;i<256;++i) l[i]=r[i]=.2f*std::sin(2*3.141592653589793*hz*(block*256+i)/rate);
  fx.process(l.data(),r.data(),256);
  for(int i=0;i<256;++i) { assert(std::isfinite(l[i])&&std::isfinite(r[i])); if(block>=8) { sum+=l[i]*l[i]; ++count; } }
 }
 return std::sqrt(sum/count);
}
static void checkFeedback(DescriptorFn get,int slot,double rate) {
 const auto* b=binding(slot); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d);
 ladspa::LadspaEffect dry(slot,b,d), wet(slot,b,d);
  assert(dry.prepare(rate,256)&&wet.prepare(rate,256));
  assert(dry.getLatencyFrames()==0 && wet.getLatencyFrames()==0);
  dry.setBypassed(false); wet.setBypassed(false);
 const uint32_t id=slot==lsp::kSlotPhaser?lsp::kParamPhaserFeedback:lsp::kParamFlangerFeedback;
 wet.setParameter(id,.5f);
  const double base=rmsTone(dry,rate,440);
  ladspa::LadspaEffect zero(slot,b,d); assert(zero.prepare(rate,256)); zero.setBypassed(false);
  const double zeroAmount=rmsTone(zero,rate,440);
  assert(std::abs(base-zeroAmount)<1e-6);
  const double changed=rmsTone(wet,rate,440);
 const double difference=std::abs(base-changed);
 std::printf("feedback slot=%d rate=%.0f dry_rms=%.6f wet_rms=%.6f delta=%.6f\n",slot,rate,base,changed,difference);
 assert(base>1e-5 && changed>1e-5 && difference>1e-5);
}
static double checkMix(DescriptorFn get,int slot,double rate,float mix) {
 const auto* b=binding(slot); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d);
 ladspa::LadspaEffect fx(slot,b,d); assert(fx.prepare(rate,256)); fx.setBypassed(false);
 const uint32_t id=slot==lsp::kSlotChorus?lsp::kParamChorusMix:
                   slot==lsp::kSlotPhaser?lsp::kParamPhaserMix:lsp::kParamFlangerMix;
 fx.setParameter(id,mix);
 if(slot==lsp::kSlotChorus) fx.setParameter(lsp::kParamChorusVoices,3);
 // Two seconds let LFO/delay state settle; measure next 0.5 s of identical input.
 double sum=0,delta=0; int count=0;
 for(int block=0;block<469;++block) {
  std::array<float,256> l{},r{};
  for(int i=0;i<256;++i) l[i]=r[i]=.1f*std::sin(2*3.141592653589793*440*(block*256+i)/rate);
  fx.process(l.data(),r.data(),256);
  if(block>=375) for(int i=0;i<256;++i) {
   const float x=.1f*std::sin(2*3.141592653589793*440*(block*256+i)/rate);
   sum+=l[i]*l[i]; delta+=std::abs(l[i]-x); ++count;
  }
 }
 const double rms=std::sqrt(sum/count);
 if(mix==0) assert(delta/count<1e-6 && std::abs(rms-.1/std::sqrt(2.0))<1e-4);
 else { assert(delta/count>1e-4); assert(rms<.15); }
 return rms;
}
static void checkMixes(DescriptorFn get,int slot,double rate) {
 const double dry=checkMix(get,slot,rate,0), half=checkMix(get,slot,rate,50), wet=checkMix(get,slot,rate,100);
 std::printf("mix slot=%d rate=%.0f rms0=%.6f rms50=%.6f rms100=%.6f\n",slot,rate,dry,half,wet);
}
static void checkFilterResponse(DescriptorFn get,double rate) {
 const auto* b=binding(lsp::kSlotFilter); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d);
 ladspa::LadspaEffect fx(lsp::kSlotFilter,b,d); assert(fx.prepare(rate,256)); fx.setBypassed(false);
  fx.setParameter(lsp::kParamFilterType,0); fx.setParameter(lsp::kParamFilterCutoff,1000);
 const double low=rmsTone(fx,rate,100), high=rmsTone(fx,rate,8000);
 const double ratio=high/low;
 std::printf("filter rate=%.0f low_rms=%.6f high_rms=%.6f ratio=%.6f\n",rate,low,high,ratio);
 assert(low>1e-5 && high<low*.6);
}
static void checkFilterTypes(DescriptorFn get,double rate) {
 const auto* b=binding(lsp::kSlotFilter); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d);
 for(int type=0;type<=10;++type) {
  ladspa::LadspaEffect fx(lsp::kSlotFilter,b,d); assert(fx.prepare(rate,256)); fx.setBypassed(false);
  fx.setParameter(lsp::kParamFilterType,static_cast<float>(type));
  fx.setParameter(lsp::kParamFilterCutoff,1000); fx.setParameter(lsp::kParamFilterGain,2.0f);
  const double below=rmsTone(fx,rate,100), center=rmsTone(fx,rate,1000), above=rmsTone(fx,rate,8000);
  assert(std::isfinite(below)&&std::isfinite(center)&&std::isfinite(above));
  assert(below>=0.0 && center>=0.0 && above>=0.0);
  std::printf("filter type=%d rate=%.0f rms100=%.6f rms1000=%.6f rms8000=%.6f\n",type,rate,below,center,above);
 }
}
static int checkClipperNonlinearity(DescriptorFn get, double rate) {
 const auto* b=lsp::instrumentBindingForSlot(lsp::kSlotClipper); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d); auto h=d->instantiate(d,static_cast<unsigned long>(rate)); assert(h);
 std::vector<float> ports(d->PortCount,0.f), il(256),ir(256),ol(256),orr(256);
 for(unsigned long p=0;p<d->PortCount;++p) {
  if(LADSPA_IS_PORT_CONTROL(d->PortDescriptors[p]) && LADSPA_IS_PORT_INPUT(d->PortDescriptors[p])) ports[p]=0;
  d->connect_port(h,p,&ports[p]);
 }
 // Safe musical-drive settings, actual audited ports.
 ports[4]=0; ports[5]=4; ports[6]=.5f; ports[7]=0; ports[14]=0; ports[15]=0; ports[17]=0;
  ports[21]=1; ports[22]=0; ports[23]=.75f; ports[13]=-12; ports[24]=0; ports[25]=1; ports[20]=1;
 d->connect_port(h,0,il.data()); d->connect_port(h,1,ir.data()); d->connect_port(h,2,ol.data()); d->connect_port(h,3,orr.data());
 if(d->activate)d->activate(h);
 std::vector<float> history; history.reserve(256*40);
 for(int block=0;block<40;++block) {
  for(int i=0;i<256;++i){float x=.8f*std::sin(2*3.141592653589793*440*(block*256+i)/rate); il[i]=ir[i]=x; history.push_back(x);}
  d->run(h,256); assert(finite(ol)&&finite(orr));
 }
  const int lag=static_cast<int>(ports[57]);
  assert(lag>=0);
 // Fit output against delayed input; significant residual denotes generated harmonics.
 double xy=0,xx=0,res=0,dc=0; float peak=0;
 for(int i=64;i<256;++i){int idx=39*256+i; float x=history[idx-lag]; xy+=ol[i]*x;xx+=x*x;dc+=ol[i];peak=std::max(peak,std::abs(ol[i]));}
 double gain=xy/xx;
 for(int i=64;i<256;++i){int idx=39*256+i;double e=ol[i]-gain*history[idx-lag];res+=e*e;}
  assert(std::sqrt(res/192)>.001); assert(std::abs(dc/192)<.2); assert(peak<2);
  std::printf("clipper raw: rate=%.0f latency=%d residual=%.5f dc=%.5f peak=%.5f\n",rate,lag,std::sqrt(res/192),dc/192,peak);
  if(d->deactivate) d->deactivate(h);
  d->cleanup(h);
  return lag;
}
static void checkClipperImpulse(DescriptorFn get,double rate,int expectedLatency) {
 const auto* b=lsp::instrumentBindingForSlot(lsp::kSlotClipper); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 auto h=d->instantiate(d,static_cast<unsigned long>(rate)); assert(h);
 std::vector<float> ports(d->PortCount,0.f), il(256,0.f),ir(256,0.f),ol(256,0.f),orr(256,0.f);
 for(unsigned long p=0;p<d->PortCount;++p) d->connect_port(h,p,&ports[p]);
 ports[4]=0; ports[5]=1; ports[6]=.5f; ports[7]=0; ports[13]=0; ports[14]=0; ports[15]=0;
 ports[17]=0; ports[20]=1; ports[21]=1; ports[22]=0; ports[23]=.75f; ports[24]=0; ports[25]=1;
 d->connect_port(h,0,il.data()); d->connect_port(h,1,ir.data()); d->connect_port(h,2,ol.data()); d->connect_port(h,3,orr.data());
 il[0]=ir[0]=.5f; if(d->activate)d->activate(h); d->run(h,256);
 int onset=-1; for(int i=0;i<256;++i) if(std::abs(ol[i])>1e-6f){onset=i;break;}
 assert(onset==expectedLatency); assert(finite(ol)&&finite(orr));
 if(d->deactivate) d->deactivate(h);
 d->cleanup(h);
}
static void checkChorusFeedback(DescriptorFn get) {
 const auto* b=lsp::instrumentBindingForSlot(lsp::kSlotChorus); const LADSPA_Descriptor* d=nullptr;
 for(unsigned long i=0;get(i);++i) if(std::strcmp(get(i)->Label,b->label)==0)d=get(i);
 assert(d); ladspa::LadspaEffect dry(lsp::kSlotChorus,b,d), feedback(lsp::kSlotChorus,b,d);
 assert(dry.prepare(48000,256)&&feedback.prepare(48000,256)); dry.setBypassed(false); feedback.setBypassed(false);
 dry.setParameter(lsp::kParamChorusVoices,3); feedback.setParameter(lsp::kParamChorusVoices,3);
 feedback.setParameter(lsp::kParamChorusFeedback,.5f);
 std::array<float,256> a{},ar{},c{},cr{}; double difference=0;
 for(int block=0;block<200;++block) {
  for(int i=0;i<256;++i) a[i]=ar[i]=c[i]=cr[i]=.1f*std::sin(2*3.141592653589793*440*(block*256+i)/48000.);
  dry.process(a.data(),ar.data(),256); feedback.process(c.data(),cr.data(),256);
  for(int i=0;i<256;++i) { assert(std::isfinite(c[i])&&std::isfinite(cr[i])); difference+=std::abs(a[i]-c[i]); }
 }
 assert(difference>.01);
}
int main(int argc,char**argv) {
 assert(sizeof(lsp::kBindings)/sizeof(lsp::kBindings[0])==3);
  assert(sizeof(lsp::kInstrumentBindings)/sizeof(lsp::kInstrumentBindings[0])==5);
  for(int slot=0;slot<lsp::kInstrumentEffectCount;++slot) {
   int count=0; assert(lsp::paramDescriptors(slot,count) && count>0);
   if(slot>=3) assert(lsp::instrumentBindingForSlot(slot) && lsp::effectSlotForId(lsp::instrumentBindingForSlot(slot)->stableId)==slot);
  }
  assert(lsp::effectSlotForId(lsp::kIdChorus)==3 && lsp::effectSlotForId(lsp::kIdClipper)==4 &&
         lsp::effectSlotForId(lsp::kIdFilter)==5 && lsp::effectSlotForId(lsp::kIdPhaser)==6 && lsp::effectSlotForId(lsp::kIdFlanger)==7);
 ladspa::LadspaEffect missing(lsp::kSlotChorus,lsp::instrumentBindingForSlot(lsp::kSlotChorus),nullptr);
 assert(!missing.prepare(48000,256) && !missing.isAvailable());
 assert(argc==2); void* so=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL); assert(so);
 auto get=reinterpret_cast<DescriptorFn>(dlsym(so,"ladspa_descriptor")); assert(get);
  for(int slot=3;slot<lsp::kInstrumentEffectCount;++slot) { runPlugin(get,slot,44100.); runPlugin(get,slot,48000.); }
 const int latency441=checkClipperNonlinearity(get,44100.);
 const int latency480=checkClipperNonlinearity(get,48000.);
 assert(latency441==22 && latency480==24);
 checkClipperImpulse(get,44100.,latency441); checkClipperImpulse(get,48000.,latency480);
  checkChorusFeedback(get);
   for(double rate:{44100.,48000.}) {
    checkMixes(get,lsp::kSlotChorus,rate);
    checkMixes(get,lsp::kSlotPhaser,rate);
    checkMixes(get,lsp::kSlotFlanger,rate);
    checkFilterResponse(get,rate);
    checkFilterTypes(get,rate);
   checkFeedback(get,lsp::kSlotPhaser,rate);
   checkFeedback(get,lsp::kSlotFlanger,rate);
  }
  dlclose(so); std::puts("compiled LADSPA instrument DSP tests passed");
}
