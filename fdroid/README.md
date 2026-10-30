# F-Droid preparation

The recipe's version, versionCode and full source SHA are synchronized from the
application's release source. It remains disabled while submission is on hold.
No submission has been made.

## Release synchronization

On `master`, run `scripts/prepare-release.sh X.Y.Z` to bump versionCode and
version, commit app source first, then synchronize and commit recipe against that
full immutable source SHA. Script runs debug build and JVM tests; it never tags or
pushes. After checks pass, use printed commands to tag the app-version commit
(not metadata followup) and push explicitly. Recipe commit cannot self-reference.
CI synchronizes metadata to its exact checkout SHA and uploads `fdroid/` as the
`fdroid-metadata` artifact; this artifact is distinct from canonical master
metadata when checkout is the release tag commit.

To repair/check the tracked recipe for the current tagged release:

```sh
python3 scripts/sync_fdroid_recipe.py
python3 scripts/sync_fdroid_recipe.py --check
```

The synchronization updates build/current versions, version codes, the full
source commit SHA, and the Gradle version override together. It preserves
`Disabled` and dependency definitions; it neither publishes nor submits anything.

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

`srclib-name-audit.json` records a check against fdroiddata snapshot
`bcbf58839e496d4054cfb2f53a2a254e543eda45`: 62 requested names compared with
600 upstream entries, zero collisions. It is a dated snapshot, not a guarantee
against future upstream additions.

fdroidserver 2.4.5 parsed metadata and all 62 source-library descriptors. Enabled
scratch metadata passed lint. Application and pinned LSP sources had no fatal
scanner findings; scanner reported inert FluidSynth audio/test fixtures and a
Java-format `.bin` fixture.

Local `fdroid build --test --stop --no-color --no-refresh com.piano.sequencer:19`
succeeded for source `ffb35a6f1833a6f42e41ad92ae4214fccac45889` (`v0.3.2`). APK
package `com.piano.sequencer`, versionCode 19, versionName `0.3.2`, size 9,539,805
bytes, SHA-256
`25bfd75362ef27815376c03686cfb995c0a1722006947d82cc53d0d532f8b831`.
Bundled license assets match the source files byte-for-byte. LSP is packaged for
ARM64, with the ARMv7 native engine also present. The unused FluidSynth manual
fixtures are removed before source scanning; the LSP Java test fixture still
produces a non-fatal scanner warning.

This was a local test build, not `fdroid build --server` or a fully
network-isolated build. It used seeded local repository checkout and cached
Gradle wrapper/dependencies; system Gradle was unavailable. This does not validate
F-Droid buildserver compatibility or replace F-Droid's review. Keep the recipe
disabled until submission is approved.

Reference: https://f-droid.org/docs/Build_Metadata_Reference/
