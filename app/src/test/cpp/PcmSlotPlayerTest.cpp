#include "audio/PcmSlotPlayer.h"
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <thread>
#include <atomic>
#include <vector>

int main() {
    PcmSlotPlayer p;
    int16_t pcm[] = {32767,-32768, 0,16384, -16384,8192};
    assert(p.load(0, pcm, 3, 48000));
    pcm[0] = 0;
    assert(p.start(0, false));
    float out[6]{}; p.renderAdd(out, 3, 48000);
    assert(out[0] > .99f && out[1] == -1.f);
    assert(!p.isPlaying(0));
    assert(p.start(0, true));
    float loop[10]{}; p.renderAdd(loop, 5, 48000);
    assert(p.isPlaying(0));
    assert(p.stop(0)); p.renderAdd(loop, 1, 48000); assert(!p.isPlaying(0));
    // Loop changes apply at callback boundary without restarting position.
    int16_t sequence[] = {1000,1000, 2000,2000, 3000,3000, 4000,4000};
    assert(p.load(2, sequence, 4, 48000)); assert(p.start(2, false));
    float first[4]{}; p.renderAdd(first, 2, 48000);
    assert(first[0] > .029f && first[2] > .06f);
    assert(p.setLoop(2, true)); float second[8]{}; p.renderAdd(second, 4, 48000);
    assert(second[0] > .09f); // continued at frame 2, not restarted at frame 0
    assert(p.isPlaying(2)); // reached EOF and looped
    assert(p.setLoop(2, false)); float tail[8]{}; p.renderAdd(tail, 4, 48000);
    assert(!p.isPlaying(2)); // disabling loop runs to EOF
    assert(p.setLoop(3, true)); float empty[2]{}; p.renderAdd(empty,1,48000); // safe unloaded slot
    assert(!p.load(16, pcm, 3, 44100));
    assert(!p.load(1, pcm, 0, 44100));
    assert(!p.load(1, pcm, 3, 0));
    assert(p.start(0, false));
    float preserve[2]{}; p.renderAdd(preserve, 1, 48000);
    assert(!p.load(0, pcm, 3, 0));
    assert(p.isPlaying(0)); // invalid replacement preserves old asset/playback

    // Fractional-rate duration and finite interpolation.
    std::vector<int16_t> ramp(441 * 2);
    for (int i=0;i<441;++i) ramp[i*2]=ramp[i*2+1]=int16_t(i*40);
    assert(p.load(1, ramp.data(), 441, 44100)); assert(p.start(1,false));
    std::vector<float> resampled(480*2); p.renderAdd(resampled.data(),480,48000);
    assert(!p.isPlaying(1));
    for (float x:resampled) assert(std::isfinite(x) && std::abs(x)<=1.f);

    // All supported callback block sizes and 16-way additive mixing.
    int16_t constant[] = {4096, -4096};
    for (int s=0;s<16;++s) { assert(p.load(s, constant, 1, 48000)); assert(p.start(s,true)); }
    for (int n : {1,64,192,513}) {
        std::vector<float> mix(size_t(n)*2 + 4, 0.f);
        p.renderAdd(mix.data()+2, n, 48000);
        assert(std::abs(mix[2] - 2.f) < .001f && std::abs(mix[3] + 2.f) < .001f);
        assert(mix[0] == 0.f && mix.back() == 0.f);
    }
    assert(p.stopAll()); float silence[2]{}; p.renderAdd(silence,1,48000);
    for(int s=0;s<16;++s) assert(!p.isPlaying(s));

    // Budget checks on tiny configurable instance; rejection leaves old clip intact.
    PcmSlotPlayer limited(8, 16);
    assert(limited.load(0,constant,1,48000));
    assert(!limited.load(0,constant,3,48000));
    assert(limited.start(0,true)); float tiny[2]{}; limited.renderAdd(tiny,1,48000);
    assert(tiny[0] > 0.f && limited.isPlaying(0));

    // A full-size upload fits exactly: caller storage plus one native copy.
    PcmSlotPlayer uploadLimit(64, 128);
    int16_t fullAsset[32]{};
    assert(uploadLimit.load(0, fullAsset, 16, 48000, nullptr, 64));
    assert(!uploadLimit.load(1, constant, 1, 48000, nullptr, 64));

    // Serialized worker replacement/free while audio callback concurrently renders.
    std::atomic<bool> done{false};
    std::thread audio([&] { float block[128]{}; while(!done.load()) p.renderAdd(block,64,48000); });
    for(int i=0;i<3000;++i) {
        while (!p.load(i%16, constant, 1, 48000)) std::this_thread::yield();
        if ((i%3)==0) while (!p.free(i%16)) std::this_thread::yield();
    }
    done.store(true); audio.join();
    float finalBlock[2]{}; p.renderAdd(finalBlock,1,48000);
    std::cout << "PcmSlotPlayer tests passed\n";
}
