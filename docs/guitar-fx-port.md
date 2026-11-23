# Guitar FX — 0xFX DSP port

Phase 1: vendored DSP core + NDK static library. Later phases add the adapter,
JNI surface and UI.

## Upstream

- URL: <https://github.com/averagenative/0xFX>
- Pinned SHA: `5b248779c96dce6e64d0ed29cfa598014dc4d3c4` (upstream 0xFX 1.3.0)
- Vendored under `app/src/main/cpp/third_party/0xFX/` (byte-identical to
  upstream except the recorded patch in §Patches; full file list in
  `third_party/0xFX/UPSTREAM.txt`).
- Licenses: 0xFX MIT (`LICENSE`); cJSON MIT, dr_wav Public-Domain-or-MIT-0,
  KissFFT BSD-3-Clause (`LICENSES/`).

## Public API map (verbatim from pinned `src/engine/fx_engine.h`)

All types opaque; downstream C++ must include only `src/engine/fx_engine.h`.

```c
typedef struct fx_engine fx_engine_t;
typedef int32_t fx_pedal_id;
typedef int32_t fx_chain_id;
#define FX_CHAIN_DEFAULT 0
```

Enums:

```c
typedef enum {                      /* 12 amp models + count */
    FX_AMP_FULLERTON_CLEAN = 0, FX_AMP_BRIT_CRUNCH, FX_AMP_SOUTHWEST_LEAD,
    FX_AMP_ESSEX_CHIME, FX_AMP_TWEED_BLUES, FX_AMP_MERIDIAN_HIGH_GAIN,
    FX_AMP_CITRUS_ROAR, FX_AMP_CITRUS_TERROR, FX_AMP_REGENT_800,
    FX_AMP_SOLAR_MONOLITH, FX_AMP_ECLIPSE_DRONE, FX_AMP_EMERALD_DELUXE,
    FX_AMP_COUNT
} fx_amp_type_t;

typedef enum {                      /* 11 amp params + count */
    FX_AMP_PARAM_GAIN = 0, FX_AMP_PARAM_VOLUME, FX_AMP_PARAM_BASS,
    FX_AMP_PARAM_MID, FX_AMP_PARAM_TREBLE, FX_AMP_PARAM_PRESENCE,
    FX_AMP_PARAM_SAG, FX_AMP_PARAM_MASTER, FX_AMP_PARAM_BRIGHT,
    FX_AMP_PARAM_CUT, FX_AMP_PARAM_TONE, FX_AMP_PARAM_FEEDBACK,
    FX_AMP_PARAM_COUNT
} fx_amp_param_t;

typedef enum {                      /* 36 pedal types + count */
    FX_PEDAL_JADE_DRIVE = 0, FX_PEDAL_GOLD_DRIVE, FX_PEDAL_BLUES_GRIT,
    FX_PEDAL_RODENT, FX_PEDAL_ORANGE_DIST, FX_PEDAL_METAL_ZONE,
    FX_PEDAL_AMP_BOX, FX_PEDAL_MAMMOTH_FUZZ, FX_PEDAL_ROUND_FUZZ,
    FX_PEDAL_WRAITH_FUZZ, FX_PEDAL_CHAOS_FUZZ, FX_PEDAL_ECHO_DELAY,
    FX_PEDAL_CARBON_DELAY, FX_PEDAL_TAPE_MACHINE, FX_PEDAL_MEMORY_ECHO,
    FX_PEDAL_DRIP_VERB, FX_PEDAL_PLATE_VERB, FX_PEDAL_HALL_VERB,
    FX_PEDAL_SHIMMER_VERB, FX_PEDAL_CLOUD_VERB, FX_PEDAL_LIQUID_CHORUS,
    FX_PEDAL_PHASE_SWEEP, FX_PEDAL_JET_FLANGER, FX_PEDAL_PULSE_TREM,
    FX_PEDAL_DRIFT_VIBRATO, FX_PEDAL_HOWL_WAH, FX_PEDAL_QUACK_FILTER,
    FX_PEDAL_SQUEEZE_BOX, FX_PEDAL_GLASS_COMP, FX_PEDAL_PUNCH_COMP,
    FX_PEDAL_TONE_SCULPTOR, FX_PEDAL_PRECISION_EQ, FX_PEDAL_NOISE_GATE,
    FX_PEDAL_GRIT_CRUSH, FX_PEDAL_RING_TONE, FX_PEDAL_WARM_TAPE,
    FX_PEDAL_OCTAVE_ENGINE, FX_PEDAL_PITCH_WARP, FX_PEDAL_LOOP_STATION,
    FX_PEDAL_INFINITE_HOLD, FX_PEDAL_GRAIN_CLOUD, FX_PEDAL_TYPE_COUNT
} fx_pedal_type_t;

typedef enum { FX_CHAIN_POS_PRE = 0, FX_CHAIN_POS_POST } fx_chain_pos_t;

typedef enum { FX_CAB_1X12_OPEN = 0, FX_CAB_2X12_CLOSED,
               FX_CAB_4X12_STRAIGHT, FX_CAB_4X12_SLANT,
               FX_CAB_TYPE_COUNT } fx_cab_type_t;
typedef enum { FX_MIC_ON_AXIS = 0, FX_MIC_OFF_AXIS, FX_MIC_EDGE,
               FX_MIC_POS_COUNT } fx_mic_pos_t;
typedef struct {
    fx_cab_type_t cab_type; fx_mic_pos_t mic_pos;
    float speaker_fs; float brightness; float resonance;
} fx_cab_params_t;

typedef enum {                      /* 10 mic types + count */
    FX_MIC_DI = 0, FX_MIC_STAGE_WORKHORSE, FX_MIC_ROADIE_VOCAL,
    FX_MIC_BERLIN_DYNAMIC, FX_MIC_SILVER_BULLET, FX_MIC_VELVET_RIBBON,
    FX_MIC_HERITAGE_RIBBON, FX_MIC_STUDIO_LARGE, FX_MIC_AUSTRIAN_PENCIL,
    FX_MIC_ROOM_PENCIL, FX_MIC_COUNT
} fx_mic_type_t;
typedef enum { FX_MIC_PARAM_DISTANCE = 0, FX_MIC_PARAM_ANGLE,
               FX_MIC_PARAM_POSITION, FX_MIC_PARAM_COUNT } fx_mic_param_t;

typedef enum {                      /* 9 studio types + count */
    FX_STUDIO_IRON_SQUEEZE = 0, FX_STUDIO_GLASS_EQ, FX_STUDIO_REEL_WARMTH,
    FX_STUDIO_BRICK_WALL, FX_STUDIO_VELVET_PRESS, FX_STUDIO_GLUE_BUS,
    FX_STUDIO_VALVE_COLOR, FX_STUDIO_PRECISION_EQ, FX_STUDIO_ROOM_ENGINE,
    FX_STUDIO_COUNT
} fx_studio_type_t;
typedef int fx_studio_id;

typedef enum { FX_LOOP_EMPTY = 0, FX_LOOP_ARMED, FX_LOOP_RECORDING,
               FX_LOOP_PLAYING, FX_LOOP_OVERDUBBING } fx_loop_state_t;
#define FX_LOOPER_SLOT_COUNT 9
```

