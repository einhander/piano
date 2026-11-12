#include "PcmSlotPlayer.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <limits>
#include <new>

struct PcmSlotPlayer::Asset {
    int64_t frames;
    int32_t rate;
    uint64_t bytes;
    std::unique_ptr<int16_t[]> samples;
};

PcmSlotPlayer::PcmSlotPlayer(uint64_t maxAssetBytes, uint64_t maxResidentBytes)
    : maxAssetBytes_(maxAssetBytes), maxResidentBytes_(maxResidentBytes) {}

PcmSlotPlayer::~PcmSlotPlayer() {
    // Contract: callback is quiescent. Commands have not yet become callback-owned.
    for (auto& v : voices_) { delete v.asset; v = {}; }
    uint32_t r = read_.load(std::memory_order_relaxed), w = write_.load(std::memory_order_relaxed);
    while (r != w) { delete commands_[r % kQueueCapacity].asset; ++r; }
    uint32_t ar = retiredRead_.load(std::memory_order_relaxed), aw = retiredWrite_.load(std::memory_order_relaxed);
    while (ar != aw) { delete retired_[ar % kQueueCapacity]; ++ar; }
}

bool PcmSlotPlayer::validSlot(int s) const { return s >= 0 && s < kMaxSlots; }

void PcmSlotPlayer::drainRetired() {
    uint32_t r = retiredRead_.load(std::memory_order_relaxed);
    const uint32_t w = retiredWrite_.load(std::memory_order_acquire);
    while (r != w) {
        Asset* a = retired_[r % kQueueCapacity];
        residentBytes_ -= a->bytes;
        delete a;
        ++r;
    }
    retiredRead_.store(r, std::memory_order_release);
}

bool PcmSlotPlayer::enqueue(const Command& c) {
    const uint32_t w = write_.load(std::memory_order_relaxed);
    if (w - read_.load(std::memory_order_acquire) >= kQueueCapacity) return false;
    commands_[w % kQueueCapacity] = c;
    write_.store(w + 1, std::memory_order_release);
    return true;
}

bool PcmSlotPlayer::load(int slot, const int16_t* input, int64_t frames, int32_t rate, std::string* error, uint64_t uploadCapacity) {
    std::lock_guard<std::mutex> guard(workerMutex_);
    drainRetired();
    auto fail = [&](const char* why) { if (error) *error = why; return false; };
    if (!validSlot(slot)) return fail("invalid slot");
    if (!input || frames <= 0 || rate <= 0 || rate > 768000) return fail("invalid PCM dimensions or rate");
    if (uint64_t(frames) > (std::numeric_limits<uint64_t>::max() / 4)) return fail("PCM size overflow");
    const uint64_t bytes = uint64_t(frames) * 4;
    if (bytes > maxAssetBytes_) return fail("asset budget exceeded");
    const uint64_t uploadBytes = std::max(uploadCapacity, bytes);
    if (uploadBytes > maxResidentBytes_ || residentBytes_ > maxResidentBytes_ - uploadBytes ||
        bytes > maxResidentBytes_ - uploadBytes - residentBytes_)
        return fail("resident budget exceeded (copy allowance)");
    const uint32_t w = write_.load(std::memory_order_relaxed);
    if (w - read_.load(std::memory_order_acquire) >= kQueueCapacity) return fail("command queue full");
    if (frames > static_cast<int64_t>(std::numeric_limits<size_t>::max() / (2 * sizeof(int16_t)))) return fail("allocation size overflow");
    Asset* asset = new (std::nothrow) Asset{};
    if (!asset) return fail("PCM allocation failed");
    asset->samples.reset(new (std::nothrow) int16_t[static_cast<size_t>(frames) * 2]);
    if (!asset->samples) { delete asset; return fail("PCM allocation failed"); }
    asset->frames = frames; asset->rate = rate; asset->bytes = bytes;
    std::memcpy(asset->samples.get(), input, static_cast<size_t>(bytes));
    if (!enqueue({Op::Replace, slot, asset, false})) { delete asset; return fail("command queue full"); }
    residentBytes_ += bytes;
    if (error) error->clear();
    return true;
}

