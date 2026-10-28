# Instrument-set effects integration handoff

Nonvisual APIs added for identity-aware rack persistence:

- `MAIN_INSTRUMENT_SET_ID` (`"main"`) identifies legacy Main instruments.
- `resolveInstrumentIdentityChannel(setId, localChannel, base)` maps Main local channels
  directly and File sets to their absolute 0–127 channel; unsupported bases fail `-1`.
- `PlaybackService` overloads accept `(setId, localChannel, ...)` for rack enabled,
  effect enabled/parameter, and instrument-delay parameter writes. Keep old channel-only
  methods for Main compatibility. Resolve set IDs using `FileInstrumentSetStore` on
  worker thread; never fallback unknown IDs to Main.
- File identity prefs use `instrument_fx_v1_{rack|enabled|param}_<setId>_<local>...`
  and `instrument_delay_v2_<setId>_<local>_<id>`. Missing values restore disabled.

`MainActivity` worker startup restores racks over Main and supported persisted sets only when
the optional LSP bundle is available; instrument delays restore independently either way.
Legacy
channel-only rack/parameter keys and v1 delay keys migrate only to Main; migration markers
are written after successful migration. Existing sets at bases above 112 remain persisted,
but are not restored/activated. Global delay send and reverb keys remain unchanged.

UI integration is separate from this nonvisual lane; set-aware API overloads are available.

STEP4B update: `EffectCcMapping.targetInstrumentSetId` now defaults to `main` and is persisted
as optional `targetSetId` JSON field (missing means Main). CC cache/pending identity includes
set ID, local channel, scope, effect, and parameter. Use controller metadata/query/manual-change
set-ID arguments for all set-aware targets. Unknown/deleted/unsupported IDs are logged and do
not route to Main. Worker setters/getters resolve File IDs to absolute channels.

Range lifecycle APIs: `PlaybackService.resetInstrumentSetRange(id)` and
`resetInstrumentChannelRange(base, 16)` are worker-only; native `RESET_CHANNEL_RANGE` command
clears synth held/active note tracking plus sustain/all-sounds/controllers 64/120/121/123 and
resets per-channel rack/delay state under worker callback guard. Ordinary trigger activation
only applies presets and preserves restored effects. Removal gate blocks completion if reset fails.
`MidiFileTriggerController.prepareFileInstrumentSetRemoval(id, callback)` stops/frees known
slots and resets range; both SequencerActivity last-reference cell removal and
InstrumentActivity set removal wait for success before deleting metadata. Sequencer removal
keeps both cell and set on failure; shared-set cell removal skips range reset.
