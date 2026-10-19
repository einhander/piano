# LAME 4.0 minimal encoder-only static library.
# Source archive: lame-4.0.tar.gz (official release); SHA-256:
# 3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb
set(LAME_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(LAME_LIB ${LAME_ROOT}/libmp3lame)
file(READ ${LAME_ROOT}/config.h.in LAME_CONFIG_TEMPLATE)
foreach(feature STDC_HEADERS HAVE_MEMCPY HAVE_STDINT_H HAVE_INTTYPES_H HAVE_ERRNO_H HAVE_FCNTL_H HAVE_SYS_STAT_H HAVE_SYS_TYPES_H HAVE_INT8_T HAVE_INT16_T HAVE_INT32_T HAVE_INT64_T HAVE_UINT8_T HAVE_UINT16_T HAVE_UINT32_T HAVE_UINT64_T)
    string(REPLACE "#undef ${feature}" "#define ${feature} 1" LAME_CONFIG_TEMPLATE "${LAME_CONFIG_TEMPLATE}")
endforeach()
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/config.h "${LAME_CONFIG_TEMPLATE}")

add_library(mp3lame STATIC
    ${LAME_LIB}/VbrTag.c
    ${LAME_LIB}/bitstream.c
    ${LAME_LIB}/encoder.c
    ${LAME_LIB}/fft.c
    ${LAME_LIB}/gain_analysis.c
    ${LAME_LIB}/id3tag.c
    ${LAME_LIB}/lame.c
    ${LAME_LIB}/newmdct.c
    ${LAME_LIB}/presets.c
    ${LAME_LIB}/psymodel.c
    ${LAME_LIB}/quantize.c
    ${LAME_LIB}/quantize_pvt.c
    ${LAME_LIB}/reservoir.c
    ${LAME_LIB}/set_get.c
    ${LAME_LIB}/tables.c
    ${LAME_LIB}/takehiro.c
    ${LAME_LIB}/util.c
    ${LAME_LIB}/vbrquantize.c
    ${LAME_LIB}/version.c
)
target_include_directories(mp3lame PUBLIC ${LAME_ROOT}/include PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${LAME_LIB}
    ${LAME_LIB}/..
    ${LAME_LIB}/vector
)
target_compile_definitions(mp3lame PRIVATE
    HAVE_CONFIG_H=1
    STDC_HEADERS=1
    HAVE_MEMCPY=1
    HAVE_STDINT_H=1
    HAVE_INTTYPES_H=1
    LAME_LIBRARY_BUILD=1
)
target_link_libraries(mp3lame PUBLIC m)
