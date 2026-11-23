package com.piano.sequencer

/**
 * Guitar FX sound presets (spec §14). Values are RAW native parameter values
 * for the "guitar.rig" descriptor ids (app/src/main/cpp/effects/guitar/
 * GuitarRigParams.h: 0 drive type, 1 drive gain, 2 drive tone, 3 amp model,
 * 4 amp gain, 5 bass, 6 mid, 7 treble, 8 cab type, 9 cab engaged, 10 output,
 * 11 mix). Ranges belong to the native descriptor table; every value below is
 * in bounds (floats 0..1; discrete codes quantized).
 *
 * Discrete code tables (GuitarRigParams.h): drive type 1=Jade Drive,
 * 2=Rodent, 3=Mammoth Fuzz; amp model 0=British Crunch, 1=Regent 800,
 * 2=Meridian High Gain; cab type 0=1x12 Open, 1=2x12 Closed,
 * 2=4x12 Straight, 3=4x12 Slant.
 *
 * Ordering contract: live-safe parameters are written FIRST and the discrete
 * amp model / cabinet LAST, so applying a preset triggers at most one live
 * rig swap; the native discrete-config dedupe turns unchanged selectors into
 * true no-ops. Presets NEVER write the enabled flag — engaging the insert
 * stays the user's switch.
 */
internal object GuitarRigPresets {
    /** Descriptor ids that start a live rig swap when written (spec §10.6). */
    val swapTriggerParameterIds = setOf(3, 8)

    data class Preset(val key: String, val parameters: List<Pair<Int, Float>>)

    /**
     * Classic Rock (spec §14): Jade Drive at the specified ~0.35 into a
     * British Crunch at ~0.4 gain, 4x12 Straight. EQ mid-forward but gentle
     * (bass .45 / mid .55 / treble .55) so the crunch sings without fizz;
     * output at the unity default 0.5, full wet.
     */
    val classicRock = Preset("classic-rock", listOf(
        11 to 1.00f, // mix: fully wet
        10 to 0.50f, // output: unity trim (native default)
        9 to 1.00f,  // cabinet engaged
        7 to 0.55f,  // treble
        6 to 0.55f,  // mid
        5 to 0.45f,  // bass
        4 to 0.40f,  // amp gain — spec anchor
        3 to 0.00f,  // amp model: British Crunch — spec
        2 to 0.50f,  // drive tone: neutral
        1 to 0.35f,  // drive gain — spec anchor
        0 to 1.00f,  // drive type: Jade Drive — spec
        8 to 2.00f   // cab type LAST: 4x12 Straight — spec
    ))

    /**
     * Hard Rock (spec §14): Rodentuzz at a hot 0.55 into a Regent 800 pushed
     * to 0.6, 4x12 Slant. Bass lifted and treble forward for cut (bass .55 /
     * mid .50 / treble .60), tone slightly bright, output at unity, full wet.
     */
    val hardRock = Preset("hard-rock", listOf(
        11 to 1.00f,
        10 to 0.50f,
        9 to 1.00f,
        7 to 0.60f,
        6 to 0.50f,
        5 to 0.55f,
        4 to 0.60f,  // hotter amp gain — spec
        3 to 1.00f,  // amp model: Regent 800 — spec
        2 to 0.55f,
        1 to 0.55f,  // hotter drive — spec
        0 to 2.00f,  // drive type: Rodent — spec
        8 to 3.00f   // cab type LAST: 4x12 Slant — spec
    ))

    /**
     * Modern Metal (spec §14): Jade Drive kept MODERATE (gain .25) purely as
     * a tightness gate in front of the Meridian High Gain at .75; scooped
     * mids (bass .60 / mid .40 / treble .65), tone .60 to keep the top end
     * articulate, 4x12 Straight (spec), output at unity, full wet.
     */
    val modernMetal = Preset("modern-metal", listOf(
        11 to 1.00f,
        10 to 0.50f,
        9 to 1.00f,
        7 to 0.65f,
        6 to 0.40f,
        5 to 0.60f,
        4 to 0.75f,  // high gain — spec
        3 to 2.00f,  // amp model: Meridian High Gain — spec
        2 to 0.60f,
        1 to 0.25f,  // drive moderate — spec
        0 to 1.00f,  // drive type: Jade Drive — spec
        8 to 2.00f   // cab type LAST: 4x12 Straight — spec
    ))

    val all = listOf(classicRock, hardRock, modernMetal)
}
