"""Shared adb helpers for E2E tests (stdlib only)."""

from __future__ import annotations

import os
import subprocess

PKG = "com.axon.agent"
SERVICE = f"{PKG}/{PKG}.AutomationAccessibilityService"


def _base() -> list[str]:
    serial = os.environ.get("ADB_SERIAL", "").strip()
    return ["adb", "-s", serial] if serial else ["adb"]


def adb(*args: str, check: bool = True) -> str:
    out = subprocess.run(_base() + list(args), capture_output=True, text=True)
    if check and out.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def shell(*args: str, check: bool = True) -> str:
    return adb("shell", *args, check=check)


def package_installed(pkg: str = PKG) -> bool:
    return pkg in adb("shell", "pm", "list", "packages", pkg)


def accessibility_dump() -> str:
    return shell("dumpsys", "accessibility")
