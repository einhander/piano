import importlib.util
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("sync_fdroid_recipe.py")


class SyncRecipeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=SCRIPT.parents[1], prefix=".fdroid-validation-")
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        (self.root / "app").mkdir()
        (self.root / "fdroid").mkdir()
        self.script = self.root / "scripts/sync_fdroid_recipe.py"
        self.script.write_text(SCRIPT.read_text())
        (self.root / "app/build.gradle.kts").write_text('val baseVersion = "1.2.3"\nversionCode = 7\n')
        (self.root / "fdroid/com.piano.sequencer.yml").write_text(
            'Builds:\n  - versionName: 1.2.3\n    versionCode: 7\n    commit: old\n'
            '    prebuild:\n      - sed -i \'s/versionName = resolvedVersionName/versionName = "1.2.3"/\' build.gradle.kts\n'
            'CurrentVersion: 1.2.3\nCurrentVersionCode: 7\n')
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.name", "Test"], check=True)
        self.commit()
        self.sha = self.git("rev-parse", "HEAD")
        self.git("tag", "v1.2.3")

    def tearDown(self): self.tmp.cleanup()
    def git(self, *args): return subprocess.run(["git", "-C", str(self.root), *args], check=True, text=True, stdout=subprocess.PIPE).stdout.strip()
    def commit(self):
        self.git("add", "."); self.git("commit", "-qm", "fixture")
    def run_script(self, *args):
        return subprocess.run(["python3", str(self.script), *args], cwd=self.root, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_tag_and_explicit_commit_idempotent(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.run_script("--commit", self.sha).returncode, 0)
        self.assertEqual(self.run_script("--commit", self.sha, "--check").returncode, 0)
        self.assertIn(self.sha, (self.root / "fdroid/com.piano.sequencer.yml").read_text())

    def test_check_stale_is_nonmutating(self):
        before = (self.root / "fdroid/com.piano.sequencer.yml").read_text()
        result = self.run_script("--commit", self.sha, "--check")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(before, (self.root / "fdroid/com.piano.sequencer.yml").read_text())

    def test_missing_tag(self):
        self.git("tag", "-d", "v1.2.3")
        self.assertEqual(self.run_script().returncode, 2)

    def test_working_version_mismatch(self):
        (self.root / "app/build.gradle.kts").write_text('val baseVersion = "1.2.4"\nversionCode = 8\n')
        self.assertEqual(self.run_script("--commit", self.sha).returncode, 2)

    def test_multiple_build_entries_refused(self):
        p=self.root / "fdroid/com.piano.sequencer.yml"
        p.write_text(p.read_text().replace("CurrentVersion:", "  - versionName: 1.2.2\n    versionCode: 6\nCurrentVersion:"))
        self.assertEqual(self.run_script("--commit", self.sha).returncode, 2)


if __name__ == "__main__": unittest.main()
