#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_dir="$repo_root/apps/lightcraft-android/android"

if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    ANDROID_HOME=$ANDROID_SDK_ROOT
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    for sdk_path in \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        /usr/lib/android-sdk \
        /opt/android-sdk
    do
        if [ -d "$sdk_path" ]; then
            ANDROID_HOME=$sdk_path
            break
        fi
    done
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    printf '%s\n' \
        "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT to your SDK directory." >&2
    exit 1
fi

if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ "$ANDROID_SDK_ROOT" != "$ANDROID_HOME" ]; then
    printf '%s\n' \
        "ANDROID_HOME and ANDROID_SDK_ROOT point to different directories." \
        "Set them to the same Android SDK directory and try again." >&2
    exit 1
fi

if [ ! -d "$ANDROID_HOME" ]; then
    printf 'Android SDK directory does not exist: %s\n' "$ANDROID_HOME" >&2
    exit 1
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME

cd "$android_dir"
exec ./gradlew assembleRelease "$@"
