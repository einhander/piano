#!/usr/bin/env bash
set -euo pipefail

# Build script for Piano Android Sequencer
# Usage: ./build.sh [debug|release]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MODE="${1:-debug}"

if [ "$MODE" = "debug" ]; then
    echo "==> Building debug APK..."
    ./gradlew :app:assembleDebug --no-daemon --warning-mode none
    echo "==> Done: app/build/outputs/apk/debug/"
elif [ "$MODE" = "release" ]; then
    echo "==> Building release APK..."
    ./gradlew :app:assembleRelease --no-daemon --warning-mode none
    echo "==> Done: app/build/outputs/apk/release/"
else
    echo "Usage: $0 [debug|release]"
    exit 1
fi