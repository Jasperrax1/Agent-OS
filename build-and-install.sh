#!/bin/sh
set -eu
umask 077

if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [adb-serial]\n' "$0" >&2
    exit 2
fi

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tools_dir="$HOME/Documents/Codex/tools/lan-camera"
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] && [ -f "$root/local.properties" ]; then
    sdk=$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$root/local.properties" |
        tail -n 1 | sed 's/\r$//; s/\\ / /g; s/\\:/:/g')
fi
if [ -z "$sdk" ]; then
    sdk="$tools_dir/android-sdk"
fi

adb="$sdk/platform-tools/adb"
if [ ! -x "$adb" ]; then
    printf '%s\n' 'Android SDK Platform Tools were not found. Set ANDROID_HOME to a valid SDK.' >&2
    exit 1
fi
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"

if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x "$tools_dir/jdk17/Contents/Home/bin/java" ]; then
        JAVA_HOME="$tools_dir/jdk17/Contents/Home"
    elif [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
    fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
fi

serial=${1:-${ANDROID_SERIAL:-}}
if [ -z "$serial" ]; then
    connected=$("$adb" devices)
    serial=$(printf '%s\n' "$connected" | awk '$2 == "device" { print $1 }')
    count=$(printf '%s\n' "$serial" | awk 'NF { n++ } END { print n+0 }')
    if [ "$count" -ne 1 ]; then
        printf '%s\n' 'Connect and authorize one Android device, or pass an adb serial.' >&2
        exit 1
    fi
fi
if [ "$("$adb" -s "$serial" get-state)" != device ]; then
    printf '%s\n' 'The selected Android device is not authorized and online.' >&2
    exit 1
fi

cd "$root"
./sync-listening-ui.sh
apk=app/build/outputs/apk/debug/app-debug.apk
protect_apk() {
    if [ -f "$apk" ]; then
        chmod 600 "$apk"
    fi
}
trap protect_apk EXIT
protect_apk
./gradlew :app:assembleDebug
chmod 600 "$apk"
"$adb" -s "$serial" install -r "$apk"
