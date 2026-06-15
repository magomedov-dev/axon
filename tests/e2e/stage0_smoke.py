#!/usr/bin/env python3
"""
Stage 0 smoke test — no WebSocket yet, just the bare service.

Verifies:
  1. the APK is installed,
  2. the accessibility service is enabled in secure settings,
  3. the service is actually bound/connected by the system (visible in
     `dumpsys accessibility`).

This proves the manifest + accessibility_service_config are correct and the
service registers, before any networking is added in Stage 1.
"""

import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from _adb import PKG, SERVICE, package_installed, accessibility_dump, shell  # noqa: E402


def main() -> int:
    ok = True

    if package_installed():
        print(f"  [ok] package installed: {PKG}")
    else:
        print(f"  [FAIL] package not installed: {PKG}")
        ok = False

    enabled = shell("settings", "get", "secure", "enabled_accessibility_services")
    if PKG in enabled:
        print("  [ok] service present in enabled_accessibility_services")
    else:
        print(f"  [FAIL] service not enabled. current = {enabled.strip()!r}")
        ok = False

    dump = accessibility_dump()
    # The component appears in the bound/enabled service list once connected.
    if f"{PKG}/.AutomationAccessibilityService" in dump or SERVICE in dump:
        print("  [ok] service bound/connected (dumpsys accessibility)")
    else:
        print("  [FAIL] service not found in dumpsys accessibility (not bound)")
        ok = False

    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
