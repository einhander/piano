# F-Droid preparation

Version 0.3.0 uses versionCode 17. The recipe targets source commit
`a2020fa3aff9d59bd811d1a760cffb377702a320`, containing F-Droid preparation
scripts. Keep recipe disabled until that pinned commit is published and reviewed
for F-Droid. Do not move the existing v0.3.0 tag. No submission has been made.

## Source delivery

Copy `com.piano.sequencer.yml` into fdroiddata's `metadata/` and files under
`srclibs/` into fdroiddata's `srclibs/`. Recipe pins LSP modules and GCEM to
immutable Git commits. F-Droid fetches these inputs before build; build does not
download them.

`prepare-fdroid-sources.sh` receives explicit `$$name$$` paths expanded by
F-Droid, copies source trees without Git metadata, and creates a content manifest.
Existing destination trees are refused, not overwritten. `LSP_OFFLINE=1` verifies
prepared inputs before skipping `make fetch`. GCEM headers are supplied to
FluidSynth using `PIANO_GCEM_INCLUDE_DIR` on Gradle invocation.

Generate source-library descriptors from `lsp-sources.lock`:

```sh
python3 app/src/main/cpp/lsp-integration/generate-fdroid-srclibs.py \
  app/src/main/cpp/lsp-integration/lsp-sources.lock fdroid/srclibs
```

Commit pins live in application recipe, not descriptors. Lock tags are
informational; immutable commits are effective pins.

## Verification

fdroidserver 2.4.5 parsed metadata and all 62 source-library descriptors. Enabled
scratch metadata passed lint. Application and pinned LSP sources had no fatal
scanner findings; scanner reported inert FluidSynth audio/test fixtures and a
Java-format `.bin` fixture.

Local `fdroid build --test --stop --no-color --no-refresh com.piano.sequencer:17`
succeeded. APK package `com.piano.sequencer`, versionCode 17, versionName `0.3.0`,
size 9,316,893 bytes, SHA-256
`2fc556e900e584670e7b699d78d57c4d6880122aae9a91772604ce6fcf9615ee`; LSP is
packaged for ARM64, with ARMv7 native engine also present.

This was a local test build, not `fdroid build --server` or a fully
network-isolated build. It used seeded local repository checkout and cached
Gradle wrapper/dependencies; system Gradle was unavailable. This does not validate
F-Droid buildserver compatibility or all licensing/dependency review. Keep recipe
disabled pending source publication and F-Droid review.

Reference: https://f-droid.org/docs/Build_Metadata_Reference/
