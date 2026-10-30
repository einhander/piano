import json
import re
import unittest
from pathlib import Path


class SrcLibAuditReportTest(unittest.TestCase):
    def test_snapshot_report_covers_requested_names_without_collisions(self):
        report = json.loads((Path(__file__).parents[1] / "fdroid/srclib-name-audit.json").read_text())
        self.assertEqual(report["upstream_sha"], "bcbf58839e496d4054cfb2f53a2a254e543eda45")
        self.assertEqual(report["requested_names_count"], 62)
        self.assertEqual(report["snapshot_srclib_entry_count"], 600)
        self.assertEqual(report["conflicts"], [])
        self.assertEqual(report["errors"], [])
        self.assertEqual(len(set(report["requested_names"])), 62)
        root = Path(__file__).parents[1]
        recipe = (root / "fdroid/com.piano.sequencer.yml").read_text()
        recipe_names = re.findall(r"^      - ([a-z0-9-]+)@", recipe, re.M)
        descriptor_names = sorted(path.stem for path in (root / "fdroid/srclibs").glob("*.yml"))
        self.assertEqual(set(recipe_names), set(report["requested_names"]))
        self.assertEqual(set(descriptor_names), set(report["requested_names"]))


if __name__ == "__main__": unittest.main()
