#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LSP_ROOT="${LSP_ROOT:-$HERE/../third_party/lsp}"
LOCK="${LOCK_OVERRIDE:-$HERE/lsp-sources.lock}"
python3 - "$LOCK" "$LSP_ROOT" "$@" <<'PY'
import hashlib, pathlib, shutil, sys
lock, root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
if (len(sys.argv)-3) % 2 or len(sys.argv)<5: raise SystemExit('usage: prepare-fdroid-sources.sh name path [name path ...]')
provided=dict(zip(sys.argv[3::2],sys.argv[4::2]))
entries=[]
for line in lock.read_text().splitlines():
    if line and not line.startswith('#'):
        name,url,sha,tag=line.split('\t'); entries.append((name,sha))
entries.append(('gcem','012ae73c6d0a2cb09ffe86475f5c6fba3926e200'))
if set(provided) != {n for n,s in entries}: raise SystemExit('srclib arguments do not match locked module names plus gcem')
root.mkdir(parents=True,exist_ok=True)
manifest=[]
for name,sha in entries:
    src=pathlib.Path(provided[name])
    if name=='gcem': src=src/'include'
    if not src.is_dir(): raise SystemExit(f'missing F-Droid srclib source: {src}')
    dst=(root/'gcem'/'include') if name=='gcem' else (root/'modules'/name)
    if dst.exists(): raise SystemExit(f'refusing existing destination: {dst}')
    dst.parent.mkdir(parents=True,exist_ok=True)
    shutil.copytree(src,dst,ignore=shutil.ignore_patterns('.git'))
    if name=='gcem':
        if not (dst/'gcem.hpp').is_file(): raise SystemExit(f'missing GCEM header: {dst}')
        (dst/'.fdroid-source-marker').write_text(sha)
        continue
    h=hashlib.sha256()
    for f in sorted(p for p in dst.rglob('*') if p.is_file()):
        h.update(f.relative_to(dst).as_posix().encode()+b'\0'+f.read_bytes())
    manifest.append(f'{name}\t{sha}\t{h.hexdigest()}')
(root/'.lsp-source-manifest').write_text('\n'.join(manifest)+'\n')
PY
bash "$HERE/prepare-lsp-sources.sh" --verify --root "$LSP_ROOT" --lock "$LOCK"
