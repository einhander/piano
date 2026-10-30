# F-Droid preparation

This is a disabled draft, not a validated submission. Version 0.3.0 uses
versionCode 17. The recipe must eventually reference a published source commit
containing the preparation scripts; the existing v0.3.0 tag does not contain them.
Do not move that release tag.

## Source delivery

Copy `com.piano.sequencer.yml` into fdroiddata's `metadata/` and the files under
`srclibs/` into fdroiddata's `srclibs/`. The recipe declares each LSP module and
GCEM as a source library with an immutable Git commit. F-Droid obtains these
inputs before the build; the build does not download them.

`prepare-fdroid-sources.sh` receives explicit `$$name$$` paths expanded by
F-Droid, copies source trees without Git metadata, and creates a content manifest.
Existing destination trees are refused, not overwritten. `LSP_OFFLINE=1` verifies
the prepared inputs before skipping `make fetch`. GCEM headers are supplied to
FluidSynth using `PIANO_GCEM_INCLUDE_DIR` on the Gradle invocation.

The source-library descriptors are generated from `lsp-sources.lock`:

```sh
python3 app/src/main/cpp/lsp-integration/generate-fdroid-srclibs.py \
  app/src/main/cpp/lsp-integration/lsp-sources.lock fdroid/srclibs
```

Commit pins live in the application recipe, not in source-library descriptors.
Tags in the lock are informational; immutable commits are the effective pins.
Local preparation can alternatively use `prepare-lsp-sources.sh --output DIR`;
its GCEM archive is checked against upstream's pinned SHA-256.

## Verification

```sh
bash app/src/main/cpp/lsp-integration/tests/test_prepare_lsp_sources.sh
bash app/src/main/cpp/lsp-integration/tests/test_fdroid_source_delivery.sh
./build.sh debug
./gradlew :app:testDebugUnitTest
```

Fixture tests cover source copying, stripped Git metadata, missing/changed
inputs, refusal to overwrite destinations, duplicate source-lock names, and
successful local Git staging. fdroidserver 2.4.5 scanned all 61 pinned LSP
source modules from clean upstream checkouts: zero fatal findings, with one
binary test fixture warning (`lsp-3rd-party/res/test/fmt/java/test-jdk8.bin`).
The scan excludes the app's separately pinned Oboe and FluidSynth submodules.

Current checks: enabled scratch metadata passes `fdroid lint`/`readmeta` with
fdroidserver 2.4.5; complete pinned LSP source modules pass source scanner with
the warning above. A fresh F-Droid-style source delivery also passed
`LSP_OFFLINE=1 build-lsp-ladspa-android.sh`; output is a 9.1 MB AArch64 ELF with
`ladspa_descriptor` and NEEDED entries limited to libdl, libc++_shared, libm,
and libc. Network namespace isolation is unavailable here (`unshare -n` denied),
so this build used curl/wget/git denial stubs; it is weaker evidence than an
isolated F-Droid build. These checks do not validate licenses, all app
submodules, F-Droid buildserver compatibility, Gradle dependency preparation,
or final release commit/version. Recipe remains disabled pending those checks
and a clean isolated app build.

Reference: https://f-droid.org/docs/Build_Metadata_Reference/

## Local validation results (2026-10-30)

- fdroidserver 2.4.5 parses the recipe and 62 source-library descriptors.
  Lint passes on an enabled scratch copy with the Multimedia category configured.
- The application source and 61 LSP modules have no fatal source-scanner findings.
  LSP's third-party test data includes a binary Java-format fixture warning;
  this is not a complete scan of every APK dependency.
- A fresh source-only LSP tree built the ARM64 LADSPA library successfully:
  198 plugin metadata records, zero validator warnings/errors.
- A separate application tree from `git archive`, clean pinned Oboe/FluidSynth
  archives, current integration scripts and that newly built LSP library produced
  `app-release-unsigned.apk` using Gradle `--offline`. Prepared GCEM headers were
  explicitly supplied. No release keystore or signing passwords were copied.
- The APK is 9,316,861 bytes; versionCode 17, versionName `0.3.0~0b16ffc`.
  LSP is packaged for ARM64 only; the app also contains its ARMv7 native engine.

This is a composed local build, not `fdroid build --server`. It reused the local
Gradle cache and separately validated LSP output. Network command wrappers blocked
curl/wget and Git network operations; network namespace isolation was unavailable.
The APK version suffix still differs from the draft recipe's `0.3.0`, and the
recipe's source commit still needs integration. Keep the recipe disabled until
these and the remaining checks above are resolved.
