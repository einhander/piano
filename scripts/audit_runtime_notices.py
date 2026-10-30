#!/usr/bin/env python3
"""Fetch resolved runtime archives and preserve bundled LICENSE/NOTICE files.

Uses only Python's standard library. Downloads go to the gitignored
.fdroid-validation/license-audit cache, never into source/build inputs.
"""
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/licenses"
RUNTIME = ASSETS / "runtime-dependencies.json"
CACHE = ROOT / ".fdroid-validation/license-audit"


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "piano-license-audit/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read()


def archive_url(pom_url: str, ext: str) -> str:
    return pom_url[:-4] + ext


def is_notice(name: str) -> bool:
    leaf = name.rsplit("/", 1)[-1].lower()
    return bool(re.match(r"^(license|licence|notice|copying)(?:[._-].*)?$", leaf))


def find_notices(blob: bytes, prefix: str = ""):
    """Yield notice members from an archive and nested JAR/ZIP members."""
    with zipfile.ZipFile(BytesIO(blob)) as archive:
        for member in archive.infolist():
            if member.is_dir():
                continue
            name = f"{prefix}{member.filename}"
            raw = archive.read(member)
            if is_notice(member.filename) and raw.strip():
                yield name, raw
            if member.filename.lower().endswith((".jar", ".zip")):
                try:
                    yield from find_notices(raw, name + "!")
                except zipfile.BadZipFile:
                    continue


def main() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    records = []
    texts = {}
    failures = []
    artifacts = json.loads(RUNTIME.read_text(encoding="utf-8"))["artifacts"]
    for artifact in artifacts:
        coordinate = artifact["coordinate"]
        pom_url = artifact["evidence"][0]["url"]
        try:
            # Preserve exact POM bytes as cache evidence and confirm declared packaging.
            pom_file = CACHE / (hashlib.sha256(pom_url.encode()).hexdigest() + ".pom")
            if not pom_file.exists():
                pom_file.write_bytes(fetch(pom_url))
            pom_bytes = pom_file.read_bytes()
            if hashlib.sha256(pom_bytes).hexdigest() != artifact["evidence"][0]["sha256"]:
                raise ValueError("downloaded POM SHA-256 differs from recorded evidence")
            root = ElementTree.fromstring(pom_bytes)
            packaging = next((e.text for e in root if e.tag.endswith("packaging")), None) or "jar"
            if packaging not in ("jar", "aar"):
                records.append({"coordinate": coordinate, "packaging": packaging, "archiveStatus": "no runtime archive"})
                continue
            url = archive_url(pom_url, "." + packaging)
            archive_file = CACHE / (hashlib.sha256(url.encode()).hexdigest() + "." + packaging)
            if not archive_file.exists():
                archive_file.write_bytes(fetch(url))
            archive_bytes = archive_file.read_bytes()
            found = []
            for member_path, raw in find_notices(archive_bytes):
                digest = hashlib.sha256(raw).hexdigest()
                filename = f"Runtime-Notice-{digest}.txt"
                texts.setdefault(filename, raw)
                found.append({"archivePath": member_path, "asset": filename, "sha256": digest})
            records.append({
                "coordinate": coordinate, "packaging": packaging, "archiveUrl": url,
                "archiveSha256": hashlib.sha256(archive_bytes).hexdigest(),
                "archiveStatus": "checked", "notices": found,
            })
        except (OSError, urllib.error.URLError, ValueError, zipfile.BadZipFile, ElementTree.ParseError) as exc:
            failures.append({"coordinate": coordinate, "error": f"{type(exc).__name__}: {exc}"})
            records.append({"coordinate": coordinate, "archiveStatus": "fetch-or-inspection-failed"})

    for filename, raw in texts.items():
        path = ASSETS / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists() and path.read_bytes() != raw:
            raise SystemExit(f"refusing to overwrite changed notice {path}")
        path.write_bytes(raw)
    output = {
        "schemaVersion": 1,
        "source": "runtime-dependencies.json POM URLs; binary archives inspected for ZIP members named LICENSE, LICENCE, NOTICE, or COPYING",
        "checkedArtifactCount": sum(r.get("archiveStatus") == "checked" for r in records),
        "failedArtifactCount": len(failures),
        "artifacts": records,
        "failures": failures,
    }
    target = ASSETS / "runtime-archive-notices.json"
    target.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    index_path = ASSETS / "LICENSES.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    index["components"] = [c for c in index["components"] if c.get("name") != "Resolved runtime archive notices"]
    notice_assets = sorted({n["asset"] for record in records for n in record.get("notices", [])})
    if notice_assets:
        index["components"].append({
            "name": "Resolved runtime archive notices",
            "version": "67 resolved release runtime artifacts; see runtime-archive-notices.json",
            "license": "Apache-2.0 POM-declared artifacts; archive notice contents preserved verbatim where found",
            "licenseFiles": notice_assets,
            "evidenceFiles": ["runtime-archive-notices.json"],
            "source": "Archive URLs recorded per artifact in runtime-archive-notices.json",
            "scopeNote": "Includes direct and nested archive members named LICENSE, LICENCE, NOTICE, or COPYING. Review archiveStatus and failures in evidence report.",
        })
    indexed_assets = {index.get("notice"), "SOURCE.txt"}
    for component in index["components"]:
        indexed_assets.update(component.get("licenseFiles", []))
    index["assetSha256"] = {
        name: hashlib.sha256((ASSETS / name).read_bytes()).hexdigest()
        for name in sorted(indexed_assets)
    }
    index_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    print(f"runtime archives checked: {output['checkedArtifactCount']}/{len(records)}; preserved notice texts: {len(texts)}; failures: {len(failures)}")
    for failure in failures:
        print(f"  {failure['coordinate']}: {failure['error']}", file=sys.stderr)
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
