#!/usr/bin/env bash
#
# Axon — project-local toolchain installer.
#
# Installs EVERYTHING the project needs into ./.tooling so the build is fully
# self-contained and independent of whatever is on the host (the host JDK is 26,
# which AGP/Gradle do not support — hence a local JDK 17).
#
#   .tooling/jdk           Temurin JDK 17  (Gradle/AGP runtime)
#   .tooling/gradle        Gradle distribution
#   .tooling/android-sdk   Android SDK (cmdline-tools + platform + build-tools)
#   .tooling/downloads     download cache
#
# After install it writes local.properties and generates the Gradle wrapper
# (./gradlew) so subsequent builds need only:  scripts/build.sh assembleDebug
#
# Idempotent: re-running skips anything already present.

set -euo pipefail

# ---- versions -------------------------------------------------------------
GRADLE_VERSION="8.10.2"
ANDROID_PLATFORM="android-35"
ANDROID_BUILD_TOOLS="35.0.0"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
JDK_FEATURE="17"

# ---- paths ----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLING="$ROOT_DIR/.tooling"
DL="$TOOLING/downloads"
JDK_DIR="$TOOLING/jdk"
GRADLE_DIR="$TOOLING/gradle"
SDK_DIR="$TOOLING/android-sdk"

mkdir -p "$DL"

log()  { printf '\033[1;34m[setup]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[setup] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

for bin in curl tar unzip; do
    command -v "$bin" >/dev/null 2>&1 || die "'$bin' is required but not found in PATH."
done

# ---- 1. JDK 17 ------------------------------------------------------------
if [[ -x "$JDK_DIR/bin/javac" ]]; then
    log "JDK 17 already present — skipping."
else
    log "Downloading Temurin JDK $JDK_FEATURE ..."
    JDK_URL="https://api.adoptium.net/v3/binary/latest/${JDK_FEATURE}/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
    curl -fL --retry 3 -o "$DL/jdk.tar.gz" "$JDK_URL"
    log "Extracting JDK ..."
    rm -rf "$JDK_DIR"; mkdir -p "$JDK_DIR"
    tar -xzf "$DL/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1
    "$JDK_DIR/bin/java" -version 2>&1 | sed 's/^/        /'
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"

# ---- 2. Gradle ------------------------------------------------------------
if [[ -x "$GRADLE_DIR/bin/gradle" ]]; then
    log "Gradle already present — skipping."
else
    log "Downloading Gradle $GRADLE_VERSION ..."
    curl -fL --retry 3 -o "$DL/gradle.zip" \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    log "Extracting Gradle ..."
    rm -rf "$GRADLE_DIR"; mkdir -p "$GRADLE_DIR"
    unzip -q "$DL/gradle.zip" -d "$DL/gradle-extract"
    mv "$DL/gradle-extract/gradle-${GRADLE_VERSION}"/* "$GRADLE_DIR"/
    rm -rf "$DL/gradle-extract"
fi
GRADLE_BIN="$GRADLE_DIR/bin/gradle"

# ---- 3. Android SDK -------------------------------------------------------
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [[ -x "$SDKMANAGER" ]]; then
    log "Android cmdline-tools already present — skipping download."
else
    log "Downloading Android command-line tools ..."
    curl -fL --retry 3 -o "$DL/cmdline-tools.zip" \
        "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
    log "Extracting cmdline-tools ..."
    mkdir -p "$SDK_DIR/cmdline-tools"
    rm -rf "$DL/cmdline-extract"
    unzip -q "$DL/cmdline-tools.zip" -d "$DL/cmdline-extract"
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$DL/cmdline-extract/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$DL/cmdline-extract"
fi

log "Accepting SDK licenses ..."
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

log "Installing platform-tools, $ANDROID_PLATFORM, build-tools;$ANDROID_BUILD_TOOLS ..."
"$SDKMANAGER" --sdk_root="$SDK_DIR" \
    "platform-tools" \
    "platforms;$ANDROID_PLATFORM" \
    "build-tools;$ANDROID_BUILD_TOOLS" | sed 's/^/        /'

# ---- 4. local.properties --------------------------------------------------
log "Writing local.properties ..."
printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT_DIR/local.properties"

# ---- 5. Gradle wrapper ----------------------------------------------------
if [[ -x "$ROOT_DIR/gradlew" ]]; then
    log "Gradle wrapper already present — skipping generation."
else
    log "Generating Gradle wrapper ..."
    ( cd "$ROOT_DIR" && "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_VERSION" --no-daemon )
fi

log "Done. Toolchain installed under .tooling/"
log "Build with:  scripts/build.sh assembleDebug"
