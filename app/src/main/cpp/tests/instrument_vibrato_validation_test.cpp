// Host validation test for the per-channel vibrato API (FluidSynth generator
// offsets). Imitates instrument_release_validation_test.cpp: drives the
// PRODUCTION FluidSynthEngine against a real (host-built) FluidSynth + SF2.
//
// Coverage:
//   - pre-init sets STASH the desired state and are readable via the getter;
//   - rejection of invalid channel (-1, 128), non-finite, and out-of-range
//     rate (0.49 / 12.01), depth (-0.01 / 100.01), delay (-0.01 / 2000.01);
//   - acceptance of boundary values (rate 0.5 / 12, depth 0 / 100,
//     delay 0 / 2000);
//   - rejections never mutate stored state;
//   - per-channel independence;
//   - getter round-trip incl. "disable keeps the numeric values";
//   - queue overflow: a rejected write keeps the last accepted state;
//   - channel-range reset zeroes (disables + restores defaults for) the
//     stored state of the range only;
//   - init + SF2 load/swap replay the stored state (getter unchanged).
//
// Build/run: see README.instrument-vibrato.md.

#include "../synth/FluidSynthEngine.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <memory>

#define CHECK(expr) \
    do { \
        if (!(expr)) { \
            std::fprintf(stderr, "FAIL: %s (line %d)\n", #expr, __LINE__); \
            return 1; \
        } \
    } while (0)

int main(int argc, char** argv) {
    assert(argc == 2);
    const char* sf2Path = argv[1];
    auto engine = std::make_unique<FluidSynthEngine>();

    // ── Pre-init: setters stash desired state; getter reads it back ──────
    CHECK(engine->setChannelVibrato(0, true, 7.5f, 40.0f, 120.0f));
    bool en = true;
    float r = 0.0f, d = 0.0f, dl = 0.0f;
    CHECK(engine->getChannelVibrato(0, en, r, d, dl));
    CHECK(en && r == 7.5f && d == 40.0f && dl == 120.0f);

    // Rejections (pre-init): invalid channel, non-finite, out of range.
    CHECK(!engine->setChannelVibrato(-1, true, 5.0f, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(128, true, 5.0f, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, 0.49f, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, 12.01f, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, NAN, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, INFINITY, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, -INFINITY, 25.0f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, 5.0f, -0.01f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, 5.0f, 100.01f, 0.0f));
    CHECK(!engine->setChannelVibrato(0, true, 5.0f, 25.0f, NAN));
    CHECK(!engine->setChannelVibrato(0, true, 5.0f, 25.0f, -0.01f));
    CHECK(!engine->setChannelVibrato(0, true, 5.0f, 25.0f, 2000.01f));
    // Rejections must not have mutated the stored state:
    CHECK(engine->getChannelVibrato(0, en, r, d, dl));
    CHECK(en && r == 7.5f && d == 40.0f && dl == 120.0f);

    // Accepted boundaries (pre-init):
    CHECK(engine->setChannelVibrato(1, true, 0.5f, 0.0f, 0.0f));          // min rate/depth/delay
    CHECK(engine->setChannelVibrato(2, true, 12.0f, 100.0f, 2000.0f));    // max rate/depth/delay
    CHECK(engine->setChannelVibrato(3, false, 5.0f, 25.0f, 0.0f));        // defaults, disabled
    CHECK(engine->setChannelVibrato(127, true, 8.0f, 60.0f, 250.0f));

    // Per-channel independence:
    CHECK(engine->getChannelVibrato(0, en, r, d, dl));
    CHECK(en && r == 7.5f && d == 40.0f && dl == 120.0f);
    CHECK(engine->getChannelVibrato(1, en, r, d, dl));
    CHECK(en && r == 0.5f && d == 0.0f && dl == 0.0f);
    CHECK(engine->getChannelVibrato(2, en, r, d, dl));
    CHECK(en && r == 12.0f && d == 100.0f && dl == 2000.0f);
    CHECK(engine->getChannelVibrato(3, en, r, d, dl));
    CHECK(!en && r == 5.0f && d == 25.0f && dl == 0.0f);
    CHECK(engine->getChannelVibrato(127, en, r, d, dl));
    CHECK(en && r == 8.0f && d == 60.0f && dl == 250.0f);
    // Untouched channels keep the constructor defaults (disabled, 5/25/0):
    CHECK(engine->getChannelVibrato(4, en, r, d, dl));
    CHECK(!en && r == 5.0f && d == 25.0f && dl == 0.0f);

    // Invalid channels for the getter:
    CHECK(!engine->getChannelVibrato(-1, en, r, d, dl));
    CHECK(!engine->getChannelVibrato(128, en, r, d, dl));

    // ── Init + SF2: stashed state survives (applied via applyDesiredState) ─
    CHECK(engine->init(48000, 256));
    CHECK(engine->loadSoundFont(sf2Path) >= 0);
    engine->setReverb(false);
    engine->setChorus(false);
    CHECK(engine->getChannelVibrato(0, en, r, d, dl));
    CHECK(en && r == 7.5f && d == 40.0f && dl == 120.0f);
    CHECK(engine->getChannelVibrato(127, en, r, d, dl));
    CHECK(en && r == 8.0f && d == 60.0f && dl == 250.0f);

    // Post-init round-trip (validate -> push -> store):
    CHECK(engine->setChannelVibrato(5, true, 3.0f, 75.0f, 500.0f));
    CHECK(engine->getChannelVibrato(5, en, r, d, dl));
    CHECK(en && r == 3.0f && d == 75.0f && dl == 500.0f);
    // Disabling keeps the numeric values (the UI re-enables with them):
    CHECK(engine->setChannelVibrato(5, false, 3.0f, 75.0f, 500.0f));
    CHECK(engine->getChannelVibrato(5, en, r, d, dl));
    CHECK(!en && r == 3.0f && d == 75.0f && dl == 500.0f);
    // A rejected post-init write must not mutate stored state:
    CHECK(!engine->setChannelVibrato(5, true, 13.0f, 75.0f, 500.0f));
    CHECK(engine->getChannelVibrato(5, en, r, d, dl));
    CHECK(!en && r == 3.0f && d == 75.0f && dl == 500.0f);

    // Drain the queued commands (applies the offsets to both synth slots).
    engine->beginSynthAccess();
    engine->processCommands();
    engine->endSynthAccess();

    // Drained-MIDI CC121 path (processOneMidi) re-applies the stored state;
    // exercise the code path and confirm the getter is untouched.
    {
        MidiMessage cc{};
        cc.status = 0xB0;
        cc.data1 = 121;
        cc.channel = 5;
        engine->beginSynthAccess();
        engine->processOneMidi(cc);
        engine->endSynthAccess();
    }
    CHECK(engine->getChannelVibrato(5, en, r, d, dl));
    CHECK(!en && r == 3.0f && d == 75.0f && dl == 500.0f);

    // Queue overflow (capacity 256, same pattern as the release test): the
    // last REJECTED write must not change the desired state.
    {
        int accepted = 0;
        float lastRate = 0.0f;
        for (int i = 0; i < 400; ++i) {
            const float value = 0.5f + 11.5f * (i / 399.0f); // in-range sweep
            if (engine->setChannelVibrato(15, true, value, 25.0f, 0.0f)) {
                ++accepted;
                lastRate = value;
            }
        }
        CHECK(accepted > 0 && accepted < 400);
        CHECK(engine->getChannelVibrato(15, en, r, d, dl));
        CHECK(en && r == lastRate && d == 25.0f && dl == 0.0f);
    }
    engine->beginSynthAccess();
    engine->processCommands();
    engine->endSynthAccess();

    // Channel-range reset: clears (disables) the stored state inside the
    // range, restores documented defaults for the numeric fields, and leaves
    // channels outside the range untouched.
    CHECK(engine->setChannelVibrato(0, true, 6.0f, 30.0f, 100.0f));
    CHECK(engine->setChannelVibrato(15, true, 4.0f, 20.0f, 50.0f));
    CHECK(engine->setChannelVibrato(16, true, 9.0f, 40.0f, 300.0f));
    CHECK(engine->resetChannelRange(0, 16));
    CHECK(engine->getChannelVibrato(0, en, r, d, dl));
    CHECK(!en && r == 5.0f && d == 25.0f && dl == 0.0f);
    CHECK(engine->getChannelVibrato(15, en, r, d, dl));
    CHECK(!en && r == 5.0f && d == 25.0f && dl == 0.0f);
    CHECK(engine->getChannelVibrato(16, en, r, d, dl));
    CHECK(en && r == 9.0f && d == 40.0f && dl == 300.0f);
    engine->beginSynthAccess();
    engine->processCommands();
    engine->endSynthAccess();

    // Slot swap (SF2 reload) replays the desired state on the prepared slot;
    // the getter must still read back the stored values afterwards.
    CHECK(engine->setChannelVibrato(127, true, 11.0f, 90.0f, 1500.0f));
    CHECK(engine->loadSoundFont(sf2Path) >= 0);
    CHECK(engine->getChannelVibrato(127, en, r, d, dl));
    CHECK(en && r == 11.0f && d == 90.0f && dl == 1500.0f);
    engine->beginSynthAccess();
    engine->processCommands();
    engine->endSynthAccess();

    std::printf("vibrato validation: all checks passed\n");
    return 0;
}
