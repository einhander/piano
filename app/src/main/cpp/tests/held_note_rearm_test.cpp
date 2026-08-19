// B3 regression test:
//   Part A — FluidSynthEngine held-note tracking + re-arm (MIDI-thread semantics):
//            keyboard note-on (timestamp 0) → file note-off (timestamp > 0) for the
//            same (channel, note) must re-arm the held note; a keyboard's own
//            note-off must NOT re-arm; file-only note-offs must NOT re-arm.
//   Part B — MidiFilePlayer timestamp convention: every file-origin event pushed
//            into the live MIDI queue must carry timestamp > 0 (live keyboard is
//            == 0). Covers fired events (tick-0 event in a small first callback)
//            and flush note-offs (STOP at near-zero tick, natural end / loop wrap).
//
// Build (host, from repo root):
//   cmake -S app/src/main/cpp/third_party/fluidsynth -B /tmp/opencode/fs-host \
//     -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
//     -Denable-alsa=off -Denable-jack=off -Denable-oss=off -Denable-pulseaudio=off \
//     -Denable-pipewire=off -Denable-sdl3=off -Denable-dbus=off -Denable-midishare=off \
//     -Denable-network=off -Denable-readline=off -Denable-libsndfile=off \
//     -Denable-ladspa=off -Denable-native-dls=off -Denable-signalsmith=off \
//     -Denable-opensles=off -Denable-oboe=off -Denable-aufile=off -Denable-portaudio=off
//   cmake --build /tmp/opencode/fs-host --target libfluidsynth -j
//   g++ -std=c++17 -I app/src/main/cpp \
//     -I app/src/main/cpp/engine -I app/src/main/cpp/synth \
//     -I app/src/main/cpp/realtime -I app/src/main/cpp/midi \
//     -I app/src/main/cpp/third_party/fluidsynth/include \
//     -I /tmp/opencode/fs-host/include \
//     app/src/main/cpp/synth/FluidSynthEngine.cpp \
//     app/src/main/cpp/realtime/MidiQueue.cpp \
//     app/src/main/cpp/engine/MidiFilePlayer.cpp \
//     app/src/main/cpp/midi/MidiFileParser.cpp \
//     app/src/main/cpp/midi/MidiFileWriter.cpp \
//     app/src/main/cpp/tests/held_note_rearm_test.cpp \
//     /tmp/opencode/fs-host/src/libfluidsynth.a -lm -lpthread -lgomp \
//     -o /tmp/opencode/held_note_rearm_test
// Run (from repo root):
//   /tmp/opencode/held_note_rearm_test [path/to/test.sf2]

#include "synth/FluidSynthEngine.h"
#include "engine/MidiFilePlayer.h"
#include "engine/MidiRecorder.h"  // RecordedMidiEvent (complete type)
#include "midi/MidiFileWriter.h"
#include "realtime/MidiQueue.h"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <vector>

static int fail(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    fprintf(stderr, "FAIL: %s\n", buf);
    return 1;
}

static MidiMessage mkMsg(uint8_t status, uint8_t d1, uint8_t d2, int64_t ts) {
    MidiMessage m;
    m.status = status;
    m.data1 = d1;
    m.data2 = d2;
    m.timestamp = ts;
    return m;
}

