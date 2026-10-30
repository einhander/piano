#!/usr/bin/env python3
"""Validate bundled license index paths and source inventory coverage."""
import json
import re
import sys
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/licenses"
INDEX = ASSETS / "LICENSES.json"
RUNTIME = ASSETS / "runtime-dependencies.json"
LSP_PROVENANCE = ASSETS / "LSP-LICENSE-PROVENANCE.json"


def fail(message: str) -> None:
    print(f"license check: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    try:
        data = json.loads(INDEX.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read LICENSES.json: {exc}")
    if data.get("schemaVersion") != 1 or not isinstance(data.get("components"), list):
        fail("unsupported or malformed index")
    if not data.get("scope"):
        fail("scope warning must be explicit")

    copied_source_texts = {
        "GPL-3.0.txt": ROOT / "LICENSE",
        "Oboe-Apache-2.0.txt": ROOT / "app/src/main/cpp/third_party/oboe/LICENSE",
        "Oboe-AUTHORS.txt": ROOT / "app/src/main/cpp/third_party/oboe/AUTHORS",
        "FluidSynth-LGPL.txt": ROOT / "app/src/main/cpp/third_party/fluidsynth/LICENSE",
        "FluidSynth-AUTHORS.txt": ROOT / "app/src/main/cpp/third_party/fluidsynth/AUTHORS",
        "LAME-COPYING.txt": ROOT / "app/src/main/cpp/third_party/lame/COPYING",
        "LAME-LICENSE.txt": ROOT / "app/src/main/cpp/third_party/lame/LICENSE",
    }
    for asset_name, source in copied_source_texts.items():
        if source.is_file() and (ASSETS / asset_name).is_file():
            if hashlib.sha256(source.read_bytes()).digest() != hashlib.sha256((ASSETS / asset_name).read_bytes()).digest():
                fail(f"copied upstream text differs from source: {asset_name}")

    referenced = {data.get("notice"), "SOURCE.txt"}
    evidence_files = set()
    for component in data["components"]:
        for key in ("name", "license", "source"):
            if not component.get(key):
                fail(f"component missing {key}: {component}")
        files = component.get("licenseFiles")
        if not isinstance(files, list) or not files:
            fail(f"component has no license files: {component['name']}")
        for filename in files:
            path = ASSETS / filename
            if Path(filename).is_absolute() or ".." in Path(filename).parts or not path.is_file() or not path.read_text(errors="replace").strip():
                fail(f"missing/empty/unsafe license file {filename!r}")
            referenced.add(filename)
        coordinates = component.get("coordinates")
        if coordinates is not None and (not isinstance(coordinates, list) or not coordinates):
            fail(f"empty coordinate inventory: {component['name']}")
        evidence_files.update(component.get("evidenceFiles", []))

    for name in referenced:
        if not name or not (ASSETS / name).is_file():
            fail(f"missing indexed notice file {name!r}")
    for name in evidence_files:
        if Path(name).is_absolute() or ".." in Path(name).parts or not (ASSETS / name).is_file():
            fail(f"missing/unsafe evidence file {name!r}")
    asset_hashes = data.get("assetSha256")
    if not isinstance(asset_hashes, dict) or set(asset_hashes) != referenced:
        fail("assetSha256 must cover exactly every indexed notice/license text")
    for name, expected_hash in asset_hashes.items():
        if not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
            fail(f"malformed asset SHA-256: {name}")
        if hashlib.sha256((ASSETS / name).read_bytes()).hexdigest() != expected_hash:
            fail(f"asset SHA-256 mismatch: {name}")

    runtime = json.loads(RUNTIME.read_text(encoding="utf-8"))
    artifacts = runtime.get("artifacts", [])
    coords = [item.get("coordinate") for item in artifacts]
    if len(coords) != 67 or len(set(coords)) != 67:
        fail(f"runtime POM evidence must contain 67 unique artifacts, found {len(set(coords))}")
    for item in artifacts:
        if item.get("license") != "Apache-2.0" or not item.get("evidence"):
            fail(f"runtime artifact lacks verified license evidence: {item.get('coordinate')}")
        for evidence in item["evidence"]:
            if not re.fullmatch(r"[0-9a-f]{64}", evidence.get("sha256", "")) or not evidence.get("url", "").startswith("https://"):
                fail(f"malformed POM evidence for {item['coordinate']}")
    indexed = {
        coordinate
        for component in data["components"]
        for coordinate in component.get("coordinates", [])
    }
    if set(coords) != indexed:
        fail("LICENSES.json coordinate inventory differs from runtime POM evidence")
    if "runtime-dependencies.json" not in evidence_files or not (ASSETS / "runtime-dependencies.json").is_file():
        fail("runtime POM evidence file is not linked from LICENSES.json")
    archive_report = ASSETS / "runtime-archive-notices.json"
    if archive_report.is_file():
        archive_data = json.loads(archive_report.read_text(encoding="utf-8"))
        archive_paths = set()
        for record in archive_data.get("artifacts", []):
            for notice in record.get("notices", []):
                name = notice.get("asset", "")
                path = ASSETS / name
                if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != notice.get("sha256"):
                    fail(f"runtime archive notice missing or hash mismatch: {name}")
                archive_paths.add(name)
        indexed_paths = {
            path
            for component in data["components"]
            if component.get("name") == "Resolved runtime archive notices"
            for path in component.get("licenseFiles", [])
        }
        if archive_paths != indexed_paths:
            fail("runtime archive notice files differ from LICENSES.json")
        print(f"runtime archive notice scan: {archive_data.get('checkedArtifactCount', 0)} checked, {archive_data.get('failedArtifactCount', 0)} fetch/inspection failures")

    lock = ROOT / "app/src/main/cpp/lsp-integration/lsp-sources.lock"
    lock_rows = [line for line in lock.read_text().splitlines() if line and not line.startswith("#")]
    if len(lock_rows) != 61:
        fail(f"expected 61 pinned LSP modules, found {len(lock_rows)}")
    provenance = json.loads(LSP_PROVENANCE.read_text(encoding="utf-8"))
    module_records = provenance.get("modules", [])
    lock_by_name = {row.split("\t", 1)[0]: row.split("\t") for row in lock_rows}
    if len(module_records) != len(lock_rows) or {m.get("name") for m in module_records} != set(lock_by_name):
        fail("LSP license provenance does not cover all pinned modules")
    for module in module_records:
        expected = lock_by_name[module["name"]]
        if module.get("url") != expected[1] or module.get("commit") != expected[2]:
            fail(f"LSP provenance does not match lock for {module['name']}")
        if not module.get("noticeFiles"):
            fail(f"LSP module has no recorded notice paths: {module['name']}")
        for notice in module["noticeFiles"]:
            filename = notice.get("bundledAs")
            if not filename or not (ASSETS / filename).is_file():
                fail(f"LSP upstream notice not bundled: {module['name']} {notice.get('sourcePath')}")
            digest = hashlib.sha256((ASSETS / filename).read_bytes()).hexdigest()
            if digest != notice.get("sha256"):
                fail(f"LSP bundled notice hash mismatch: {filename}")
    supplemental = provenance.get("supplementalNotices", [])
    for notice in supplemental:
        filename = notice.get("bundledAs")
        if not filename or not (ASSETS / filename).is_file():
            fail(f"LSP supplemental notice not bundled: {notice.get('sourcePath')}")
        if hashlib.sha256((ASSETS / filename).read_bytes()).hexdigest() != notice.get("sha256"):
            fail(f"LSP supplemental notice hash mismatch: {filename}")
    for notice in provenance.get("rootNoticeFiles", []):
        filename = notice.get("bundledAs")
        if not filename or not (ASSETS / filename).is_file() or hashlib.sha256((ASSETS / filename).read_bytes()).hexdigest() != notice.get("sha256"):
            fail(f"LSP root notice missing or hash mismatch: {notice.get('sourcePath')}")
    header = provenance.get("sampleHeaderAttribution", {})
    if header.get("bundledAs") != "LSP-NOTICE.txt" or not re.fullmatch(r"[0-9a-f]{64}", header.get("sha256", "")):
        fail("LSP sample header attribution provenance malformed")
    distinct_lsp_texts = {
        notice.get("bundledAs")
        for module in module_records
        for notice in module.get("noticeFiles", [])
    } | {notice.get("bundledAs") for notice in supplemental}
    if not {"LSP-GPL-3.0.txt", "LSP-LGPL-3.0.txt", "LSP-OFL-1.1.txt", "LSP-ISC.txt", "LSP-MIT.txt"}.issubset(distinct_lsp_texts):
        fail("LSP provenance does not cover all five distinct license texts")

    report = ROOT / ".fdroid-validation/release-runtime-dependencies.txt"
    if report.is_file():
        coordinates = set()
        for line in report.read_text(errors="replace").splitlines():
            if " (c)" in line or " - A dependency constraint" in line:
                continue
            match = re.search(r"([\w.-]+):([\w.-]+):([\w.+-]+)(?: -> ([\w.+-]+))?", line)
            if match:
                group, artifact, requested, resolved = match.groups()
                if artifact.endswith("-bom"):
                    continue
                coordinates.add(f"{group}:{artifact}:{resolved or requested}")
        if not coordinates:
            fail("runtime graph exists but no coordinates parsed")
        if coordinates != set(coords):
            fail(f"runtime graph/POM coordinate mismatch: missing evidence={sorted(coordinates-set(coords))}; stale evidence={sorted(set(coords)-coordinates)}")
        print(f"runtime report and POM evidence match: {len(coordinates)} resolved artifacts")
    print(f"license assets valid: {len(data['components'])} components, {len(referenced)} text paths, {len(module_records)} pinned LSP modules")


if __name__ == "__main__":
    main()
