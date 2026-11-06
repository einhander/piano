import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "build.sh"
TEST_AREA = ROOT / "build/verification-tests"


class BuildCheckTest(unittest.TestCase):
    def setUp(self):
        TEST_AREA.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=TEST_AREA)
        self.root = Path(self.tmp.name)
        shutil.copy2(BUILD_SCRIPT, self.root / "build.sh")
        self.events = self.root / "events"
        (self.root / "gradlew").write_text(
            '#!/usr/bin/env bash\n'
            'echo "$*" >> "$EVENTS"\n'
            'case "$*" in *assembleDebug*) exit "${BUILD_STATUS:-0}";; '
            '*testDebugUnitTest*) exit "${TEST_STATUS:-0}";; '
            '*assembleRelease*) exit "${RELEASE_STATUS:-0}";; esac\n'
        )
        (self.root / "gradlew").chmod(0o755)
        self.java = self.root / "jdk/bin/java"
        self.java.parent.mkdir(parents=True)
        self.java.write_text("#!/bin/sh\nexit 0\n")
        self.java.chmod(0o755)
        self.sdk = self.root / "sdk"
        self.sdk.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def tearDown(self):
        self.tmp.cleanup()

    def run_build(self, mode, **overrides):
        env = os.environ.copy()
        for key in ("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT"):
            env.pop(key, None)
        env.update({"JAVA_HOME": str(self.java.parent.parent),
                    "ANDROID_HOME": str(self.sdk), "EVENTS": str(self.events)})
        env.update({key: str(value) for key, value in overrides.items()})
        return subprocess.run(["bash", "build.sh", mode], cwd=self.root, env=env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_success_order_and_unique_logs(self):
        first = self.run_build("check")
        second = self.run_build("check")
        self.assertEqual(first.returncode, 0, first.stdout)
        self.assertEqual(second.returncode, 0, second.stdout)
        self.assertEqual(self.events.read_text().splitlines(), [
            ":app:assembleDebug --no-daemon --warning-mode none", ":app:testDebugUnitTest",
            ":app:assembleDebug --no-daemon --warning-mode none", ":app:testDebugUnitTest"])
        self.assertIn("git diff --check", first.stdout)
        logs = sorted((self.root / "build/verification").iterdir())
        self.assertEqual(len(logs), 2)
        self.assertNotEqual(logs[0].name, logs[1].name)
        self.assertEqual({p.name for p in logs[0].iterdir()},
                         {"debug-build.log", "jvm-tests.log", "diff-check.log"})

    def test_build_failure_stops(self):
        result = self.run_build("check", BUILD_STATUS=17)
        self.assertEqual(result.returncode, 17, result.stdout)
        self.assertEqual(self.events.read_text().splitlines(),
                         [":app:assembleDebug --no-daemon --warning-mode none"])

    def test_test_failure_skips_diff_and_propagates(self):
        result = self.run_build("check", TEST_STATUS=23)
        self.assertEqual(result.returncode, 23, result.stdout)
        self.assertEqual(len(self.events.read_text().splitlines()), 2)
        self.assertNotIn("git diff --check", result.stdout)

    def test_tee_failure_fails_check(self):
        bindir = self.root / "bin"
        bindir.mkdir()
        tee = bindir / "tee"
        tee.write_text("#!/bin/sh\n/usr/bin/tee \"$@\"\nexit 31\n")
        tee.chmod(0o755)
        result = self.run_build("check", PATH=f"{bindir}:{os.environ['PATH']}")
        self.assertEqual(result.returncode, 31, result.stdout)
        self.assertEqual(len(self.events.read_text().splitlines()), 1)

    def test_diff_failure_propagates(self):
        tracked = self.root / "tracked.txt"
        tracked.write_text("clean\n")
        subprocess.run(["git", "add", "tracked.txt"], cwd=self.root, check=True)
        tracked.write_text("trailing space \n")
        result = self.run_build("check")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("git diff --check", result.stdout)
        self.assertIn("trailing whitespace", result.stdout)

    def test_android_sdk_root_fallback(self):
        result = self.run_build("check", ANDROID_HOME="", ANDROID_SDK_ROOT=self.sdk)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_debug_release_commands_unchanged(self):
        for mode, expected in (("debug", ":app:assembleDebug --no-daemon --warning-mode none"),
                               ("release", ":app:assembleRelease --no-daemon --warning-mode none")):
            self.events.unlink(missing_ok=True)
            result = self.run_build(mode)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertEqual(self.events.read_text().splitlines(), [expected])

    def test_missing_toolchain_fails_clearly(self):
        result = self.run_build("check", JAVA_HOME=self.root / "missing-jdk")
        self.assertEqual(result.returncode, 1)
        self.assertIn("JDK 17 not found", result.stdout)


if __name__ == "__main__":
    unittest.main()