int main(int argc, char** argv) {
    const char* sf2Path = (argc > 1) ? argv[1]
        : "app/src/main/cpp/third_party/fluidsynth/sf2/VintageDreamsWaves-v2.sf2";

    // ── Part A: held-note re-arm (FluidSynthEngine, MIDI-thread semantics) ──
    {
        FluidSynthEngine synth;
        if (!synth.init(48000, 512)) {
            return fail("A: init failed");
        }
        if (synth.loadSoundFont(sf2Path) < 0) {
            return fail("A: loadSoundFont failed: %s", sf2Path);
        }

        // A1: keyboard note-on (ts=0) → file note-off (ts>0) → re-arm;
        //     keyboard note-off (ts=0) → no re-arm (own note-off clears bitmap first).
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 60, 100, 0) };
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 0) {
                return fail("A1: re-arm on plain keyboard note-on (got %d)", synth.getRearmCount());
            }

            b = { mkMsg(0x80, 60, 0, 5) };  // file note-off, same (ch, note)
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 1) {
                return fail("A1: expected re-arm after file note-off (got %d)", synth.getRearmCount());
            }

            b = { mkMsg(0x80, 60, 0, 0) };  // keyboard's own note-off
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 1) {
                return fail("A1: spurious re-arm on keyboard's own note-off (got %d)", synth.getRearmCount());
            }
        }

        // A2: vel-0 file note-on is a note-off (MIDI convention) → re-arm.
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 61, 100, 0) };
            synth.processLiveMidi(b);

            b = { mkMsg(0x90, 61, 0, 7) };  // vel-0 note-on = note-off, file origin
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 2) {
                return fail("A2: expected re-arm after vel-0 file note-on (got %d)", synth.getRearmCount());
            }

            b = { mkMsg(0x80, 61, 0, 0) };
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 2) {
                return fail("A2: spurious re-arm (got %d)", synth.getRearmCount());
            }
        }

        // A3: file-only note-on/note-off (no keyboard involvement) → no re-arm.
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 62, 100, 9) };
            synth.processLiveMidi(b);

            b = { mkMsg(0x80, 62, 0, 11) };
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 2) {
                return fail("A3: spurious re-arm without keyboard (got %d)", synth.getRearmCount());
            }
        }

        // A4: keyboard note-on + note-off only → no re-arm.
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 63, 100, 0) };
            synth.processLiveMidi(b);

            b = { mkMsg(0x80, 63, 0, 0) };
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 2) {
                return fail("A4: spurious re-arm (got %d)", synth.getRearmCount());
            }
        }

        // A5: non-note events (CC / program change / pitch bend) do not touch the
        //     bitmap — a file note-off after them still re-arms a held note.
        {
            std::vector<MidiMessage> b = {
                mkMsg(0x90, 64, 100, 0),  // keyboard note-on
                mkMsg(0xB0, 7, 100, 0),   // CC (live)
                mkMsg(0xC0, 0, 0, 3),     // program change (file)
                mkMsg(0xE0, 0, 0x40, 4),  // pitch bend (file)
                mkMsg(0x80, 64, 0, 6),    // file note-off → re-arm
            };
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 3) {
                return fail("A5: expected re-arm (got %d)", synth.getRearmCount());
            }

            b = { mkMsg(0x80, 64, 0, 0) };
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != 3) {
                return fail("A5: spurious re-arm (got %d)", synth.getRearmCount());
            }
        }

        printf("ReArm: PASS (A1-A5)\n");
    }

    // ── Part B: MidiFilePlayer timestamp convention (file events must be > 0) ──
    {
        // Write a tiny file: note-on C4 @tick 0, note-off @tick 1920.
        const char* tmpPath = "/tmp/opencode/held_note_rearm_test.mid";
        std::vector<RecordedMidiEvent> events = {
            {0, 0x90, 60, 100, 0},
            {1920, 0x80, 60, 0, 0},
        };
        if (!MidiFileWriter().write(tmpPath, events, 0, 960, 500000)) {
            return fail("B: write test .mid failed");
        }

        // B1: small first callback (16 frames @48kHz/120bpm/960ppq → 0.64 ticks,
        // (int64_t) == 0 before the clamp) fires the tick-0 note-on; then STOP in
        // the next callback flushes the active note at ~0.68 ticks (also < 1).
        // Every message must carry timestamp >= 1.
        {
            MidiFilePlayer player;
            if (player.load(0, tmpPath, 120.0f, false, -1, true) != 0) {
                return fail("B1: load failed");
            }
            MidiQueue q(256);
            player.process(16, 48000, 0, &q);
            player.stop(0);
            player.process(1, 48000, 16, &q);

            int count = 0;
            MidiMessage m;
            while (q.pop(m)) {
                if (m.timestamp < 1) {
                    return fail("B1: file event with timestamp %lld (must be > 0)",
                                (long long)m.timestamp);
                }
                count++;
            }
            if (count != 2) {
                return fail("B1: expected 2 messages (fired note-on + flush note-off), got %d", count);
            }
        }

        // B2: run to natural end (48000 frames = 1920 ticks) — fired events and
        // the loop-wrap flush must all carry timestamp >= 1.
        {
            MidiFilePlayer player;
            if (player.load(0, tmpPath, 120.0f, false, -1, true) != 0) {
                return fail("B2: load failed");
            }
            MidiQueue q(256);
            player.process(48000, 48000, 0, &q);

            int count = 0;
            MidiMessage m;
            while (q.pop(m)) {
                if (m.timestamp < 1) {
                    return fail("B2: file event with timestamp %lld (must be > 0)",
                                (long long)m.timestamp);
                }
                count++;
            }
            if (count != 2) {
                return fail("B2: expected 2 messages (note-on + note-off), got %d", count);
            }
        }

        printf("TimestampConvention: PASS (B1-B2)\n");
    }

    // ── Part C: loop-wrap flush with a note held across the boundary (the
    //    actual B3 scenario) ──
    // File: note-on C4 @tick 0 with NO note-off + a CC @tick 1920 (gives the
    // file a length so the loop wrap fires). At the wrap, flushActiveNotes
    // emits a note-off for the still-active (0, 60).
    {
        const char* tmpPath = "/tmp/opencode/held_note_rearm_loopwrap.mid";
        std::vector<RecordedMidiEvent> events = {
            {0, 0x90, 60, 100, 0},
            {1920, 0xB0, 7, 100, 0},
        };
        if (!MidiFileWriter().write(tmpPath, events, 0, 960, 500000)) {
            return fail("C: write loop-wrap .mid failed");
        }

        // C1: keyboard holds (0, 60) → the wrap flush must re-arm it.
        {
            FluidSynthEngine synth;
            if (!synth.init(48000, 512)) {
                return fail("C1: init failed");
            }
            if (synth.loadSoundFont(sf2Path) < 0) {
                return fail("C1: loadSoundFont failed");
            }

            std::vector<MidiMessage> kb = { mkMsg(0x90, 60, 100, 0) };
            synth.processLiveMidi(kb);

            MidiFilePlayer player;
            if (player.load(0, tmpPath, 120.0f, true, -1, true) != 0) {
                return fail("C1: load failed");
            }
            MidiQueue q(256);
            // 48000 frames @48kHz/120bpm/960ppq = 1920 ticks → loop wrap + flush
            player.process(48000, 48000, 0, &q);

            std::vector<MidiMessage> file;
            MidiMessage m;
            while (q.pop(m)) {
                file.push_back(m);
            }
            // Expect: note-on, CC, flush note-off, re-fired note-on
            if (file.size() != 4) {
                return fail("C1: expected 4 file events, got %zu", file.size());
            }
            if (file[2].status != 0x80 || file[2].data1 != 60) {
                return fail("C1: expected flush note-off at index 2, got 0x%02X note %d",
                            file[2].status, file[2].data1);
            }

            int rearmBefore = synth.getRearmCount();
            synth.processLiveMidi(file);
            if (synth.getRearmCount() != rearmBefore + 1) {
                return fail("C1: expected re-arm on wrap flush while keyboard holds (got %d)",
                            synth.getRearmCount());
            }
            if (synth.getLastRearmVelocity() != 100) {
                return fail("C1: re-arm used vel %d, expected 100", synth.getLastRearmVelocity());
            }
        }

        // C2: keyboard NOT holding → the wrap flush must NOT re-arm.
        {
            FluidSynthEngine synth;
            if (!synth.init(48000, 512)) {
                return fail("C2: init failed");
            }
            if (synth.loadSoundFont(sf2Path) < 0) {
                return fail("C2: loadSoundFont failed");
            }

            MidiFilePlayer player;
            if (player.load(0, tmpPath, 120.0f, true, -1, true) != 0) {
                return fail("C2: load failed");
            }
            MidiQueue q(256);
            player.process(48000, 48000, 0, &q);

            std::vector<MidiMessage> file;
            MidiMessage m;
            while (q.pop(m)) {
                file.push_back(m);
            }
            if (file.size() != 4) {
                return fail("C2: expected 4 file events, got %zu", file.size());
            }

            int rearmBefore = synth.getRearmCount();
            synth.processLiveMidi(file);
            if (synth.getRearmCount() != rearmBefore) {
                return fail("C2: spurious re-arm on wrap flush without keyboard (got %d)",
                            synth.getRearmCount());
            }
        }

        printf("LoopWrapFlush: PASS (C1-C2)\n");
    }

    // ── Part D: keyboard release between flush and re-arm (both orderings) +
    //    re-press at a new velocity ──
    {
        FluidSynthEngine synth;
        if (!synth.init(48000, 512)) {
            return fail("D: init failed");
        }
        if (synth.loadSoundFont(sf2Path) < 0) {
            return fail("D: loadSoundFont failed");
        }

        // D1: flush note-off first, then keyboard note-off in the same batch →
        // re-arm happens, then the keyboard note-off kills the re-armed voice
        // (final: silent) and clears the bitmap. Bitmap-clear is proven
        // behaviorally: a later file note-off must NOT re-arm.
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 65, 100, 0) };
            synth.processLiveMidi(b);

            b = { mkMsg(0x80, 65, 0, 5), mkMsg(0x80, 65, 0, 0) };  // flush, then keyboard off
            int before = synth.getRearmCount();
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != before + 1) {
                return fail("D1: expected re-arm from flush before keyboard release (got %d)",
                            synth.getRearmCount());
            }

            b = { mkMsg(0x80, 65, 0, 6) };  // later file note-off
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != before + 1) {
                return fail("D1: spurious re-arm after keyboard release (got %d)",
                            synth.getRearmCount());
            }
        }

        // D2: keyboard note-off first, then flush note-off → no re-arm
        // (bitmap already clear).
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 66, 100, 0) };
            synth.processLiveMidi(b);

            b = { mkMsg(0x80, 66, 0, 0), mkMsg(0x80, 66, 0, 5) };  // keyboard off, then flush
            int before = synth.getRearmCount();
            synth.processLiveMidi(b);
            if (synth.getRearmCount() != before) {
                return fail("D2: spurious re-arm after keyboard released first (got %d)",
                            synth.getRearmCount());
            }
        }

        // D3: re-press at a new velocity — the re-arm must use the LATEST
        // keyboard velocity (mHeldVel updated on each live note-on).
        {
            std::vector<MidiMessage> b = { mkMsg(0x90, 67, 100, 0) };
            synth.processLiveMidi(b);

            b = { mkMsg(0x80, 67, 0, 5) };  // file note-off → re-arm @100
            synth.processLiveMidi(b);
            if (synth.getLastRearmVelocity() != 100) {
                return fail("D3: first re-arm used vel %d, expected 100", synth.getLastRearmVelocity());
            }

            b = { mkMsg(0x80, 67, 0, 0) };  // keyboard release
            synth.processLiveMidi(b);

            b = { mkMsg(0x90, 67, 60, 0) };  // re-press at vel 60
            synth.processLiveMidi(b);

            b = { mkMsg(0x80, 67, 0, 7) };  // file note-off → re-arm @60
            synth.processLiveMidi(b);
            if (synth.getLastRearmVelocity() != 60) {
                return fail("D3: second re-arm used vel %d, expected 60", synth.getLastRearmVelocity());
            }
        }

        printf("ReleaseOrdering: PASS (D1-D3)\n");
    }

    printf("ALL TESTS PASSED\n");
    return 0;
}