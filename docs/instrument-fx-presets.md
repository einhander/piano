# Instrument FX presets

Open **FX presets…** inside the instrument effects screen to access the separate
preset submenu. The screen has one shared named preset library across Main,
file instrument sets, and channels. Selecting a preset does not apply it. Apply
changes only the channel and instrument set shown on the screen.

A preset includes the insert rack switch, eight insert effect switches and raw
parameter values, and the channel's Delay settings. It excludes instrument
programs, MIDI mappings, master effects, and global FluidSynth Reverb.

Save current creates a named snapshot; replacing an existing name requires
confirmation. Delete removes the shared snapshot without changing current sound.
The first library initialization adds eleven editable starting points for guitar,
violin, piano, organ, and strings. Existing libraries receive Guitar · Distortion,
Organ · Classic, and Strings · Distortion once, without replacing name collisions
or restoring deleted older presets. Deleting migrated additions does not cause
them to reappear on restart. Organ · Classic approximates modulation with existing
Chorus/Phaser and mild drive; it is not a dedicated Leslie simulation.
Actual sound depends on the selected SoundFont; these presets do not select an
instrument program.

All storage and native control calls run on a worker thread. Descriptor validation
precedes application. Application uses existing individual native setters, not an
atomic audio-boundary transaction: a rejected setter can leave earlier changes
applied, which is reported. Unavailable effects are skipped; MIDI soft-takeover
caches are refreshed after application. Corrupt libraries are not silently reset.

Factory regression tests use `instrument_fx_native_catalog.tsv`, generated from
the native descriptor tables by
`app/src/main/cpp/lsp-integration/tests/generate_instrument_fx_catalog_fixture.sh`.
Regenerate this fixture when changing native parameter identities or bounds.

Automated coverage: `InstrumentFxPresetLibraryTest` checks serialization, storage
failures, initialization, preset recipes, target isolation, validation, and partial
application. Device acceptance still requires saving on one channel, applying on
another, checking unaffected channels/sets, and checking persistence after restart.
