#!/usr/bin/env bash
# Stage the exact LSP module sources described by lsp-sources.lock.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${LSP_ROOT:-$HERE/../third_party/lsp}"
PREPARE_ONLY=0
LOCK_OVERRIDE=""
MODE=prepare
while (($#)); do
  case "$1" in
    --verify) MODE=verify; shift ;;
    --root) ROOT="$2"; shift 2 ;;
    --output) ROOT="$2"; shift 2 ;;
    --lock) LOCK_OVERRIDE="$2"; shift 2 ;;
    --modules-only) PREPARE_ONLY=1; shift ;;
    *) echo "usage: $0 [--verify] [--root DIR|--output DIR]" >&2; exit 2 ;;
  esac
done
if [[ "$MODE" == verify ]]; then ROOT="$(cd "$ROOT" && pwd)"; else mkdir -p "$ROOT"; ROOT="$(cd "$ROOT" && pwd)"; fi
LOCK="${LOCK_OVERRIDE:-$HERE/lsp-sources.lock}"
[[ -s "$LOCK" ]] || { echo "Missing/nonempty source lock: $LOCK" >&2; exit 1; }
python3 - "$LOCK" "$ROOT" "$MODE" <<'PY'
import hashlib, pathlib, re, subprocess, sys
lock, root, mode = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
rows=[]
def tree_hash(path):
    h=hashlib.sha256()
    for f in sorted(p for p in path.rglob('*') if p.is_file() and '.git' not in p.parts):
        h.update(f.relative_to(path).as_posix().encode()+b'\0'+f.read_bytes())
    return h.hexdigest()
for no,line in enumerate(lock.read_text().splitlines(),1):
    if not line or line.startswith('#'): continue
    cols=line.split('\t')
    if len(cols)!=4 or not re.fullmatch(r'[0-9a-f]{40}',cols[2]):
        raise SystemExit(f'{lock}:{no}: expected name<TAB>url<TAB>full-commit<TAB>tag')
    rows.append(cols)
if not rows: raise SystemExit('Source lock has no pinned modules')
names=[row[0] for row in rows]
if len(names)!=len(set(names)):
    duplicate=next(name for name in names if names.count(name)>1)
    raise SystemExit(f'{lock}: duplicate source name: {duplicate}')
for name,url,sha,tag in rows:
    dest=root/'modules'/name
    if mode=='verify':
        if not dest.is_dir() or not (dest/'Makefile').is_file():
            raise SystemExit(f'Missing source directory (VCS metadata not required): {dest}')
        if (dest/'.git').exists():
            got=subprocess.check_output(['git','-C',str(dest),'rev-parse','HEAD'],text=True).strip()
            if got != sha: raise SystemExit(f'{name}: expected {sha}, got {got}')
        else:
            expected={}
            mf=root/'.lsp-source-manifest'
            if mf.exists():
                for line in mf.read_text().splitlines():
                    p=line.split('\t')
                    if len(p)==3: expected[(p[0],p[1])]=p[2]
            if expected.get((name,sha)) != tree_hash(dest):
                raise SystemExit(f'{name}: content hash missing or changed; source manifest required')
    else:
        if dest.exists():
            if not (dest.is_dir() and any(dest.iterdir())): raise SystemExit(f'Refusing to overwrite existing path: {dest}')
            raise SystemExit(f'Source destination already exists; stage elsewhere: {dest}')
        dest.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run(['git','init','--quiet',str(dest)],check=True)
        subprocess.run(['git','-C',str(dest),'remote','add','origin',url],check=True)
        subprocess.run(['git','-C',str(dest),'fetch','--quiet','--depth=1','origin',sha],check=True)
        subprocess.run(['git','-C',str(dest),'checkout','--quiet','--detach',sha],check=True)
        with (root/'.lsp-source-manifest').open('a') as mf:
            mf.write(f'{name}\t{sha}\t{tree_hash(dest)}\n')
    print(f'{name}: {sha}')
PY
# Fluidsynth's clean CMake module requires GCEM archive by pinned revision/hash.
[[ "$PREPARE_ONLY" == 1 ]] && exit 0
GCEM_REV=012ae73c6d0a2cb09ffe86475f5c6fba3926e200
GCEM_SHA=28159274c54e9640354852e172d10d88eb159f4e7f2fea42edbcd20105ed3526
GCEM_DIR="$ROOT/gcem/include"
if [[ "$MODE" == verify ]]; then
  [[ -f "$GCEM_DIR/gcem.hpp" ]] || { echo "Missing staged GCEM headers: $GCEM_DIR" >&2; exit 1; }
  [[ -f "$GCEM_DIR/.fdroid-source-marker" ]] || { echo "Missing staged GCEM pin marker: $GCEM_DIR" >&2; exit 1; }
  [[ "$(<"$GCEM_DIR/.fdroid-source-marker")" == "012ae73c6d0a2cb09ffe86475f5c6fba3926e200" ]] || { echo "Incorrect GCEM revision marker" >&2; exit 1; }
else
  mkdir -p "$ROOT/.source-cache"
  archive="$ROOT/.source-cache/gcem-$GCEM_REV.zip"
  mkdir -p "$ROOT/.source-cache/gcem-unpack"
  if [[ ! -f "$archive" ]]; then
    curl --fail --location "https://github.com/kthohr/gcem/archive/$GCEM_REV.zip" -o "$archive.tmp"
    echo "$GCEM_SHA  $archive.tmp" | sha256sum --check
    mv "$archive.tmp" "$archive"
  fi
  echo "$GCEM_SHA  $archive" | sha256sum --check
  [[ ! -e "$GCEM_DIR" ]] || { echo "Refusing to overwrite $GCEM_DIR" >&2; exit 1; }
  unzip -q "$archive" -d "$ROOT/.source-cache/gcem-unpack"
  mkdir -p "$GCEM_DIR"
  cp -a "$ROOT/.source-cache/gcem-unpack/gcem-$GCEM_REV/include/." "$GCEM_DIR/"
  printf '%s' "$GCEM_REV" > "$GCEM_DIR/.fdroid-source-marker"
fi
