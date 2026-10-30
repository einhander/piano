import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).parents[1]


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        (ROOT / ".fdroid-validation").mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / ".fdroid-validation", prefix="release-sync-test-")
        self.root = Path(self.tmp.name)
        for d in ("scripts", "app", "fdroid"):
            (self.root / d).mkdir()
        for name in ("prepare-release.sh", "sync_fdroid_recipe.py"):
            (self.root / "scripts" / name).write_bytes((ROOT / "scripts" / name).read_bytes())
        (self.root / "scripts/prepare-release.sh").chmod(0o755)
        (self.root / "app/build.gradle.kts").write_text('val baseVersion = "1.2.3"\nversionCode = 7\n')
        (self.root / "fdroid/com.piano.sequencer.yml").write_text(
            'Builds:\n  - versionName: 1.2.3\n    versionCode: 7\n    commit: old\n'
            '    prebuild:\n      - sed -i \'s/versionName = resolvedVersionName/versionName = "1.2.3"/\' build.gradle.kts\n'
            'CurrentVersion: 1.2.3\nCurrentVersionCode: 7\n')
        (self.root / "build.sh").write_text('#!/bin/sh\necho debug >> validation.log\nexit "${BUILD_RESULT:-0}"\n')
        (self.root / "gradlew").write_text('#!/bin/sh\necho tests >> validation.log\nexit "${TEST_RESULT:-0}"\n')
        for path in (self.root / "build.sh", self.root / "gradlew"):
            path.chmod(0o755)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.git("add", "."); self.git("commit", "-qm", "initial")
        self.assertIn("app/build.gradle.kts", self.git("ls-files"))

    def tearDown(self): self.tmp.cleanup()
    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, text=True, stdout=subprocess.PIPE).stdout.strip()
    def run_release(self, version="1.2.4", **env):
        e=os.environ.copy(); e.update(env)
        return subprocess.run([str(self.root / "scripts/prepare-release.sh"), version], cwd=ROOT / "scripts", env=e,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_happy_path_two_commits_preserves_untracked_and_never_publishes(self):
        (self.root / "notes.log").write_text("keep")
        result=self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.git("rev-list", "--count", "HEAD"), "3")
        self.assertEqual(self.git("tag", "--list"), "")
        self.assertEqual((self.root / "notes.log").read_text(), "keep")
        gradle=(self.root / "app/build.gradle.kts").read_text()
        self.assertIn('baseVersion = "1.2.4"', gradle); self.assertIn("versionCode = 8", gradle)
        recipe=(self.root / "fdroid/com.piano.sequencer.yml").read_text()
        self.assertIn("1.2.4", recipe); self.assertIn("versionCode: 8", recipe)
        self.assertIn(self.git("rev-parse", "HEAD^") , recipe)
        log=self.git("log", "--format=%s", "-2").splitlines()
        self.assertEqual(log, ["build(fdroid): sync recipe to 1.2.4 source", "chore(release): bump version to 1.2.4"])
        self.assertIn("git tag -a v1.2.4", result.stdout)

    def test_preflight_failures_do_not_mutate(self):
        cases=(
            ("1.2.3", "backward version"),
            ("1.2.4-rc1", "invalid semver"),
            ("1.2.4", "existing tag"),
        )
        for version, reason in cases:
            with self.subTest(reason=reason):
                if reason == "existing tag": self.git("tag", "v1.2.5")
                before=self.git("rev-parse", "HEAD")
                result=self.run_release(version)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.git("rev-parse", "HEAD"), before)
                if reason == "existing tag": self.git("tag", "-d", "v1.2.5")

    def test_dirty_or_staged_changes_refused(self):
        target=self.root / "app/build.gradle.kts"
        target.write_text(target.read_text()+"// dirty\n")
        result=self.run_release()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        target.write_text('val baseVersion = "1.2.3"\nversionCode = 8\n')
        self.git("add", "app/build.gradle.kts")
        self.assertNotEqual(self.run_release().returncode, 0)

    def test_wrong_branch_refused(self):
        self.git("checkout", "-qb", "topic")
        self.assertNotEqual(self.run_release().returncode, 0)

    def test_version_code_exceeds_legacy_tag(self):
        gradle = self.root / "app/build.gradle.kts"
        original = gradle.read_text()
        gradle.write_text('val baseVersion = "1.2.2"\nversionCode = 30\n')
        self.git("add", "app/build.gradle.kts")
        self.git("commit", "-qm", "legacy fixture")
        self.git("tag", "1.2.2")
        gradle.write_text(original)
        self.git("add", "app/build.gradle.kts")
        self.git("commit", "-qm", "restore fixture")
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("versionCode = 31", gradle.read_text())

    def test_validation_failure_no_publish_instructions(self):
        result=self.run_release(BUILD_RESULT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("git tag -a", result.stdout)
        self.assertNotIn("git push", result.stdout)
        self.assertEqual(self.git("rev-list", "--count", "HEAD"), "3")


if __name__ == "__main__": unittest.main()