Functions:

```c
/* lifecycle */
fx_engine_t *fx_engine_create(float sample_rate);
void         fx_engine_destroy(fx_engine_t *engine);
void         fx_engine_process(fx_engine_t *engine,
                               const float *input, float *output,
                               int num_frames);

/* pedals */
fx_pedal_id  fx_chain_add_pedal(fx_engine_t *, fx_pedal_type_t, fx_chain_pos_t);
void         fx_chain_remove_pedal(fx_engine_t *, fx_pedal_id);
void         fx_chain_move_pedal(fx_engine_t *, fx_pedal_id, fx_chain_pos_t, int index);
int          fx_chain_get_pedal_count(fx_engine_t *, fx_chain_pos_t);
fx_pedal_id  fx_chain_get_pedal_at(fx_engine_t *, fx_chain_pos_t, int index);
void         fx_pedal_set_param(fx_engine_t *, fx_pedal_id, int param, float value);
float        fx_pedal_get_param(fx_engine_t *, fx_pedal_id, int param);
void         fx_pedal_set_bypass(fx_engine_t *, fx_pedal_id, bool);
bool         fx_pedal_get_bypass(fx_engine_t *, fx_pedal_id);
fx_pedal_type_t fx_pedal_get_type(fx_engine_t *, fx_pedal_id);
int          fx_pedal_get_param_count(fx_pedal_type_t);
const char  *fx_pedal_get_param_name(fx_pedal_type_t, int param);
const char  *fx_pedal_get_type_name(fx_pedal_type_t);

/* multi-amp routing */
fx_chain_id  fx_chain_create(fx_engine_t *);
void         fx_chain_destroy(fx_engine_t *, fx_chain_id);
int          fx_chain_get_count(fx_engine_t *);
void         fx_chain_set_mix(fx_engine_t *, fx_chain_id, float level);
float        fx_chain_get_mix(fx_engine_t *, fx_chain_id);

/* amp (per chain) */
void          fx_amp_set_model(fx_engine_t *, fx_chain_id, fx_amp_type_t);
fx_amp_type_t fx_amp_get_model(fx_engine_t *, fx_chain_id);
void          fx_amp_set_param(fx_engine_t *, fx_chain_id, fx_amp_param_t, float);
float         fx_amp_get_param(fx_engine_t *, fx_chain_id, fx_amp_param_t);
int           fx_amp_get_param_count(fx_amp_type_t);
const char   *fx_amp_get_param_name(fx_amp_type_t, fx_amp_param_t);
const char   *fx_amp_get_type_name(fx_amp_type_t);

/* cabinet IR (per chain) */
bool         fx_cab_load_ir(fx_engine_t *, fx_chain_id, const char *wav_path);
bool         fx_cab_generate_ir(fx_engine_t *, fx_chain_id, const fx_cab_params_t *);
void         fx_cab_set_bypass(fx_engine_t *, fx_chain_id, bool);
bool         fx_cab_get_bypass(fx_engine_t *, fx_chain_id);
const char  *fx_cab_get_custom_ir_path(fx_engine_t *, fx_chain_id);
const char  *fx_cab_get_custom_name(fx_engine_t *, fx_chain_id);
const char  *fx_cab_get_custom_image_path(fx_engine_t *, fx_chain_id);
void         fx_cab_set_custom_name(fx_engine_t *, fx_chain_id, const char *);
void         fx_cab_set_custom_image_path(fx_engine_t *, fx_chain_id, const char *);
void         fx_cab_clear_custom_ir_path(fx_engine_t *, fx_chain_id);

/* mic simulation */
void          fx_mic_set_type(fx_engine_t *, fx_chain_id, fx_mic_type_t);
fx_mic_type_t fx_mic_get_type(fx_engine_t *, fx_chain_id);
void          fx_mic_set_param(fx_engine_t *, fx_chain_id, fx_mic_param_t, float);
float         fx_mic_get_param(fx_engine_t *, fx_chain_id, fx_mic_param_t);
const char   *fx_mic_get_type_name(fx_mic_type_t);

/* studio processors */
fx_studio_id   fx_studio_add(fx_engine_t *, fx_studio_type_t);
void           fx_studio_remove(fx_engine_t *, fx_studio_id);
void           fx_studio_set_param(fx_engine_t *, fx_studio_id, int, float);
float          fx_studio_get_param(fx_engine_t *, fx_studio_id, int);
void           fx_studio_set_bypass(fx_engine_t *, fx_studio_id, bool);
bool           fx_studio_get_bypass(fx_engine_t *, fx_studio_id);
fx_studio_type_t fx_studio_get_type(fx_engine_t *, fx_studio_id);
const char    *fx_studio_get_type_name(fx_studio_type_t);
int            fx_studio_get_param_count(fx_studio_type_t);
const char    *fx_studio_get_param_name(fx_studio_type_t, int);

/* noise gate */
void  fx_gate_set_threshold(fx_engine_t *, float db);
float fx_gate_get_threshold(fx_engine_t *);
void  fx_gate_set_attack(fx_engine_t *, float ms);
float fx_gate_get_attack(fx_engine_t *);
void  fx_gate_set_release(fx_engine_t *, float ms);
float fx_gate_get_release(fx_engine_t *);
void  fx_gate_set_hold(fx_engine_t *, float ms);
float fx_gate_get_hold(fx_engine_t *);

/* presets */
bool  fx_preset_save(fx_engine_t *, const char *path);
bool  fx_preset_load(fx_engine_t *, const char *path);

/* looper */
void            fx_looper_slot_tap(fx_engine_t *, int slot);
void            fx_looper_slot_mute(fx_engine_t *, int slot);
void            fx_looper_slot_clear(fx_engine_t *, int slot);
void            fx_looper_slot_undo(fx_engine_t *, int slot);
int             fx_looper_arm_next(fx_engine_t *);
void            fx_looper_master_toggle(fx_engine_t *);
bool            fx_looper_master_is_playing(fx_engine_t *);
void            fx_looper_focus_next(fx_engine_t *);
void            fx_looper_set_focus(fx_engine_t *, int slot);
int             fx_looper_focused(fx_engine_t *);
void            fx_looper_set_sync(fx_engine_t *, bool);
bool            fx_looper_get_sync(fx_engine_t *);
void            fx_looper_set_tap_pre_chain(fx_engine_t *, bool);
bool            fx_looper_get_tap_pre_chain(fx_engine_t *);
void            fx_looper_set_master_level(fx_engine_t *, float);
float           fx_looper_get_master_level(fx_engine_t *);
fx_loop_state_t fx_looper_get_slot_state(fx_engine_t *, int slot);
bool            fx_looper_get_slot_muted(fx_engine_t *, int slot);
int             fx_looper_get_slot_length_frames(fx_engine_t *, int slot);
int             fx_looper_get_slot_play_pos(fx_engine_t *, int slot);
int             fx_looper_get_slot_layers(fx_engine_t *, int slot);
bool            fx_looper_export_slot_wav(fx_engine_t *, int slot, const char *);
bool            fx_looper_export_mix_wav(fx_engine_t *, const char *);

/* master volume + metering */
void   fx_engine_set_master_volume(fx_engine_t *, float);  /* 0..1 */
float  fx_engine_get_master_volume(fx_engine_t *);
float  fx_engine_get_input_level(fx_engine_t *);
float  fx_engine_get_output_level(fx_engine_t *);

/* tuner */
float       fx_tuner_get_frequency(fx_engine_t *);
int         fx_tuner_get_note(fx_engine_t *);
float       fx_tuner_get_cents(fx_engine_t *);
const char *fx_tuner_get_note_name(fx_engine_t *);

/* audio device management — standalone only, NOT implemented in the
 * vendored engine subset (declared in the header, defined by the excluded
 * src/audio layer). Do not call from the app. */
int          fx_audio_get_device_count(void);
const char  *fx_audio_get_device_name(int index);
bool         fx_audio_set_device(fx_engine_t *, int index);
bool         fx_audio_set_buffer_size(fx_engine_t *, int frames);
bool         fx_audio_set_sample_rate(fx_engine_t *, float rate);
```

