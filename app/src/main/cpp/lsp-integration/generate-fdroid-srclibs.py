#!/usr/bin/env python3
"""Generate F-Droid srclib declarations from immutable LSP source lock."""
import pathlib
import re
import sys

lock = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
rows = []
for number, line in enumerate(lock.read_text().splitlines(), 1):
    if not line or line.startswith('#'):
        continue
    fields = line.split('\t')
    if len(fields) != 4 or not re.fullmatch(r'[0-9a-f]{40}', fields[2]):
        raise SystemExit(f'{lock}:{number}: malformed lock entry')
    rows.append(fields)
unique = {}
for row in rows:
    if row[0] in unique and unique[row[0]][2] != row[2]:
        raise SystemExit(f'duplicate srclib name has conflicting pins: {row[0]}')
    unique[row[0]] = row
rows = list(unique.values())
out.mkdir(parents=True, exist_ok=True)
if not rows:
    raise SystemExit('source lock has no entries')
for name, url, commit, _tag in rows:
    (out / f'{name}.yml').write_text(
        f'RepoType: git\nRepo: {url}\n'
    )
gcem = out / 'gcem.yml'
gcem.write_text('RepoType: git\nRepo: https://github.com/kthohr/gcem.git\nSubdir: .\n')
print(f'generated {len(rows)} pinned srclib declarations')
