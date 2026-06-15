#!/usr/bin/env bash
#
# Axon — build wrapper. Runs the Gradle wrapper with the project-local JDK 17
# and Android SDK, so the host environment (JDK 26 etc.) is never used.
#
# Usage:
#   scripts/build.sh assembleDebug
#   scripts/build.sh installDebug
#   scripts/build.sh tasks
#
# Requires scripts/setup.sh to have been run once.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLING="$ROOT_DIR/.tooling"

[[ -x "$TOOLING/jdk/bin/java" ]] || { echo "JDK not found. Run scripts/setup.sh first." >&2; exit 1; }
[[ -x "$ROOT_DIR/gradlew"   ]] || { echo "gradlew not found. Run scripts/setup.sh first." >&2; exit 1; }

export JAVA_HOME="$TOOLING/jdk"
export ANDROID_HOME="$TOOLING/android-sdk"
export ANDROID_SDK_ROOT="$TOOLING/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"

exec "$ROOT_DIR/gradlew" -p "$ROOT_DIR" "$@"
