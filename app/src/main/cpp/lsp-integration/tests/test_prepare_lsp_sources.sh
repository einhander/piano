#!/usr/bin/env bash
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/prepare-lsp-sources.sh"
REPO="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
tmp="$(mktemp -d "$REPO/.fdroid-source-test.XXXXXX")"; trap 'rm -rf "$tmp"' EXIT
lock="$tmp/lock"
printf 'fixture\thttps://example.invalid/fixture.git\t0123456789012345678901234567890123456789\t1.0\n' > "$lock"
if "$SCRIPT" --verify --root "$tmp/root" --lock "$tmp/empty" >/dev/null 2>&1; then exit 1; fi
if "$SCRIPT" --verify --root "$tmp/root" --lock "$lock" >/dev/null 2>&1; then exit 1; fi
mkdir -p "$tmp/root/modules/fixture" "$tmp/root/gcem/include"
echo fixture > "$tmp/root/modules/fixture/Makefile"
echo header > "$tmp/root/gcem/include/gcem.hpp"
printf '%s' 012ae73c6d0a2cb09ffe86475f5c6fba3926e200 > "$tmp/root/gcem/include/.fdroid-source-marker"
python3 - "$tmp/root" "$lock" <<'PY'
import hashlib,pathlib,sys
r=pathlib.Path(sys.argv[1]); name='fixture'; sha='0123456789012345678901234567890123456789'; p=r/'modules'/name
h=hashlib.sha256(); f=p/'Makefile'; h.update(b'Makefile\0'+f.read_bytes())
(r/'.lsp-source-manifest').write_text(f'{name}\t{sha}\t{h.hexdigest()}\n')
PY
"$SCRIPT" --verify --root "$tmp/root" --lock "$lock"
echo altered >> "$tmp/root/modules/fixture/Makefile"
if "$SCRIPT" --verify --root "$tmp/root" --lock "$lock" >/dev/null 2>&1; then exit 1; fi
mkdir "$tmp/upstream"
git -C "$tmp/upstream" init --quiet
printf 'fixture\n' > "$tmp/upstream/Makefile"
git -C "$tmp/upstream" add Makefile
git -C "$tmp/upstream" -c user.name=Fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
sha="$(git -C "$tmp/upstream" rev-parse HEAD)"
printf 'fixture\t%s\t%s\tfixture\n' "$tmp/upstream" "$sha" > "$tmp/local-lock"
bash "$SCRIPT" --output "$tmp/new-root" --lock "$tmp/local-lock" --modules-only
bash "$SCRIPT" --verify --root "$tmp/new-root" --lock "$tmp/local-lock" --modules-only
[[ -f "$tmp/new-root/modules/fixture/Makefile" ]]
echo 'source verification tests passed'