## Internal limits (pinned `engine_internal.h`)

- `FX_MAX_BLOCK_SIZE = 4096` (also scratch buffers; multi-chain path adds a
  4096-float stack array per chain iteration — see hot-path audit).
- `FX_MAX_PEDALS_PER_POS 16`, `FX_MAX_CHAINS 4`, `FX_MAX_PEDALS_TOTAL 64`,
  `FX_MAX_STUDIO_TOTAL 8`, `FX_MAX_PARAMS 12`.
- `fx_engine_process` **clamps** `num_frames` to `FX_MAX_BLOCK_SIZE`
  (`engine.c:84`): frames beyond 4096 are silently dropped, not processed.

## Hot-path call inventory (what `fx_engine_process` invokes)

Bullet list only; full RT audit is a later phase.

- NULL/`num_frames<=0` guard; block clamp to 4096.
- Input peak metering loop (fabsf over block).
- `memcpy` input → `scratch_a`.
- `fx_tuner_feed` — tuner ALWAYS runs on every block (no bypass flag seen).
- Optional `memcpy` to `scratch_tap` when looper tap-pre-chain mode is on.
- `fx_gate_process` (input-stage noise gate).
- `fx_pedal_process` for each PRE pedal; then per-chain:
  - single chain: `fx_amp_process` → `fx_cab_process` → `fx_mic_process`,
    then POST pedals in-place;
  - multi chain: per-chain copy (4096-float **stack** array), amp/cab/mic,
    mix into `scratch_b`, then POST pedals.
