/*
 * cab_segmented_convolution_test.cpp — host harness for the vendored 0xFX
 * cabinet FFT overlap-add path (third_party/0xFX/src/engine/internal/cab_ir.c).
 *
 * What: proves that fx_cab_process (via fx_engine_process) produces the same
 * output regardless of how the audio stream is cut into blocks. Blocks of an
 * incoming stream are caller-chosen (Oboe callback size), so the overlap-add
 * must carry convolution tail energy across ANY segmentation, not just the
 * uniform 4096-frame block the IR was sized for.
 *
 * Why: fx_cab_process stored the new convolution tail by OVERWRITING
 * overlap_buf each block. With a 2048-sample IR and sub-2048 blocks, the part
 * of the tail that spills past the next block is silently dropped. This test
 * confirms/refutes that empirically and attributes the divergence.
 *
 * Scenarios:
 *   A   engine-level impulse equivalence across 4 segmentations (stock bug
 *       must show here) + cab-bypassed attribution run
 *   A2  cabinet-only unit run vs a float64 time-domain ground-truth
 *       convolution (isolates the cab stage from amp/mic/gate colouring)
 *   B   440 Hz sine at 44.1k/48k through tiny/variable blocks: finite output,
 *       guard slots untouched
 *   C   geometry report: fft_size vs block_n + ir_len - 1
 *   T   per-block cost in ms for n = 64/512/4096 (meaningful only in the
 *       non-sanitized -O2 run; the sanitized run inflates them)
 *
 * Build and run: see README.cab-segmented-convolution.md (exact commands).
 * Exit 0 = all scenarios PASS.
 */
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include "engine_internal.h"
}

