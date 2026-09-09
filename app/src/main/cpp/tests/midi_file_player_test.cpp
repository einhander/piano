// Build (host, from repo root):
//   g++ -std=c++17 -I app/src/main/cpp \
//     app/src/main/cpp/midi/MidiFileParser.cpp \
//     app/src/main/cpp/midi/MidiFileWriter.cpp \
//     app/src/main/cpp/realtime/MidiQueue.cpp \
//     app/src/main/cpp/engine/MidiFilePlayer.cpp \
//     app/src/main/cpp/tests/midi_file_player_test.cpp \
//     -o /tmp/midi_file_player_test
// Run (from repo root):
//   /tmp/midi_file_player_test

#include "engine/MidiFilePlayer.h"
#include "midi/MidiFileWriter.h"
#include "midi/MidiFileParser.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <thread>
#include <chrono>
#include <vector>
#include <string>

static int fail(const char* msg) { std::fprintf(stderr, "FAIL: %s\n", msg); return 1; }

static std::string makePath(const char* name) { return std::string("/tmp/") + name; }

static void writeBe16(FILE* f, uint16_t v) { std::fputc((v >> 8) & 0xFF, f); std::fputc(v & 0xFF, f); }
static void writeBe32(FILE* f, uint32_t v) {
    std::fputc((v >> 24) & 0xFF, f); std::fputc((v >> 16) & 0xFF, f);
    std::fputc((v >> 8) & 0xFF, f); std::fputc(v & 0xFF, f);
}

static void writeTrack(FILE* f, const std::vector<uint8_t>& data) {
    std::fwrite("MTrk", 1, 4, f);
    writeBe32(f, static_cast<uint32_t>(data.size()));
    std::fwrite(data.data(), 1, data.size(), f);
}

static void writeMeta(FILE* f, const char* path, const char* name) {
    FILE* out = std::fopen(path, "wb");
    if (!out) std::abort();
    std::fwrite("MThd", 1, 4, out); writeBe32(out, 6); writeBe16(out, 0); writeBe16(out, 1); writeBe16(out, 960);
    std::vector<uint8_t> trk;
    trk.insert(trk.end(), {0x00, 0xFF, 0x03, static_cast<uint8_t>(std::strlen(name))});
    trk.insert(trk.end(), name, name + std::strlen(name));
    trk.insert(trk.end(), {0x00, 0xFF, 0x2F, 0x00});
    writeTrack(out, trk);
    std::fclose(out);
}

static void writeTwoTrackNames(const char* path, const char* name0, const char* name1) {
    FILE* f = std::fopen(path, "wb");
    if (!f) std::abort();
    std::fwrite("MThd", 1, 4, f); writeBe32(f, 6); writeBe16(f, 1); writeBe16(f, 2); writeBe16(f, 960);
    std::vector<uint8_t> t0 = {0x00, 0xFF, 0x03, static_cast<uint8_t>(std::strlen(name0))};
    t0.insert(t0.end(), name0, name0 + std::strlen(name0));
    t0.insert(t0.end(), {0x00, 0xFF, 0x2F, 0x00});
    std::vector<uint8_t> t1 = {0x00, 0xFF, 0x03, static_cast<uint8_t>(std::strlen(name1))};
    t1.insert(t1.end(), name1, name1 + std::strlen(name1));
    t1.insert(t1.end(), {0x00, 0xFF, 0x2F, 0x00});
    writeTrack(f, t0);
    writeTrack(f, t1);
    std::fclose(f);
}

// Single-track file with N note on/off pairs; optional tempo meta
// (0xFF 0x51, 3-byte us-per-quarter, big-endian) at tick 0.
static void writeTempoFile(const char* path, uint32_t usPerQuarter, bool withTempo, int notes = 1) {
    FILE* f = std::fopen(path, "wb");
    if (!f) std::abort();
    std::fwrite("MThd", 1, 4, f); writeBe32(f, 6); writeBe16(f, 0); writeBe16(f, 1); writeBe16(f, 960);
    std::vector<uint8_t> trk;
    if (withTempo) {
        trk.insert(trk.end(), {0x00, 0xFF, 0x51, 0x03});
        trk.push_back((usPerQuarter >> 16) & 0xFF);
        trk.push_back((usPerQuarter >> 8) & 0xFF);
        trk.push_back(usPerQuarter & 0xFF);
    }
    for (int i = 0; i < notes; ++i) {
        uint8_t note = static_cast<uint8_t>(60 + i);
        trk.insert(trk.end(), {0x00, 0x90, note, 1});
        trk.insert(trk.end(), {0x00, 0x80, note, 0});
    }
    trk.insert(trk.end(), {0x00, 0xFF, 0x2F, 0x00});
    writeTrack(f, trk);
    std::fclose(f);
}

static bool hasOnlyTrack1AndMeta(const std::vector<MidiFileEvent>& v) {
    for (const auto& e : v) {
        if (e.status == 0xFF) continue;
        if (e.trackId != 1) return false;
    }
    return true;
}

