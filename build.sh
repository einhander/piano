#!/usr/bin/env bash
set -euo pipefail

# Build script for Piano Android Sequencer
# Usage: ./build.sh [debug|release|check]

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
elif [ "$MODE" = "check" ]; then
    export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-17.0.14+7}"
    export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/tools/android-sdk}}"
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
    export PATH="$JAVA_HOME/bin:$PATH"

    if [ ! -x "$JAVA_HOME/bin/java" ]; then
        echo "check: JDK 17 not found at $JAVA_HOME (set JAVA_HOME to valid JDK 17)" >&2
        exit 1
    fi
    if [ ! -d "$ANDROID_HOME" ]; then
        echo "check: Android SDK not found at $ANDROID_HOME (set ANDROID_HOME to valid SDK)" >&2
        exit 1
    fi

    RUN_DIR="build/verification/$(date +%Y%m%d-%H%M%S)-$$"
    mkdir -p "$RUN_DIR"
    run_check_step() {
        local name="$1"; shift
        local log="$RUN_DIR/$name.log"
        printf '==> Running:'; printf ' %q' "$@"; printf '\n'
        set +e
        "$@" 2>&1 | tee "$log"
        local statuses=(${PIPESTATUS[@]})
        set -e
        printf '==> Exit status: command=%s tee=%s\n==> Log: %s\n' "${statuses[0]}" "${statuses[1]}" "$log"
        if (( statuses[0] != 0 )); then return "${statuses[0]}"; fi
        return "${statuses[1]}"
    }

    run_check_step debug-build ./gradlew :app:assembleDebug --no-daemon --warning-mode none || exit $?
    run_check_step jvm-tests ./gradlew :app:testDebugUnitTest || exit $?
    run_check_step diff-check git diff --check || exit $?
    echo "==> Verification logs: $RUN_DIR"
else
    echo "Usage: $0 [debug|release|check]"
    exit 1
fi