namespace {

constexpr int kTotal = 8192;              // frames fed per scenario-A run
constexpr int kIrLen = 2048;              // synthetic IR length (SYNTH_IR_GEN_LEN)
constexpr int kLoadBlock = 4096;          // block_size the engine loads cabs with
constexpr float kEpsRel = 1e-5f;          // PASS: max |diff| <= kEpsRel * ref peak
constexpr float kEpsFloor = 1e-6f;        // never gate below float-noise floor
constexpr float kGtTol = 5e-4f;           // A2: max |diff| vs float64 ground truth
                                         // (bounds float32 FFT round-off; the
                                         // stock truncation error is orders above)

// Scenario-A configuration, recorded verbatim for reproducibility:
//   engine 48000 Hz; default chain only; no pedals; no studio processors;
//   amp FX_AMP_BRIT_CRUNCH, FX_AMP_PARAM_GAIN = 0.0, other amp params at
//   fx_amp_init defaults; cab via fx_cab_generate_ir({FX_CAB_4X12_STRAIGHT,
//   FX_MIC_ON_AXIS, speaker_fs 70, brightness 0.5, resonance 0.6}) — the
//   bundled 4x12 straight preset; mic left at fx_mic_init default (FX_MIC_DI,
//   which fx_mic_process bypasses); noise gate at fx_gate_init defaults.
fx_cab_params_t cab_params() {
    fx_cab_params_t p;
    p.cab_type = FX_CAB_4X12_STRAIGHT;
    p.mic_pos = FX_MIC_ON_AXIS;
    p.speaker_fs = 70.0f;
    p.brightness = 0.5f;
    p.resonance = 0.6f;
    return p;
}

std::vector<int> uniform_chunks(int n) {
    std::vector<int> c;
    for (int pos = 0; pos < kTotal; pos += n) c.push_back(n);
    return c;
}

// Deterministic variable segmentation: repeat the pattern, cap the last
// chunk so the total is exactly kTotal.
std::vector<int> variable_chunks(const int* pattern, size_t len, int total) {
    std::vector<int> out;
    int rem = total;
    for (size_t i = 0; rem > 0; i++) {
        int c = pattern[i % len];
        if (c > rem) c = rem;
        out.push_back(c);
        rem -= c;
    }
    return out;
}

const int kVarA[] = {1, 17, 31, 64, 3, 511, 128, 999};
const int kVarB[] = {1, 2, 3, 7, 17, 64, 97, 511, 1000, 333, 1500, 2000};

float max_abs_diff(const std::vector<float>& a, const std::vector<float>& b) {
    float d = 0.0f;
    for (size_t i = 0; i < a.size(); i++) {
        float x = std::fabs(a[i] - b[i]);
        if (x > d) d = x;
    }
    return d;
}

double energy(const std::vector<float>& v) {
    double e = 0.0;
    for (float f : v) e += (double)f * (double)f;
    return e;
}

float peak_abs(const std::vector<float>& v) {
    float p = 0.0f;
    for (float f : v) p = std::fmax(p, std::fabs(f));
    return p;
}

/* ── A: engine-level impulse runs ──────────────────────────────── */

std::vector<float> run_engine_impulse(float sr, const std::vector<int>& chunks,
                                      bool bypass_cab, bool* ok) {
    std::vector<float> out(kTotal, 0.0f);
    fx_engine_t* e = fx_engine_create(sr);
    if (!e) { *ok = false; return out; }
    fx_amp_set_model(e, FX_CHAIN_DEFAULT, FX_AMP_BRIT_CRUNCH);
    fx_amp_set_param(e, FX_CHAIN_DEFAULT, FX_AMP_PARAM_GAIN, 0.0f);
    fx_cab_params_t p = cab_params();
    *ok = fx_cab_generate_ir(e, FX_CHAIN_DEFAULT, &p);
    if (!*ok) { fx_engine_destroy(e); return out; }
    if (bypass_cab) fx_cab_set_bypass(e, FX_CHAIN_DEFAULT, true);

    std::vector<float> in(kTotal, 0.0f);
    in[0] = 1.0f;  // single unit impulse at sample 0, then silence
    int pos = 0;
    for (int n : chunks) {
        fx_engine_process(e, in.data() + pos, out.data() + pos, n);
        pos += n;
    }
    fx_engine_destroy(e);
    return out;
}

/* ── A2: cabinet-only unit runs + float64 ground truth ─────────── */

int next_pow2(int n) {
    int p = 1;
    while (p < n) p <<= 1;
    return p;
}

// Mirrors fx_cab_load_buffer's peak normalization (0.4 / peak).
float ir_norm_gain(const float* ir, int len) {
    float peak = 0.0f;
    for (int i = 0; i < len; i++) peak = std::fmax(peak, std::fabs(ir[i]));
    return (peak > 1e-6f) ? (0.4f / peak) : 1.0f;
}

// Run the cab standalone (no amp/mic/gate) over the given segmentation.
std::vector<float> run_cab_only(const std::vector<float>& in,
                                const std::vector<int>& chunks,
                                const float* ir, int ir_len, bool* ok) {
    std::vector<float> out = in;
    fx_cab_state_t cab;
    fx_cab_init(&cab);
    *ok = fx_cab_load_buffer(&cab, ir, ir_len, kLoadBlock);
    if (!*ok) { fx_cab_free(&cab); return out; }
    int pos = 0;
    for (int n : chunks) {
        fx_cab_process(&cab, out.data() + pos, n);
        pos += n;
    }
    // Introspect geometry for scenario C.
    std::printf("    [cab] loaded=%d fft_size=%d ir_len=%d block_size=%d\n",
                (int)cab.loaded, cab.fft_size, cab.ir_len, cab.block_size);
    fx_cab_free(&cab);
    return out;
}

std::vector<double> ground_truth(const std::vector<float>& in, const float* ir,
                                 int ir_len, float gain) {
    std::vector<double> y(in.size(), 0.0);
    for (size_t i = 0; i < in.size(); i++) {
        if (in[i] == 0.0f) continue;
        double x = (double)in[i] * gain;
        for (int k = 0; k < ir_len && i + (size_t)k < y.size(); k++) {
            y[i + k] += x * (double)ir[k];
        }
    }
    return y;
}

float max_diff_vs_gt(const std::vector<float>& out, const std::vector<double>& gt) {
    float d = 0.0f;
    for (size_t i = 0; i < out.size(); i++) {
        float x = std::fabs(out[i] - (float)gt[i]);
        if (x > d) d = x;
    }
    return d;
}

/* ── B: sine robustness with guard slots ───────────────────────── */

bool scenario_b_run(float sr, const std::vector<int>& chunks, int* bad_guards,
                    int* nonfinite) {
    constexpr int kGuard = 8;
    const float kSentinel = -1234.5f;
    fx_engine_t* e = fx_engine_create(sr);
    if (!e) return false;
    fx_amp_set_model(e, FX_CHAIN_DEFAULT, FX_AMP_BRIT_CRUNCH);
    fx_amp_set_param(e, FX_CHAIN_DEFAULT, FX_AMP_PARAM_GAIN, 0.0f);
    fx_cab_params_t p = cab_params();
    if (!fx_cab_generate_ir(e, FX_CHAIN_DEFAULT, &p)) {
        fx_engine_destroy(e);
        return false;
    }

    std::vector<float> in(kTotal);
    for (int i = 0; i < kTotal; i++) {
        in[i] = 0.5f * std::sin(2.0 * M_PI * 440.0 * (double)i / (double)sr);
    }
    std::vector<float> buf(2 * kGuard + kTotal, kSentinel);
    float* out = buf.data() + kGuard;

    int pos = 0;
    for (int n : chunks) {
        fx_engine_process(e, in.data() + pos, out + pos, n);
        pos += n;
    }
    fx_engine_destroy(e);

    *bad_guards = 0;
    for (int i = 0; i < kGuard; i++) {
        if (buf[i] != kSentinel || buf[kGuard + kTotal + i] != kSentinel)
            (*bad_guards)++;
    }
    *nonfinite = 0;
    for (int i = 0; i < kTotal; i++) {
        if (!std::isfinite(out[i])) (*nonfinite)++;
    }
    return *bad_guards == 0 && *nonfinite == 0;
}

/* ── T: cab block cost (unit level, engine-exact IR) ───────────── */

void timing_note() {
    float ir[kIrLen];
    fx_cab_params_t p = cab_params();
    fx_cab_synth_ir_generate(&p, ir, kIrLen, 48000.0f);
    fx_cab_state_t cab;
    fx_cab_init(&cab);
    if (!fx_cab_load_buffer(&cab, ir, kIrLen, kLoadBlock)) {
        std::printf("  T   timing: cab load FAILED\n");
        fx_cab_free(&cab);
        return;
    }
    const int sizes[] = {64, 512, 4096};
    constexpr int kBlocks = 512;  // same FFT-call count per size
    std::printf("  T   cab-process cost @48k (fft_size=%d for every block size):\n",
                cab.fft_size);
    double checksum = 0.0;
    std::vector<float> src(kTotal);
    for (int i = 0; i < kTotal; i++) {
        src[i] = 0.5f * std::sin(2.0 * M_PI * 440.0 * (double)i / 48000.0);
    }
    for (int n : sizes) {
        std::vector<float> blk(n);
        auto refill = [&](int it) {  // fresh input per block: process() is
                                     // in-place, feeding output back would
                                     // turn the loop into a recursive filter
            memcpy(blk.data(), src.data() + ((size_t)it * n) % (kTotal - n),
                   sizeof(float) * (size_t)n);
        };
        for (int it = 0; it < 16; it++) {
            refill(it);
            fx_cab_process(&cab, blk.data(), n);
        }
        auto t0 = std::chrono::steady_clock::now();
        for (int it = 0; it < kBlocks; it++) {
            refill(16 + it);
            fx_cab_process(&cab, blk.data(), n);
        }
        auto t1 = std::chrono::steady_clock::now();
        double ms_block =
            std::chrono::duration<double, std::milli>(t1 - t0).count() / kBlocks;
        for (float f : blk) checksum += (double)f;
        std::printf(
            "      n=%4d: %8.4f ms per block (incl. input copy)  ->  %.4f ms "
            "per 1024 frames, %.1fx realtime\n",
            n, ms_block, ms_block * 1024.0 / (double)n,
            (n / (double)48000.0) * 1000.0 / ms_block);
    }
    std::printf("      (checksum %g — keeps the loop live)\n", checksum);
    fx_cab_free(&cab);
}

/* ── main ──────────────────────────────────────────────────────── */

int failures = 0;

void report(const char* name, bool pass, const char* detail) {
    std::printf("  %-58s %s  %s\n", name, pass ? "PASS" : "FAIL", detail);
    if (!pass) failures++;
}

}  // namespace