- `fx_studio_process_dsp` for each studio processor.
- NaN/inf sanitize loop over the block.
- Looper: `looper_tap` + `looper_process` into `scratch_loop`, mixed additively.
- Master volume + hard clip; `memcpy` to output; output peak metering loop.
- No logging calls inside process; no malloc/free inside process (loop buffers
  are lazy-allocated on first record — adapter-lane concern).
- `create` allocates the engine struct (~4×4096 scratch floats) via malloc —
  worker-thread only.

## Patches

### `patches/cab_ir-segmented-overlap-add.patch` — cabinet overlap-add tail truncation

- **Symptom:** identical sample streams produced different audio depending on
  the callback block size. Measured pre-fix with
  `app/src/main/cpp/tests/cab_segmented_convolution_test.cpp` (impulse @0,
  8192 frames, engine config: 48k, BRIT_CRUNCH gain 0, cab 4X12_STRAIGHT
  fs=70/b=0.5/r=0.6, mic DI, gate defaults; reference segmentation
  [4096,4096], ref peak 0.001031): max abs diff vs reference — `[512×16]`
  1.415e-5, `[64×128]` 6.489e-4, variable `[1,17,31,64,…]` 1.019e-3 against
  eps 1e-6. Cabinet-only unit runs vs float64 ground truth (IR peak
  normalized 0.4): 4096-segmentation 6.0e-8 (correct), `[512×16]` 3.34e-3,
  `[64×128]` 0.209, variable 0.400 (up to 100 % of tail peak lost). Running
  the same matrix with the cab bypassed gave diff exactly 0.0 for every
  segmentation → divergence came solely from the cabinet, not the
  sample-sequential amp/mic/gate biquads (see "Hot-path call inventory").