int main() {
    // 1) >8192 events
    {
        const std::string path = makePath("midi_file_player_big.mid");
        std::vector<RecordedMidiEvent> ev;
        for (int i = 0; i < 9001; ++i) ev.push_back({i, static_cast<uint8_t>(0x90 | (i % 16)), 60, 1, 0});
        if (!MidiFileWriter().write(path.c_str(), ev, 0, 960, 500000)) return fail("big write failed");
        MidiFilePlayer p;
        int32_t sel[] = {0}; int32_t ch[] = {-1};
        if (p.load(0, path.c_str(), 120.0f, false, sel, 1, ch, false) != 0) return fail("big load failed");
        if (p.getSlotInfo(0).eventCount != 9001) return fail("big eventCount mismatch");
        std::puts("BigEventCount: PASS");
    }

    // 2) applyTrackSelection track 1 only
    {
        std::vector<MidiFileEvent> in = {
            {0, 0xFF, 0x51, 0x00, 0}, {0, 0x90, 60, 1, 0}, {0, 0x80, 60, 0, 0},
            {0, 0xFF, 0x03, 0x00, 0}, {0, 0x91, 61, 1, 1}, {0, 0x81, 61, 0, 1}
        };
        int32_t sel[] = {1}; int32_t ch[] = {-1};
        auto out = MidiFilePlayer::applyTrackSelection(in, sel, 1, ch);
        if (!hasOnlyTrack1AndMeta(out)) return fail("selection filter wrong");
        if (out.size() != 4) return fail("selection size mismatch");
        std::puts("ApplySelection: PASS");
    }

    // 3) per-track remap
    {
        std::vector<MidiFileEvent> in = {{0, 0x90, 60, 1, 0}, {0, 0x91, 61, 1, 1}};
        int32_t sel[] = {0, 1}; int32_t ch[] = {5, -1};
        auto out = MidiFilePlayer::applyTrackSelection(in, sel, 2, ch);
        if (out.size() != 2 || out[0].status != 0x95 || out[1].status != 0x91) return fail("remap wrong");
        std::puts("PerTrackRemap: PASS");
    }

    // 4) selectedCount == 0
    {
        std::vector<MidiFileEvent> in = {{0, 0x90, 60, 1, 0}, {0, 0xFF, 0x51, 0x00, 0}, {0, 0x80, 60, 0, 1}};
        auto out = MidiFilePlayer::applyTrackSelection(in, nullptr, 0, nullptr);
        if (out.size() != 1 || out[0].status != 0xFF) return fail("selectedCount zero wrong");
        std::puts("SelectedCountZero: PASS");
    }

    // 5) track names
    {
        const std::string a = makePath("midi_file_player_named.mid");
        writeTwoTrackNames(a.c_str(), "Trk0", "");
        std::vector<RecordedMidiEvent> ev; std::vector<std::pair<int64_t, uint32_t>> tm; std::vector<std::pair<int64_t, std::pair<int,int>>> ts; std::vector<std::string> names;
        if (!MidiFileParser().parse(a.c_str(), ev, tm, ts, names, nullptr)) return fail("track names parse failed");
        if (names.size() != 2 || names[0] != "Trk0" || !names[1].empty()) return fail("track names wrong");
        std::puts("TrackNames: PASS");
    }

    // 6) cache invalidation on file change
    {
        MidiFilePlayer p;
        const std::string a = makePath("midi_file_player_cache.mid");
        writeTwoTrackNames(a.c_str(), "Old0", "Old1");
        auto names1 = p.getTrackNamesForFile(a.c_str());
        if (names1.size() != 2 || names1[0] != "Old0" || names1[1] != "Old1") return fail("cache first read wrong");
        std::this_thread::sleep_for(std::chrono::seconds(1));
        writeTwoTrackNames(a.c_str(), "New0", "New1");
        auto names2 = p.getTrackNamesForFile(a.c_str());
        if (names2.size() != 2 || names2[0] != "New0" || names2[1] != "New1") return fail("cache invalidation wrong");
        std::puts("TrackNamesCacheInvalidation: PASS");
    }

    // 7) initial tempo from file (500000 us/quarter → 120 bpm)
    {
        const std::string a = makePath("midi_file_player_tempo.mid");
        writeTempoFile(a.c_str(), 500000, true);
        MidiFilePlayer p;
        float t = p.getMidiFileTempo(a.c_str());
        if (std::fabs(t - 120.0f) > 0.01f) return fail("tempo 120 wrong");
        std::puts("FileTempo120: PASS");
    }

    // 8) no tempo meta → default 120
    {
        const std::string a = makePath("midi_file_player_notempo.mid");
        writeTempoFile(a.c_str(), 0, false);
        MidiFilePlayer p;
        float t = p.getMidiFileTempo(a.c_str());
        if (std::fabs(t - 120.0f) > 0.01f) return fail("default tempo wrong");
        std::puts("FileTempoDefault: PASS");
    }

    // 9) missing file / null path → -1
    {
        MidiFilePlayer p;
        if (p.getMidiFileTempo("/tmp/definitely_missing_piano_test.mid") != -1.0f) return fail("missing tempo wrong");
        if (p.getMidiFileTempo(nullptr) != -1.0f) return fail("null tempo wrong");
        std::puts("FileTempoMissing: PASS");
    }

    // 10) cache invalidation on file change (different size + mtime)
    {
        MidiFilePlayer p;
        const std::string a = makePath("midi_file_player_tempo_cache.mid");
        writeTempoFile(a.c_str(), 500000, true, 1); // 120 bpm
        float t1 = p.getMidiFileTempo(a.c_str());
        if (std::fabs(t1 - 120.0f) > 0.01f) return fail("tempo cache first wrong");
        std::this_thread::sleep_for(std::chrono::seconds(1));
        writeTempoFile(a.c_str(), 250000, true, 2); // 240 bpm, different size
        float t2 = p.getMidiFileTempo(a.c_str());
        if (std::fabs(t2 - 240.0f) > 0.01f) return fail("tempo cache invalidation wrong");
        std::puts("FileTempoCacheInvalidation: PASS");
    }

    // 11) regression existing midi_file_io_test: manual run only, no duplicate here.
    std::puts("ALL TESTS PASSED");
    return 0;
}
