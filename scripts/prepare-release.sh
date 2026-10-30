#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$ROOT"

fail() { echo "release preparation: $*" >&2; exit 1; }
[[ $# == 1 ]] || fail "usage: scripts/prepare-release.sh VERSION (for example 0.3.3)"
version=$1
[[ $version =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail "VERSION must be strict semver X.Y.Z"
[[ $(git branch --show-current) == master ]] || fail "must run on master"
[[ -z $(git diff --cached --name-only) ]] || fail "staged changes exist"
git diff --quiet --ignore-submodules=dirty || fail "tracked worktree changes exist"
[[ -z $(git status --porcelain -- app/build.gradle.kts fdroid/com.piano.sequencer.yml) ]] || fail "app version or F-Droid recipe has changes"

python3 - "$version" <<'PY'
import re, subprocess, sys
from pathlib import Path
v=sys.argv[1]
p=Path('app/build.gradle.kts'); text=p.read_text()
versions=re.findall(r'^(\s*val baseVersion = ")([^"\n]+)(")$', text, re.M)
codes=re.findall(r'^(\s*versionCode = )(\d+)(\b.*)$', text, re.M)
if len(versions)!=1 or len(codes)!=1: raise SystemExit('expected one baseVersion and versionCode declaration')
old=tuple(map(int, versions[0][1].split('.'))); new=tuple(map(int,v.split('.')))
if new<=old: raise SystemExit(f'version {v} must be greater than {versions[0][1]}')
tags=subprocess.run(['git','tag','--list'],check=True,text=True,stdout=subprocess.PIPE).stdout.splitlines()
max_code = int(codes[0][1])
for tag in tags:
 m=re.fullmatch(r'v?(\d+\.\d+\.\d+)',tag)
 if m and tuple(map(int,m[1].split('.'))) >= new: raise SystemExit(f'version {v} is not greater than existing tag {tag}')
 if m:
  previous=subprocess.run(['git','show',f'{tag}:app/build.gradle.kts'],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  if previous.returncode == 0:
   found=re.findall(r'^\s*versionCode\s*=\s*(\d+)\b',previous.stdout,re.M)
   if len(found)==1: max_code=max(max_code,int(found[0]))
text=text.replace(versions[0][0]+versions[0][1]+versions[0][2],versions[0][0]+v+versions[0][2],1)
text=text.replace(codes[0][0]+codes[0][1]+codes[0][2],codes[0][0]+str(max_code+1)+codes[0][2],1)
p.write_text(text)
PY

git add -- app/build.gradle.kts
git commit -m "chore(release): bump version to $version"
bump_sha=$(git rev-parse HEAD)
python3 scripts/sync_fdroid_recipe.py --commit "$bump_sha"
git add -- fdroid/com.piano.sequencer.yml
git commit -m "build(fdroid): sync recipe to $version source"

./build.sh debug
./gradlew :app:testDebugUnitTest
echo "Release source commit: $bump_sha"
echo "Checks passed. Publish explicitly:"
echo "  git tag -a v$version $bump_sha -m 'Release $version'"
echo "  git push --atomic origin master v$version"
