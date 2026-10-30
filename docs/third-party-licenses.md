# Third-party licenses and source

Piano Sequencer's original code is GPL-3.0-only (root `LICENSE`). Third-party
components retain their own terms. The app assets `licenses/NOTICE.txt`,
`LICENSES.json`, and `SOURCE.txt` provide offline notices, machine-readable
inventory and source instructions. POM and upstream source evidence is retained
in the adjacent JSON files.

Run `python3 scripts/check_license_assets.py` to validate asset references,
runtime-coordinate/POM-evidence coverage, and 61 pinned LSP notice records.
`python3 scripts/audit_runtime_notices.py` fetches runtime archives into the
ignored `.fdroid-validation/license-audit/` cache, scans archives and nested
JARs for license/notice files, and preserves discovered bytes with archive
paths and hashes. It needs network access and reports per-artifact failures.

## Verified inventory

| Component | License / evidence |
|---|---|
| Oboe | Apache-2.0; upstream `LICENSE` and `AUTHORS` bundled. Revision in `LICENSES.json`. |
| FluidSynth | LGPL-2.1-or-later; upstream `LICENSE` and `AUTHORS` bundled. Revision in `LICENSES.json`. |
| LAME | `include/lame.h`: LGPL-2.0-or-later; `libmp3lame/gain_analysis.c`: LGPL-2.1-or-later. Upstream `COPYING` and `LICENSE` guidance both bundled. |
| LSP integration | 61 modules pinned by `app/src/main/cpp/lsp-integration/lsp-sources.lock`. Per-module `COPYING`/`COPYING.LESSER`/other license notice source paths, commit IDs, and SHA-256 digests recorded in `licenses/LSP-LICENSE-PROVENANCE.json`. Five distinct full notice texts are bundled; terms include GPL-3.0, LGPL-3.0-or-later, SIL-OFL-1.1, ISC-style and MIT. Source headers can add module/component terms; do not assume one license covers whole tree. |
| GCEM | Apache-2.0; exact source `LICENSE` and `NOTICE.txt` bundled. Build-source revision in `LICENSES.json`. |
| Gradle release runtime | All 67 resolved non-BOM coordinates declared Apache-2.0 in original POMs, including Guava's inherited parent declaration. Artifact coordinates, POM URLs, declared names and POM SHA-256 digests in `licenses/runtime-dependencies.json`. |
| Android NDK runtimes | `libc++_shared` and `libomp` are present for both APK ABIs. NDK 26.1.10909125 package `NOTICE` and `NOTICE.toolchain` bundled; these broad files retain upstream notices and LLVM exception text. |

Resolved graph inventory is for the release runtime classpath at recorded
baseline. Three BOM coordinates are constraints, not artifacts, and excluded.
Update both the runtime evidence and coordinate list whenever dependencies
change. POM metadata establishes declared licenses, not absence of extra
embedded notices in binary archives. Upstream archive notice files should be
preserved when present; final APK/source composition may add components beyond
this baseline.

Archive audit result recorded in `licenses/runtime-archive-notices.json`:
All 67 coordinates were accounted for: 64 runtime archives downloaded and
inspected (including nested JARs), and three POM-only entries have no runtime
archive. No fetch/inspection failures remain. No members named
LICENSE/LICENCE/NOTICE/COPYING were found in those archives. This filename-based
check does not prove the absence of differently named embedded notices.

FluidSynth's unused `test/manual` audio and SoundFont fixtures are excluded from
the F-Droid source recipe; they are not app assets. Keep upstream library source
and licenses intact. Review actual LSP plugin inputs and included source headers
when its binary changes.

## Corresponding source and rebuild

Provide the source corresponding to the exact distributed revision, including
the project source, pinned submodules, LSP source revisions and build scripts.
Do not point recipients at an unrelated current branch and claim it matches an
older binary. The public source repository is <https://github.com/einhander/piano>.
For a release, check out its exact published source ref and fetch submodules:

```sh
git clone https://github.com/einhander/piano.git
cd piano
git checkout RELEASE_TAG_OR_COMMIT
git submodule update --init --recursive
./build.sh debug
```

Replace `RELEASE_TAG_OR_COMMIT` with exact published tag or commit.

Build requirements are in `README.md`. To rebuild the optional LSP LADSPA
library, follow `app/src/main/cpp/lsp-integration/PROGRESS.md` and
`build-lsp-ladspa-android.sh`; use the pinned source preparation flow and copy
the output into the documented `lsp-integration/prebuilt/arm64-v8a/` location
before building the app. For LGPL-covered components, retain source, interface
files and scripts needed to rebuild or relink modified components with the
application. Private signing credentials are not needed to rebuild and must
never be distributed as Corresponding Source. Exact revisions/configuration
must be retained with each binary release.

This inventory is evidence of reviewed inputs, not legal advice or a substitute
for checking each actual distribution artifact.