int main() {
    std::printf("cab segmented-convolution harness (0xFX vendored, pinned SHA "
                "5b24877)\n");
    std::printf("config: %s\n",
                "48k engine / BRIT_CRUNCH gain 0 / cab 4X12_STRAIGHT on-axis "
                "fs70 b0.5 r0.6 / mic DI(default) / gate defaults / no pedals");

    /* ── Scenario A ────────────────────────────────────────────── */
    std::printf("\nScenario A: impulse (unit @0, %d frames) segmentation "
                "equivalence\n", kTotal);
    std::vector<float> ref;
    bool ok = false;
    ref = run_engine_impulse(48000.0f, uniform_chunks(4096), false, &ok);
    if (!ok) {
        std::printf("  engine impulse reference run failed\n");
        return 1;
    }
    float ref_peak = peak_abs(ref);
    float eps = std::fmax(kEpsFloor, kEpsRel * ref_peak);
    std::printf("  reference [4096,4096]: peak = %.6f, eps = %.9f "
                "(max(%.0e, %.0e * peak))\n", ref_peak, eps, kEpsFloor,
                kEpsRel);

    struct Var { const char* name; std::vector<int> chunks; };
    Var vars[] = {
        {"[512 x 16]", uniform_chunks(512)},
        {"[64 x 128]", uniform_chunks(64)},
        {"variable [1,17,31,64,3,511,128,999]...",
         variable_chunks(kVarA, sizeof(kVarA) / sizeof(int), kTotal)},
    };
    char buf[192];
    for (Var& v : vars) {
        std::vector<float> y = run_engine_impulse(48000.0f, v.chunks, false, &ok);
        if (!ok) { report(v.name, false, "run failed"); continue; }
        float d = max_abs_diff(ref, y);
        double de = std::sqrt(energy(y) / std::max(energy(ref), 1e-300));
        std::snprintf(buf, sizeof(buf), "max diff = %.9f (rel %.3e, energy ratio %.6f)",
                      d, (double)(d / std::max(ref_peak, 1e-30f)), de);
        report(v.name, d <= eps, buf);
    }

    // Attribution: identical run with the cabinet stage bypassed.
    std::vector<float> ref_nb = run_engine_impulse(48000.0f, uniform_chunks(4096), true, &ok);
    float max_bypass_diff = 0.0f;
    for (Var& v : vars) {
        std::vector<float> y = run_engine_impulse(48000.0f, v.chunks, true, &ok);
        if (!ok) { report("attribution run", false, "run failed"); continue; }
        max_bypass_diff = std::fmax(max_bypass_diff, max_abs_diff(ref_nb, y));
    }
    std::snprintf(buf, sizeof(buf),
                  "max diff across all segmentations = %.9f (cab bypassed)",
                  max_bypass_diff);
    report("attribution: same matrix with cab bypassed", max_bypass_diff == 0.0f,
           buf);

    /* ── Scenario A2 ───────────────────────────────────────────── */
    std::printf("\nScenario A2: cabinet-only vs float64 ground truth "
                "(tol %.0e abs)\n", kGtTol);
    float ir[kIrLen];
    fx_cab_params_t p = cab_params();
    fx_cab_synth_ir_generate(&p, ir, kIrLen, 48000.0f);
    float gain = ir_norm_gain(ir, kIrLen);
    std::printf("  unit impulse into cab, normalized ir gain = %.6f\n", gain);
    std::vector<float> in_imp(kTotal, 0.0f);
    in_imp[0] = 1.0f;
    std::vector<double> gt = ground_truth(in_imp, ir, kIrLen, gain);
    double gt_peak = 0.0;
    for (double d : gt) gt_peak = std::max(gt_peak, std::fabs(d));
    std::printf("  ground-truth peak = %.6f\n", gt_peak);

    struct Unit { const char* name; std::vector<int> chunks; };
    Unit units[] = {
        {"cab-only [4096,4096] vs ground truth", uniform_chunks(4096)},
        {"cab-only [512 x 16] vs ground truth", uniform_chunks(512)},
        {"cab-only [64 x 128] vs ground truth", uniform_chunks(64)},
        {"cab-only variable [1,17,31,64,3,511,128,999]... vs GT",
         variable_chunks(kVarA, sizeof(kVarA) / sizeof(int), kTotal)},
    };
    for (Unit& u : units) {
        std::vector<float> y = run_cab_only(in_imp, u.chunks, ir, kIrLen, &ok);
        if (!ok) { report(u.name, false, "load failed"); continue; }
        float d = max_diff_vs_gt(y, gt);
        std::snprintf(buf, sizeof(buf), "max diff vs GT = %.9f", d);
        report(u.name, d <= kGtTol, buf);
    }

    /* ── Scenario B ────────────────────────────────────────────── */
    std::printf("\nScenario B: 440 Hz sine, variable blocks incl. size 1, "
                "guards + finiteness\n");
    for (float sr : {44100.0f, 48000.0f}) {
        std::vector<int> chunks =
            variable_chunks(kVarB, sizeof(kVarB) / sizeof(int), kTotal);
        int bad = 0, nonf = 0;
        bool pass = scenario_b_run(sr, chunks, &bad, &nonf);
        std::snprintf(buf, sizeof(buf), "guard slots broken = %d, non-finite = %d",
                      bad, nonf);
        report(sr == 44100.0f ? "sine 440 Hz @ 44100" : "sine 440 Hz @ 48000",
               pass, buf);
    }

    /* ── Scenario C ────────────────────────────────────────────── */
    std::printf("\nScenario C: geometry (fft_size >= n + ir_len - 1)\n");
    {
        int want = next_pow2(kLoadBlock + kIrLen - 1);
        fx_cab_state_t cab;
        fx_cab_init(&cab);
        bool loaded = fx_cab_load_buffer(&cab, ir, kIrLen, kLoadBlock);
        int worst_n = kLoadBlock;  // largest n the process path accepts
        bool geom_ok = loaded && cab.fft_size == want &&
                       cab.fft_size >= worst_n + cab.ir_len - 1 &&
                       cab.fft_size >= 1 + cab.ir_len - 1;
        std::printf(
            "  block_size=%d ir_len=%d fft_size=%d (expected %d): "
            "covers n=1 (%d) up to n=%d (%d) => %s\n",
            cab.block_size, cab.ir_len, cab.fft_size, want,
            1 + kIrLen - 1, worst_n, worst_n + kIrLen - 1,
            geom_ok ? "FFT-SIZED OK" : "VIOLATION");
        std::printf(
            "  note: sizing is NOT the defect; overlap_buf carries only ONE "
            "block of tail (%d samples needed at n=1, single-block carry "
            "holds conv[n..] freshly each call)\n",
            kIrLen - 1);
        fx_cab_free(&cab);
        report("fft_size >= block_n + ir_len - 1 for all processed n",
               geom_ok, geom_ok ? "sized for max block 4096" : "violated");
    }

    /* ── T: timing (opt-in: meaningless under sanitizers) ──────── */
    if (std::getenv("CAB_TIMING")) {
        std::printf("\nTiming note (non-sanitized run):\n");
        timing_note();
    } else {
        std::printf("\nTiming: skipped (set CAB_TIMING=1 in the non-sanitized "
                    "-O2 run; see README)\n");
    }

    std::printf("\n%s (failures: %d)\n", failures == 0 ? "ALL PASS" : "FAILURES",
                failures);
    return failures == 0 ? 0 : 1;
}
