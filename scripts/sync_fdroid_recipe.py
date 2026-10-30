#!/usr/bin/env python3
"""Sync F-Droid recipe version fields to an immutable app source commit."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / "fdroid/com.piano.sequencer.yml"
GRADLE = "app/build.gradle.kts"


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", help="source commit (default: exact v<baseVersion> tag)")
    parser.add_argument("--check", action="store_true", help="fail if recipe is stale")
    args = parser.parse_args()
    try:
        if args.commit:
            commit = git("rev-parse", "--verify", "--end-of-options", args.commit + "^{commit}")
        else:
            gradle = (ROOT / GRADLE).read_text()
            version = re.findall(r'^\s*val\s+baseVersion\s*=\s*"([^"\n]+)"', gradle, re.M)
            if len(version) != 1:
                raise ValueError("expected exactly one baseVersion in app/build.gradle.kts")
            commit = git("rev-parse", "--verify", "--end-of-options", f"refs/tags/v{version[0]}^{{commit}}")
        if not re.fullmatch(r"[0-9a-f]{40,64}", commit):
            raise ValueError("git did not resolve source to a full commit hash")
        source = subprocess.run(["git", "show", f"{commit}:{GRADLE}"], cwd=ROOT,
                                check=True, text=True, stdout=subprocess.PIPE).stdout
        versions = re.findall(r'^\s*val\s+baseVersion\s*=\s*"([^"\n]+)"', source, re.M)
        codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\b', source, re.M)
        if len(versions) != 1 or len(codes) != 1:
            raise ValueError("source Gradle file must declare one baseVersion and versionCode")
        version, code = versions[0], codes[0]
        if not re.fullmatch(r"\d+\.\d+\.\d+", version):
            raise ValueError(f"unsupported version format: {version}")
        working = (ROOT / GRADLE).read_text()
        if re.findall(r'^\s*val\s+baseVersion\s*=\s*"([^"\n]+)"', working, re.M) != [version] or re.findall(r'^\s*versionCode\s*=\s*(\d+)\b', working, re.M) != [code]:
            raise ValueError("working app version declarations do not match source commit")
        content = RECIPE.read_text()
        build = re.compile(r'(?ms)^  - versionName: .*?(?=^  - versionName: |^CurrentVersion:)')
        if len(re.findall(r'(?m)^  - versionName:', content)) != 1:
            raise ValueError("expected exactly one F-Droid build entry")
        matches = list(build.finditer(content))
        if len(matches) != 1:
            raise ValueError("expected exactly one F-Droid build entry")
        block = matches[0].group()
        block, n = re.subn(r'(?m)^  - versionName: .*$', f'  - versionName: {version}', block, count=1)
        if n != 1: raise ValueError("missing build versionName")
        block, n = re.subn(r'(?m)^    versionCode: .*$', f'    versionCode: {code}', block, count=1)
        if n != 1: raise ValueError("missing build versionCode")
        block, n = re.subn(r'(?m)^    commit: .*$', f'    commit: {commit}', block, count=1)
        if n != 1: raise ValueError("missing build commit")
        block, n = re.subn(r'(?m)^      - sed -i .*versionName = .*$',
                           f'      - sed -i \'s/versionName = resolvedVersionName/versionName = "{version}"/\' build.gradle.kts', block, count=1)
        if n != 1: raise ValueError("missing versionName prebuild override")
        updated = content[:matches[0].start()] + block + content[matches[0].end():]
        updated, n1 = re.subn(r'(?m)^CurrentVersion: .*$', f'CurrentVersion: {version}', updated, count=1)
        updated, n2 = re.subn(r'(?m)^CurrentVersionCode: .*$', f'CurrentVersionCode: {code}', updated, count=1)
        if n1 != 1 or n2 != 1: raise ValueError("missing CurrentVersion metadata")
        if updated == content:
            print(f"F-Droid recipe already matches {version} ({commit})")
            return 0
        if args.check:
            print(f"F-Droid recipe stale for {version} ({commit})", file=sys.stderr)
            return 1
        fd, tmp = tempfile.mkstemp(dir=RECIPE.parent, prefix=".fdroid-recipe-")
        try:
            with os.fdopen(fd, "w") as stream: stream.write(updated)
            os.replace(tmp, RECIPE)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
        print(f"Updated F-Droid recipe to {version} ({commit})")
        return 0
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f"F-Droid sync error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
