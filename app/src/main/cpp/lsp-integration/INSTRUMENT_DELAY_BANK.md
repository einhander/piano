# Native instrument delay bank

`InstrumentDelayBank` owns 16 independent `SharedStereoDelay` instances. Native
API for the JNI migration lane: `setInstrumentDelayParameter(channel,id,value)`,
`getInstrumentDelayParameter(channel,id)`, `getInstrumentDelayParameterInfo(id,...)`,
`isInstrumentDelayPrepared()`, and `resetInstrumentDelay(channel)`. IDs: 0 enabled,
1 time ms, 2 feedback, 3 wet, 4 sync, 5 division. Each insert defaults disabled.
It receives and returns wet on its own post-insert channel bus only; no shared
send or cross-channel routing. Legacy shared-delay accessors remain temporarily,
but its audio route is disabled.

Preparation allocates four arrays per delay: two float rings and two uint64
generation rings, each `ceil(rate*2)+2` samples. At 48 kHz this is about 2.20
MiB/channel (about 35.2 MiB for all 16); at maximum accepted 384 kHz it is about
17.58 MiB/channel (about 281.3 MiB total). Allocation occurs only during initial
engine setup or existing sample-rate quiescence gate; any preparation failure
fails that graph transition. Parameter controls are atomic in the underlying
delay and retained when buffers are re-prepared. Callback processing uses fixed
256-frame stack chunks, no allocations or blocking.
