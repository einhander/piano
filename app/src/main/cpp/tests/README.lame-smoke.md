# LAME 4.0 host smoke test

Run from repository root. This compiles exactly the `mp3lame` C source list in
`third_party/lame/CMakeLists.txt`, using its generated `config.h` feature
settings and include paths. Output is a complete MP3 produced by encoding
deterministic stereo PCM and flushing the encoder.

```sh
build_dir=/tmp/opencode/lame-host
mkdir -p "$build_dir"
python3 - <<'PY' "$build_dir/config.h"
import pathlib, sys
source = pathlib.Path("app/src/main/cpp/third_party/lame/config.h.in").read_text()
features = "STDC_HEADERS HAVE_MEMCPY HAVE_STDINT_H HAVE_INTTYPES_H HAVE_ERRNO_H HAVE_FCNTL_H HAVE_SYS_STAT_H HAVE_SYS_TYPES_H HAVE_INT8_T HAVE_INT16_T HAVE_INT32_T HAVE_INT64_T HAVE_UINT8_T HAVE_UINT16_T HAVE_UINT32_T HAVE_UINT64_T".split()
for feature in features:
    source = source.replace(f"#undef {feature}", f"#define {feature} 1")
pathlib.Path(sys.argv[1]).write_text(source)
PY
common_flags=(-Iapp/src/main/cpp/third_party/lame/include -I"$build_dir" \
  -Iapp/src/main/cpp/third_party/lame/libmp3lame \
  -Iapp/src/main/cpp/third_party/lame/libmp3lame/.. \
  -Iapp/src/main/cpp/third_party/lame/libmp3lame/vector \
  -DHAVE_CONFIG_H=1 -DSTDC_HEADERS=1 -DHAVE_MEMCPY=1 \
  -DHAVE_STDINT_H=1 -DHAVE_INTTYPES_H=1 -DLAME_LIBRARY_BUILD=1)
sources=(VbrTag bitstream encoder fft gain_analysis id3tag lame newmdct presets \
  psymodel quantize quantize_pvt reservoir set_get tables takehiro util vbrquantize version)
for source in "${sources[@]}"; do
  cc -std=c99 -O2 "${common_flags[@]}" \
    -c "app/src/main/cpp/third_party/lame/libmp3lame/$source.c" \
    -o "$build_dir/$source.o"
done
g++ -std=c++17 -O2 -Iapp/src/main/cpp/third_party/lame/include \
  app/src/main/cpp/tests/lame_encode_smoke_test.cpp "$build_dir"/*.o \
  -lm -o "$build_dir/lame_encode_smoke_test"
"$build_dir/lame_encode_smoke_test" "$build_dir/lame-smoke.mp3"
test -s "$build_dir/lame-smoke.mp3"
```

The exact source archive checksum is documented in the vendor CMake file.
Upstream notices remain in `third_party/lame/COPYING`, `LICENSE`, and `README`.