bool PcmSlotPlayer::start(int slot, bool loop) {
    std::lock_guard<std::mutex> g(workerMutex_); drainRetired();
    return validSlot(slot) && enqueue({Op::Start, slot, nullptr, loop});
}
bool PcmSlotPlayer::setLoop(int slot, bool loop) {
    std::lock_guard<std::mutex> g(workerMutex_); drainRetired();
    return validSlot(slot) && enqueue({Op::SetLoop, slot, nullptr, loop});
}
bool PcmSlotPlayer::stop(int slot) {
    std::lock_guard<std::mutex> g(workerMutex_); drainRetired();
    return validSlot(slot) && enqueue({Op::Stop, slot, nullptr, false});
}
bool PcmSlotPlayer::free(int slot) {
    std::lock_guard<std::mutex> g(workerMutex_); drainRetired();
    return validSlot(slot) && enqueue({Op::Free, slot, nullptr, false});
}
bool PcmSlotPlayer::stopAll() {
    std::lock_guard<std::mutex> g(workerMutex_); drainRetired();
    return enqueue({Op::StopAll, 0, nullptr, false});
}
bool PcmSlotPlayer::isPlaying(int slot) const { return validSlot(slot) && playing_[slot].load(std::memory_order_acquire); }

void PcmSlotPlayer::renderAdd(float* out, int32_t frames, int32_t outputRate) {
    if (!out || frames <= 0 || outputRate <= 0) return;
    uint32_t r = read_.load(std::memory_order_relaxed);
    const uint32_t w = write_.load(std::memory_order_acquire);
    while (r != w) {
        const Command c = commands_[r % kQueueCapacity];
        Voice& v = voices_[c.slot];
        if (c.op == Op::Replace || c.op == Op::Free) {
            Asset* old = v.asset;
            uint32_t aw = retiredWrite_.load(std::memory_order_relaxed);
            if (old && aw - retiredRead_.load(std::memory_order_acquire) >= kQueueCapacity) break;
            if (old) {
                retired_[aw % kQueueCapacity] = old;
                retiredWrite_.store(aw + 1, std::memory_order_release);
            }
            v = {};
            playing_[c.slot].store(false, std::memory_order_release);
            if (c.op == Op::Replace) v.asset = c.asset;
        } else if (c.op == Op::Start) {
            if (v.asset) { v.position = 0; v.loop = c.loop; playing_[c.slot].store(true, std::memory_order_release); }
        } else if (c.op == Op::SetLoop) {
            if (v.asset) v.loop = c.loop;
        } else if (c.op == Op::Stop) {
            v.position = 0; playing_[c.slot].store(false, std::memory_order_release);
        } else if (c.op == Op::StopAll) {
            for (int i = 0; i < kMaxSlots; ++i) { voices_[i].position = 0; playing_[i].store(false, std::memory_order_release); }
        }
        ++r;
    }
    read_.store(r, std::memory_order_release);
    for (int s = 0; s < kMaxSlots; ++s) {
        Voice& v = voices_[s]; Asset* a = v.asset;
        if (!a || !playing_[s].load(std::memory_order_relaxed)) continue;
        const double step = double(a->rate) / outputRate;
        for (int32_t i = 0; i < frames; ++i) {
            if (v.position >= a->frames) {
                if (v.loop) v.position = std::fmod(v.position, double(a->frames));
                else { v.position = 0; playing_[s].store(false, std::memory_order_release); break; }
            }
            const int64_t index = static_cast<int64_t>(v.position);
            const int64_t next = index + 1 < a->frames ? index + 1 : (v.loop ? 0 : index);
            const double frac = v.position - index;
            for (int ch = 0; ch < 2; ++ch) {
                float x = float(a->samples[index * 2 + ch]) / 32768.0f;
                float y = float(a->samples[next * 2 + ch]) / 32768.0f;
                out[i * 2 + ch] += float(x + (y - x) * frac);
            }
            v.position += step;
        }
        if (!v.loop && v.position >= double(a->frames) - 1e-9) {
            v.position = 0;
            playing_[s].store(false, std::memory_order_release);
        }
    }
}