- **Root cause:** `fx_cab_process()` wrote the new convolution tail into
  `overlap_buf` by OVERWRITE (`overlap_buf[i] = time_buf[n+i]*scale`) each
  block. The tail head is block-alignment correct, but with a 2048-sample IR
  the tail (2047 samples) spills past any block shorter than that; the part
  beyond the next block's emission window was discarded instead of carried.
  `fft_size` sizing itself is fine (`8192 ≥ 4096+2048−1`; scenario C).
- **Fix:** proper overlap-add accumulation — after emitting
  `out[i] = conv[i] + overlap[i]`, rotate the leftover carry forward by n
  (`memmove`), zero the vacated tail (`memset`), then ADD (not replace) the
  new tail. For uniform `n == block_size` the leftover is always zero, so
  nominal engine behavior is bit-identical to upstream.
- **Measured after fix (same harness, ASan+UBSan and -O2):** all scenario-A
  diffs 0.000000000 (bit-exact vs reference); all four A2 segmentations match
  ground truth within 6.0e-8; scenarios B and C PASS; exit 0, no sanitizer
  reports.

## Build

- Target `fx0_engine` (STATIC) in `app/src/main/cpp/CMakeLists.txt`, compiled
  as C (`C_STANDARD 99` with `C_EXTENSIONS ON`, i.e. gnu99, matching upstream
  `CMAKE_C_STANDARD 99`; upstream adds no target-specific flags beyond global
  `-Wall -Wextra -Wpedantic`, which we do not replicate — warning flags are
  not semantic). `POSITION_INDEPENDENT_CODE ON`, links `m`. Linked into
  `native-lib`. Both ABIs (arm64-v8a, armeabi-v7a) verified via
  `./build.sh debug`.
- Host smoke test (validates the vendored set is self-sufficient): see
  `Host smoke test` section for the exact command.

