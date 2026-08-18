// Build (host, from repo root):
//   g++ -std=c++17 -I app/src/main/cpp \
//     app/src/main/cpp/midi/MidiFileParser.cpp \
//     app/src/main/cpp/midi/MidiFileWriter.cpp \
//     app/src/main/cpp/tests/midi_file_io_test.cpp \
//     -o /tmp/midi_file_io_test
// Run (from repo root):
//   /tmp/midi_file_io_test test_files/Test.mid test_files/Test2.mid

#include "midi/MidiFileParser.h"
#include "midi/MidiFileWriter.h"
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <vector>
#include <string>
#include <set>
#include <utility>

static int fail(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    fprintf(stderr, "FAIL: %s\n", buf);
    return 1;
}

// Compare two sorted event lists by (tick, status, data1, data2)
static bool eventsMatch(const std::vector<RecordedMidiEvent>& a,
                        const std::vector<RecordedMidiEvent>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++) {
        if (a[i].tick != b[i].tick) return false;
        if (a[i].status != b[i].status) return false;
        if (a[i].data1 != b[i].data1) return false;
        if (a[i].data2 != b[i].data2) return false;
    }
    return true;
}

int main(int argc, char** argv) {
    const char* testMid = (argc > 1) ? argv[1] : "test_files/Test.mid";
    const char* testMid2 = (argc > 2) ? argv[2] : "test_files/Test2.mid";

    // ── Test 1: Parse Test.mid ──
    {
        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        if (!MidiFileParser().parse(testMid, events, tempoMap, timeSigs, &tpb)) {
            return fail("Test.mid: parse returned false");
        }

        // Expected 16 events, all trackId=2
        if ((int)events.size() != 16) {
            return fail("Test.mid: expected 16 events, got %zu", events.size());
        }

        // Ground truth events (tick, status, data1, data2)
        struct Et { int64_t tick; uint8_t st; uint8_t d1; uint8_t d2; };
        Et expected[] = {
            {1920, 0x90, 0x45, 0x20},
            {3840, 0x80, 0x45, 0x00},
            {5760, 0x90, 0x4F, 0x20},
            {7680, 0x90, 0x47, 0x20},
            {7680, 0x80, 0x47, 0x00},
            {7680, 0x90, 0x47, 0x20},
            {7680, 0x90, 0x48, 0x20},
            {8640, 0x80, 0x4F, 0x00},
            {9600, 0x90, 0x3F, 0x20},
            {10080, 0x80, 0x47, 0x00},
            {10080, 0x90, 0x47, 0x20},
            {11520, 0x80, 0x47, 0x00},
            {11520, 0x80, 0x47, 0x00},
            {11520, 0x80, 0x48, 0x00},
            {12000, 0x80, 0x47, 0x00},
            {12480, 0x80, 0x3F, 0x00},
        };

        for (size_t i = 0; i < events.size(); i++) {
            if (events[i].tick != expected[i].tick ||
                events[i].status != expected[i].st ||
                events[i].data1 != expected[i].d1 ||
                events[i].data2 != expected[i].d2) {
                char buf[256];
                snprintf(buf, sizeof(buf),
                    "Test.mid event[%zu]: expected (%lld, 0x%02X, 0x%02X, 0x%02X), "
                    "got (%lld, 0x%02X, 0x%02X, 0x%02X)",
                    i, (long long)expected[i].tick, expected[i].st, expected[i].d1, expected[i].d2,
                    (long long)events[i].tick, events[i].status, events[i].data1, events[i].data2);
                return fail(buf);
            }
            if (events[i].trackId != 2) {
                char buf[128];
                snprintf(buf, sizeof(buf), "Test.mid event[%zu]: trackId=%d, expected 2", i, events[i].trackId);
                return fail(buf);
            }
        }

        // Tempo map: {(0,500000),(30720,500000)}
        if ((int)tempoMap.size() != 2) {
            return fail("Test.mid: expected 2 tempo entries, got %zu", tempoMap.size());
        }
        if (tempoMap[0].first != 0 || tempoMap[0].second != 500000) {
            return fail("Test.mid: tempo[0] mismatch");
        }
        if (tempoMap[1].first != 30720 || tempoMap[1].second != 500000) {
            return fail("Test.mid: tempo[1] mismatch");
        }

        // Time signatures: {(0,{4,4})}
        if ((int)timeSigs.size() != 1) {
            return fail("Test.mid: expected 1 time sig, got %zu", timeSigs.size());
        }
        if (timeSigs[0].first != 0 || timeSigs[0].second.first != 4 || timeSigs[0].second.second != 4) {
            return fail("Test.mid: time sig mismatch");
        }

        // Ticks per beat
        if (tpb != 960) {
            return fail("Test.mid: expected tpb=960, got %d", tpb);
        }

        printf("Test.mid: PASS (16 events, tempo, time sig, tpb)\n");
    }

    // ── Test 2: Parse Test2.mid ──
    {
        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        if (!MidiFileParser().parse(testMid2, events, tempoMap, timeSigs, &tpb)) {
            return fail("Test2.mid: parse returned false");
        }

        if ((int)events.size() != 31) {
            return fail("Test2.mid: expected 31 events, got %zu", events.size());
        }

        // Ground truth (tick, status, data1, data2) — 31 events sorted by tick
        struct Et { int64_t tick; uint8_t st; uint8_t d1; uint8_t d2; };
        Et expected[] = {
            {0, 0xB2, 0x65, 0x00},
            {0, 0xB2, 0x64, 0x00},
            {0, 0xB2, 0x06, 0x0C},
            {0, 0xB2, 0x26, 0x00},
            {0, 0xE2, 0x00, 0x40},
            {0, 0xB2, 0x07, 0x7F},
            {0, 0xB2, 0x0A, 0x40},
            {0, 0xB2, 0x5D, 0x00},
            {0, 0xB2, 0x5B, 0x00},
            {0, 0xB2, 0x5F, 0x00},
            {0, 0xB2, 0x5C, 0x00},
            {0, 0xB2, 0x0B, 0x7F},
            {0, 0xC2, 0x02, 0x00},
            {0, 0x92, 0x47, 0x40},
            {0, 0x92, 0x3B, 0x40},
            {0, 0x92, 0x39, 0x40},
            {1920, 0x82, 0x47, 0x40},
            {1920, 0x82, 0x3B, 0x40},
            {1920, 0x82, 0x39, 0x40},
            {1920, 0x92, 0x43, 0x40},
            {1920, 0x92, 0x3A, 0x40},
            {3840, 0x82, 0x43, 0x40},
            {3840, 0x82, 0x3A, 0x40},
            {3840, 0x92, 0x35, 0x40},
            {4800, 0x82, 0x35, 0x40},
            {4800, 0x92, 0x44, 0x40},
            {5760, 0x82, 0x44, 0x40},
            {5760, 0x92, 0x3F, 0x40},
            {6720, 0x82, 0x3F, 0x40},
            {6720, 0x92, 0x3C, 0x40},
            {7680, 0x82, 0x3C, 0x40},
        };

        for (size_t i = 0; i < events.size(); i++) {
            if (events[i].tick != expected[i].tick ||
                events[i].status != expected[i].st ||
                events[i].data1 != expected[i].d1 ||
                events[i].data2 != expected[i].d2) {
                char buf[256];
                snprintf(buf, sizeof(buf),
                    "Test2.mid event[%zu]: expected (%lld, 0x%02X, 0x%02X, 0x%02X), "
                    "got (%lld, 0x%02X, 0x%02X, 0x%02X)",
                    i, (long long)expected[i].tick, expected[i].st, expected[i].d1, expected[i].d2,
                    (long long)events[i].tick, events[i].status, events[i].data1, events[i].data2);
                return fail(buf);
            }
        }

        // Tempo map: {(0,500000)}
        if ((int)tempoMap.size() != 1) {
            return fail("Test2.mid: expected 1 tempo entry, got %zu", tempoMap.size());
        }
        if (tempoMap[0].first != 0 || tempoMap[0].second != 500000) {
            return fail("Test2.mid: tempo mismatch");
        }

        // Ticks per beat
        if (tpb != 960) {
            return fail("Test2.mid: expected tpb=960, got %d", tpb);
        }

        printf("Test2.mid: PASS (31 events, tempo, tpb)\n");
    }

    // ── Test 3: Round-trip Test2.mid events ──
    {
        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        if (!MidiFileParser().parse(testMid2, events, tempoMap, timeSigs, &tpb)) {
            return fail("Round-trip: Test2.mid parse failed");
        }

        // Write to temp file
        const char* tmpPath = "/tmp/midi_roundtrip_test.mid";
        if (!MidiFileWriter().write(tmpPath, events, 0, 960, 500000)) {
            return fail("Round-trip: write failed");
        }

        // Re-parse
        std::vector<RecordedMidiEvent> events2;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap2;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs2;
        int tpb2 = 0;

        if (!MidiFileParser().parse(tmpPath, events2, tempoMap2, timeSigs2, &tpb2)) {
            return fail("Round-trip: re-parse failed");
        }

        if ((int)events2.size() != 31) {
            return fail("Round-trip: expected 31 events after round-trip, got %zu", events2.size());
        }

        // Compare (tick, status, data1, data2) — trackId not preserved
        for (size_t i = 0; i < events.size(); i++) {
            if (events[i].tick != events2[i].tick ||
                events[i].status != events2[i].status ||
                events[i].data1 != events2[i].data1 ||
                events[i].data2 != events2[i].data2) {
                char buf[256];
                snprintf(buf, sizeof(buf),
                    "Round-trip event[%zu]: expected (%lld, 0x%02X, 0x%02X, 0x%02X), "
                    "got (%lld, 0x%02X, 0x%02X, 0x%02X)",
                    i, (long long)events[i].tick, events[i].status, events[i].data1, events[i].data2,
                    (long long)events2[i].tick, events2[i].status, events2[i].data1, events2[i].data2);
                return fail(buf);
            }
        }

        printf("Round-trip: PASS\n");
    }

    // ── Test 4: Empty events ──
    {
        const char* tmpPath = "/tmp/midi_empty_test.mid";
        std::vector<RecordedMidiEvent> empty;
        if (!MidiFileWriter().write(tmpPath, empty, 0, 960, 500000)) {
            return fail("Empty: write failed");
        }

        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        if (!MidiFileParser().parse(tmpPath, events, tempoMap, timeSigs, &tpb)) {
            return fail("Empty: re-parse failed");
        }

        if ((int)events.size() != 0) {
            return fail("Empty: expected 0 events, got %zu", events.size());
        }

        printf("Empty: PASS\n");
    }

    // ── Test 6: Tempo after channel event (F1 regression) ──
    // Track: CC@tick0 (B0 64 7F) + tempo 600000@tick0 (FF 51 03 09 27 C0) + EOT
    // 600000 µs/qr = 100 BPM. If F1 extra p++ is present, tempo meta is skipped.
    {
        const char* tmpPath = "/tmp/midi_tempo_after_cc.mid";
        FILE* f = fopen(tmpPath, "wb");
        if (!f) return fail("TempoAfterCC: fopen failed");
        // MThd header: fmt=0, 1 track, 960 tpb
        fwrite("MThd", 1, 4, f);
        fwrite("\x00\x00\x00\x06\x00\x00\x00\x01\x03\xC0", 1, 10, f);
        // MTrk header
        // Track data: 00 B0 64 7F  (CC 64@ch1)
        //             00 FF 51 03 09 27 C0  (tempo 600000 = 100 BPM)
        //             00 FF 2F 00  (EOT)
        // Total track len = 2 + 3 + 7 + 3 = 15
        const char trkHeader[] = "MTrk\x00\x00\x00\x0F";
        fwrite(trkHeader, 1, 8, f);
        const char trackData[] = "\x00\xB0\x64\x7F\x00\xFF\x51\x03\x09\x27\xC0\x00\xFF\x2F\x00";
        fwrite(trackData, 1, 15, f);
        fclose(f);

        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        if (!MidiFileParser().parse(tmpPath, events, tempoMap, timeSigs, &tpb)) {
            return fail("TempoAfterCC: parse returned false");
        }
        if ((int)tempoMap.size() != 1) {
            return fail("TempoAfterCC: expected 1 tempo entry, got %zu", tempoMap.size());
        }
        if (tempoMap[0].first != 0 || tempoMap[0].second != 600000) {
            return fail("TempoAfterCC: expected tempo (0,600000), got (%lld,%u)",
                (long long)tempoMap[0].first, tempoMap[0].second);
        }
        printf("TempoAfterCC: PASS\n");
    }

    // ── Test 7: Mid-track tempo change ──
    // Track: tempo 500000@tick0 + CC events + tempo 600000@tick1920 + EOT
    {
        const char* tmpPath = "/tmp/midi_mid_tempo.mid";
        FILE* f = fopen(tmpPath, "wb");
        if (!f) return fail("MidTempo: fopen failed");
        // MThd header: fmt=0, 1 track, 960 tpb
        fwrite("MThd", 1, 4, f);
        fwrite("\x00\x00\x00\x06\x00\x00\x00\x01\x03\xC0", 1, 10, f);
        // MTrk header
        // Track data:
        //   00 FF 51 03 07 A1 20  (tempo 500000)
        //   00 B0 64 7F           (CC 64@ch1)
        //   00 90 45 40           (note on)
        //   07 80 45 00           (delta 1920, note off)
        //   00 FF 51 03 09 27 C0  (tempo 600000 at tick 1920)
        //   00 FF 2F 00           (EOT)
        // Total: 7 + 4 + 4 + 5 + 7 + 4 = 31
        const char trkHeader[] = "MTrk\x00\x00\x00\x1F";
        fwrite(trkHeader, 1, 8, f);
        const char trackData[] =
            "\x00\xFF\x51\x03\x07\xA1\x20"   // tempo 500000 @ tick 0
            "\x00\xB0\x64\x7F"               // CC 64 @ tick 0
            "\x00\x90\x45\x40"               // note on @ tick 0
            "\x8F\x00\x80\x45\x00"           // note off @ tick 1920 (delta=0x0780, VLQ=0x8F,0x00)
            "\x00\xFF\x51\x03\x09\x27\xC0"   // tempo 600000 @ tick 1920
            "\x00\xFF\x2F\x00";              // EOT
        fwrite(trackData, 1, 31, f);
        fclose(f);

        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        if (!MidiFileParser().parse(tmpPath, events, tempoMap, timeSigs, &tpb)) {
            return fail("MidTempo: parse returned false");
        }
        if ((int)tempoMap.size() != 2) {
            return fail("MidTempo: expected 2 tempo entries, got %zu", tempoMap.size());
        }
        if (tempoMap[0].first != 0 || tempoMap[0].second != 500000) {
            return fail("MidTempo: expected tempo[0]=(0,500000), got (%lld,%u)",
                (long long)tempoMap[0].first, tempoMap[0].second);
        }
        if (tempoMap[1].first != 1920 || tempoMap[1].second != 600000) {
            return fail("MidTempo: expected tempo[1]=(1920,600000), got (%lld,%u)",
                (long long)tempoMap[1].first, tempoMap[1].second);
        }
        printf("MidTempo: PASS\n");
    }

    // ── Test 5: Corrupt file ──
    {
        const char* tmpPath = "/tmp/midi_corrupt_test.mid";
        // Write a file with just "MThd" header + junk (not enough bytes for valid parse)
        const char* corrupt = "MThd";
        FILE* f = fopen(tmpPath, "wb");
        if (!f) return fail("Corrupt: fopen failed");
        fwrite(corrupt, 1, 4, f);
        // Write 10 junk bytes
        const char junk[10] = {0,0,0,0,0,0,0,0,0,0};
        fwrite(junk, 1, 10, f);
        fclose(f);

        std::vector<RecordedMidiEvent> events;
        std::vector<std::pair<int64_t, uint32_t>> tempoMap;
        std::vector<std::pair<int64_t, std::pair<int, int>>> timeSigs;
        int tpb = 0;

        bool result = MidiFileParser().parse(tmpPath, events, tempoMap, timeSigs, &tpb);
        if (result) {
            return fail("Corrupt: expected parse to return false");
        }

        printf("Corrupt: PASS\n");
    }

    printf("ALL TESTS PASSED\n");
    return 0;
}