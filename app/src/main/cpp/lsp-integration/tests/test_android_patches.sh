#!/usr/bin/env bash
# Test real Android patches against pinned, VCS-free module archives.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/../../../../.." && pwd)"
SOURCE="${1:?usage: test_android_patches.sh PINNED_LSP_SOURCE_TREE}"
SOURCE="$(cd "$SOURCE" && pwd)"
tmp="$(mktemp -d "$REPO/.fdroid-source-test.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/modules"
for module in lsp-common-lib lsp-runtime-lib lsp-plugin-fw; do
    sha="$(python3 - "$HERE/lsp-sources.lock" "$module" <<'PY'
import pathlib, sys
rows = [line.split('\t') for line in pathlib.Path(sys.argv[1]).read_text().splitlines()
        if line and not line.startswith('#')]
print(next(row[2] for row in rows if row[0] == sys.argv[2]))
PY
)"
    mkdir "$tmp/modules/$module"
    git -C "$SOURCE/modules/$module" archive "$sha" | tar -x -C "$tmp/modules/$module"
done
git -C "$SOURCE" show HEAD:dependencies.mk > "$tmp/dependencies.mk"
run() {
    LSP_ROOT="$tmp" LSP_ANDROID_INSTANTIATE_DIAGNOSTIC="$1" \
        bash "$HERE/patches/apply-android-patches.sh" > "$tmp/patch.log"
}
run 0
grep -Fq '#define LSP_ANDROID_HAS_QSORT_FALLBACK 1' "$tmp/modules/lsp-common-lib/src/main/stdlib.cpp"
run 0
run 1
run 1
run 0
if find "$tmp/modules" -name .git -print -quit | grep -q .; then
    echo 'Fixture unexpectedly contains Git metadata' >&2
    exit 1
fi
echo 'Android source-only patch tests passed'
