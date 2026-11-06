#!/usr/bin/env python3

import os
import pathlib
import shutil
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
TEST_SCRIPT = ROOT / "tools" / "test_installer_idempotency.ps1"


def powershell_command() -> str | None:
    configured = os.environ.get("POWERSHELL")
    if configured:
        return configured
    return shutil.which("pwsh") or shutil.which("powershell.exe")


@unittest.skipUnless(powershell_command(), "PowerShell is not installed")
class WindowsInstallerTests(unittest.TestCase):
    def test_repeat_install_validation_and_recoverable_uninstall(self):
        result = subprocess.run(
            [
                powershell_command(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                str(TEST_SCRIPT),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("installer-idempotency: PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
