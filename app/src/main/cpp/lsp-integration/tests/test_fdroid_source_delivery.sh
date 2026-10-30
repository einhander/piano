#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
INTEGRATION="$ROOT/app/src/main/cpp/lsp-integration"
tmp="$(mktemp -d "$ROOT/.fdroid-source-test.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/srclibs" "$tmp/lsp/modules"
lock="$tmp/lock"
printf 'fixture\thttps://example.invalid/fixture.git\t0123456789012345678901234567890123456789\t1.0\n' > "$lock"
mkdir -p "$tmp/srclibs/fixture" "$tmp/srclibs/gcem/include"
echo fixture > "$tmp/srclibs/fixture/Makefile"
echo header > "$tmp/srclibs/gcem/include/gcem.hpp"
LOCK_OVERRIDE="$lock" LSP_ROOT="$tmp/lsp" bash "$INTEGRATION/prepare-fdroid-sources.sh" fixture "$tmp/srclibs/fixture" gcem "$tmp/srclibs/gcem"
[[ -f "$tmp/lsp/modules/fixture/Makefile" && ! -e "$tmp/lsp/modules/fixture/.git" ]]
[[ -f "$tmp/lsp/gcem/include/gcem.hpp" ]]
[[ -f "$tmp/lsp/.lsp-source-manifest" ]]
if LOCK_OVERRIDE="$lock" LSP_ROOT="$tmp/lsp" bash "$INTEGRATION/prepare-fdroid-sources.sh" fixture "$tmp/srclibs/fixture" gcem "$tmp/srclibs/gcem" 2>/dev/null; then exit 1; fi
rm "$tmp/lsp/modules/fixture/Makefile"
if bash "$INTEGRATION/prepare-lsp-sources.sh" --verify --root "$tmp/lsp" --lock "$lock" 2>/dev/null; then exit 1; fi
cp "$lock" "$tmp/duplicate-lock"
cat "$lock" >> "$tmp/duplicate-lock"
if bash "$INTEGRATION/prepare-lsp-sources.sh" --verify --root "$tmp/lsp" --lock "$tmp/duplicate-lock" 2>/dev/null; then exit 1; fi
python3 "$INTEGRATION/generate-fdroid-srclibs.py" "$lock" "$tmp/generated"
grep -q 'RepoType: git' "$tmp/generated/fixture.yml"
! grep -q '^Builds:' "$tmp/generated/fixture.yml"
echo 'F-Droid source delivery tests passed'