## Host smoke test

Not wired into Gradle (offline proof of self-sufficiency only). From
`app/src/main/cpp/third_party/0xFX/`:

```
gcc -std=gnu99 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Isrc/engine -Isrc/engine/internal -Isrc/core -Ideps \
  src/engine/internal/{engine,amp,biquad,noise_gate,tuner,pedals,studio,cab_ir,mic_sim,preset,looper}.c \
  src/core/log.c deps/cJSON.c deps/kissfft/kiss_fft.c deps/kissfft/kiss_fftr.c \
  /tmp/fx_smoke.c -lm -o /tmp/fx_smoke && /tmp/fx_smoke
```

`fx_smoke.c` calls `fx_engine_create(48000.0f)` →
`fx_chain_add_pedal(e, FX_PEDAL_JADE_DRIVE, FX_CHAIN_POS_PRE)` →
`fx_pedal_set_param(e, p, 0, 0.5f)` → `fx_engine_process` on 64 zero frames →
`fx_engine_destroy`. Result: `SMOKE OK pedal_id=1`, exit 0, no ASan/UBSan
reports. The engine creates without extra setup; no allocation-honesty issues
at create/process/destroy. The file is not committed; it is recreated verbatim
from this description whenever needed. Re-verified after the `cab_ir` overlap-
add patch with the identical command and result (2026-11-23).

A dedicated segmented-convolution harness for the cabinet FFT path lives at
`app/src/main/cpp/tests/cab_segmented_convolution_test.cpp` (commands and
expected output: `app/src/main/cpp/tests/README.cab-segmented-convolution.md`).

## Live rig swap (amp model / cabinet on running rigs — spec §10)

`fx_amp_set_model` (`fx_amp_init` memsets amp state, amp.c:676) and
`fx_cab_generate_ir` (malloc + FFT re-plan, engine.c:514) are NOT live-safe.
Changing the amp model or cabinet of a running rig therefore goes through
the two-phase SAFE LIVE SWAP protocol in `GuitarRigManager`; the interim
"pending flag, apply at quiescence" policy of phase E is removed.

### State table (per channel)

| cell | state | producer (control/worker) | consumer (audio callback) |
|---|---|---|---|
| `mPublished[c]` | CURRENT | write only at instantiate/reprepare/quiescent apply (callback quiesced or lifetime-locked lifecycle ops) | sole runtime reader; takes new value ONLY at commit |
| `mPending[c]` | PENDING | builds the rig fully off-thread, then **CAS nullptr→rig** (fails = busy; never overwrites) | peek-load at top of step; takes it ONLY at commit (`exchange(nullptr)`) |
| `mRetired[c]` | RETIRED | takes it back ONLY under the quiescent contract (drain/apply); reclaims via `syncAdoptLocked` | stores the old CURRENT exactly once per swap, and only when observed empty; otherwise DEFERs the commit to the next block (spec §10.3 — never drops/overwrites) |
| `mSwapArmed[c]` | in-flight flag | sets before publishing, clears at commit (and at quiescent drain) | clears at commit |
| `mSwapCount[c]` | ACK counter | read only (`requestRigSwap` records/benchmarks it) | `+= 1` at commit, release-ordered |

Callback phases (audio-thread-only `mSwapRuntime`, never read by the worker):
IDLE → (wet rig) FADE_OUT → commit → FADE_IN → IDLE; dry rig commits
immediately (silent). The fade-out rides the rig's own level-normalized
dry/wet crossfade with the ramp duration raised to 10 ms
(`GuitarRigEffect::setRampSeconds`); the fade-in uses the same 10 ms on the
new rig; the 5 ms rack convention is restored at completion.

### Ownership rules (the two proofs the protocol must satisfy)

1. **No callback UAF (worker never frees what the callback processes):**
   the callback reads ONLY `mPublished` at any given instant. The worker
   deletes a rig only via `mEffects[c]` (its single owning cell) and only
   when that cell's pointer is parked in `mRetired` (pointer-equality
   guard in `syncAdoptLocked`): a retired rig is by construction NOT
   `mPublished` (the commit's exchange moved the new rig in BEFORE the
   old was parked… and the callback reloads the pointer immediately after
   the state machine before touching it again — TSan verified: a fade
   completion check once ran on the just-retired pointer, fixed by the
   re-load). Un-taken `mPending` is worker-owned and is destroyed only
   under the quiescent contract (`drainAllQuiescent`/`reprepareQuiescent`
   /`resetChannelRange`/`~GuitarRigManager`), which excludes any callback.
