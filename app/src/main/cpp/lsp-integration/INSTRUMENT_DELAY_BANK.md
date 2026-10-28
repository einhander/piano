# Native instrument delay bank

`InstrumentDelayBank` supports 128 independent channel slots. Instances are optional:
the bank starts with zero ring allocations and creates a channel's delay only when
that channel is enabled. Parameter targets can be changed while disabled without
allocating a ring. At 48 kHz, each allocated delay uses about 2.2 MiB; 128 slots
are supported, but allocating all of them (about 281 MiB) is not promised or eager.
`prepare()` re-prepares only existing instances and preserves controls; it and
`resetChannelRange()` require callback quiescence. NativeEngine reset facade holds
FluidSynthEngine's worker-access gate across effects and delay reset.

Native API: `setInstrumentDelayParameter(channel,id,value)`,
`getInstrumentDelayParameter(channel,id)`, `getInstrumentDelayParameterInfo(id,...)`,
`isInstrumentDelayPrepared()`, and `resetInstrumentDelay(channel)`. IDs: 0 enabled,
1 time ms, 2 feedback, 3 wet, 4 sync, 5 division. Each insert defaults disabled.
It receives and returns wet on its own post-insert channel bus only; no shared
send or cross-channel routing. Legacy shared-delay accessors remain temporarily,
but its audio route is disabled.

Each allocation contains two float rings and two uint64 generation rings, each
`ceil(rate*2)+maxFrames+2` samples. At maximum accepted 384 kHz it uses about
17.58 MiB/channel. Allocation occurs on worker during enable; sample-rate
repreparation occurs only at callback-quiescent transition. Callback processing
uses fixed 256-frame stack chunks, with no allocation or blocking.
