#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_dir="$root/listening-ui/runtime"
check="$root/listening-ui/check-runtime.sh"
main="$root/app/src/main"
destination="$main/assets/listening-ui"
lock="$main/.listening-ui-sync.lock"
stage=

"$check" "$source_dir"
mkdir "$lock" || {
    printf '%s\n' 'Listening UI synchronization is already running.' >&2
    exit 1
}

cleanup() {
    status=$?
    trap - EXIT HUP INT TERM
    if [ -n "$stage" ] && [ "$status" -ne 0 ] && [ -d "$stage/previous" ]; then
        if [ -e "$destination" ] && ! mv "$destination" "$stage/failed"; then
            printf 'Could not roll back. Previous assets are in %s\n' "$stage/previous" >&2
            rmdir "$lock"
            exit 1
        fi
        if ! mv "$stage/previous" "$destination"; then
            printf 'Could not restore previous assets. They are in %s\n' "$stage/previous" >&2
            rmdir "$lock"
            exit 1
        fi
    fi
    if [ -n "$stage" ]; then
        rm -rf "$stage"
    fi
    rmdir "$lock"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

# Stage outside the packaged assets directory and on the same filesystem.
stage=$(mktemp -d "$main/.listening-ui-stage.XXXXXX")
mkdir "$stage/runtime"
cp "$source_dir/listening-ui.css" "$source_dir/listening-ui.js" "$stage/runtime/"
"$check" "$stage/runtime"
cmp -s "$source_dir/listening-ui.css" "$stage/runtime/listening-ui.css"
cmp -s "$source_dir/listening-ui.js" "$stage/runtime/listening-ui.js"

if [ -e "$destination" ] || [ -L "$destination" ]; then
    "$check" "$destination"
    if cmp -s "$stage/runtime/listening-ui.css" "$destination/listening-ui.css" &&
        cmp -s "$stage/runtime/listening-ui.js" "$destination/listening-ui.js"; then
        printf '%s\n' 'Android listening UI assets are current.'
        exit 0
    fi
    mv "$destination" "$stage/previous"
fi
mv "$stage/runtime" "$destination"
printf '%s\n' 'Synchronized Android listening UI CSS and JavaScript.'