2. **No double-destroy / single-slot no-overwrite:** every rig has exactly
   one owner at every instant: worker (owning `unique_ptr`, or the CAS
   slot it just filled) → callback (only between commit-take and
   commit-store, a straight line of code) → RETIRED cell → worker deletes
   via `mEffects.reset(published)` (one delete of the old, adoption of the
   new, same instant). Publish is CAS (a racing occupied slot is reported
   busy, the fresh candidate destroyed by its still-owner). `mRetired` is
   written by the callback exactly once per swap and only from a verified
   IDLE-retired state; the defensive commit path stashes into an empty
   retired cell instead of losing a rig. The destructor's `syncAdopt`
   prevents the ASan-caught "last swapped rig never adopted" leak.

### ACK + busy semantics (UI lane)

`setParameterChecked(kAmpModel|kCabType)` and `requestRigConfig(ch,model,
cab)` return **true = swap accepted** (it lands within ~2×10 ms of callback
processing; `swapCount` will advance), **false = busy** (a previous swap
unconsumed/unreclaimed — honest "применяется на лету" state, also
`hasPendingConfig(ch)` / `swapArmed(ch)`) **or builder failure** (then
`errorState(ch)` ≠ Ok, CURRENT keeps running with the old sound). The
desired row (`mDesired`) is updated on every attempt, accepted or not, so
a busy/rejected change is never lost; `reprepareQuiescent`/`applyPending
Config` apply the latest desired config at quiescence (spec §10.4 quiescent
apply: PENDING → CURRENT directly, old destroyed inline; a superseded
PENDING is destroyed by the worker, never by the callback). Rack-off does
not cancel a pending swap — the silent dry commit path lands it (spec
§10.7). `NativeEngine::shutdown()` detaches the audio callback and then
calls `mGuitarEffects.drainAllQuiescent()`.

### Measured behavior (host x86-64, gcc 14.2, ASan+UBSan + TSan, 2026-11-23)

`tests/guitar_rig_swap_test.cpp` (see `tests/README.guitar-rig-swap.md`):
1000 model/cab alternations against an irregular-block callback thread
(sizes 1,7,16,31,64,512,4096): zero UAF/double-free/leak, frames processed
== frames fed, swapCount == accepted swaps, all outputs finite, busy
rejection observed. Click bound: max consecutive-sample |Δ| of the L bus.
The absolute max|Δ| of any rig here is its own hard-clip edge (measured
≈0.41 per sample for the high-gain model at defaults — a property of the
SOUND, not the swap), so the bound is defined against a no-swap sweep of
EVERY timbre used by the swap schedule at settled full wet: swap-run
max|Δ| must stay within natural-slew max + 0.05 (measured: see README
numbers; the swap-run max equals a timbre's natural clip-edge slew, i.e.
the swap boundaries add no measurable discontinuity). Silent priming
(4 ms, dry-forced window after commit) exists because without it the
fresh-engine DC/gate start-up transient enters the mid-ramp fade-in
directly; the fade itself is per-sample linear at 10 ms each way.
Determinism: identical swap schedule twice → bit-identical settled output
(eps 0). TSan: caught one real protocol bug in this lane (fade-completion
accessing the just-retired rig via a pre-commit pointer — fixed by
re-loading CURRENT before any post-state-machine access); the post-fix
re-run is race-free.

## Limitations / later phases

- Adapter (RT-safe wrapper over the engine), JNI bridge, Kotlin UI: later phases.
- `fx_audio_*` functions are declared but intentionally absent from the
  vendored subset; calling them is a link error by design.
- Looper lazy-allocates record buffers on first record → RT hazard for the
  adapter lane.
- Tuner is hard-wired into the process path (always fed) — metering-only cost.
