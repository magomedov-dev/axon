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

[[ -x "$TOOLING/jdk/bin/java"        ]] || { echo "JDK not found. Run scripts/setup.sh first." >&2; exit 1; }
[[ -x "$TOOLING/gradle/bin/gradle"   ]] || { echo "Gradle not found. Run scripts/setup.sh first." >&2; exit 1; }

# Use the project-local Gradle directly (not ./gradlew) so nothing is re-downloaded
# from the network, and keep the dependency cache inside the project too.
export JAVA_HOME="$TOOLING/jdk"
export ANDROID_HOME="$TOOLING/android-sdk"
export ANDROID_SDK_ROOT="$TOOLING/android-sdk"
export GRADLE_USER_HOME="$TOOLING/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"

exec "$TOOLING/gradle/bin/gradle" -p "$ROOT_DIR" "$@"
