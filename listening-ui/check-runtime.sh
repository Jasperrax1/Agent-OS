#!/bin/sh
set -eu

workspace=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
runtime=${1:-"$workspace/runtime"}

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

[ -d "$runtime" ] && [ ! -L "$runtime" ] || fail "Runtime must be a real directory."
for entry in "$runtime"/* "$runtime"/.[!.]* "$runtime"/..?*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    case "${entry##*/}" in
        listening-ui.css|listening-ui.js)
            [ -f "$entry" ] && [ ! -L "$entry" ] && [ -s "$entry" ] ||
                fail "Runtime resource must be a nonempty regular file: $entry"
            ;;
        *) fail "Unexpected runtime entry: $entry" ;;
    esac
done
for name in listening-ui.css listening-ui.js; do
    [ -f "$runtime/$name" ] || fail "Missing runtime resource: $name"
done

markers='mock|fixture|debug|placeholder|lorem|preview|playground|test[[:space:]_-]*(button|panel|data|mode|text)|(^|[^[:alnum:]_])demo([^[:alnum:]_]|$)|console[[:space:]]*\.[[:space:]]*(log|debug|table)|模拟|调试|测试按钮|测试面板|占位|示例|演示文本|帮我(打开|生成)|请输入'
if flagged=$(LC_ALL=C grep -ilE "$markers" "$runtime/listening-ui.css" "$runtime/listening-ui.js"); then
    fail "Preview or diagnostic content found in: $flagged"
else
    status=$?
    [ "$status" -eq 1 ] || fail "Could not scan runtime resources (grep status $status)."
fi

printf '%s\n' 'Runtime resources passed the allowlist and content checks.'
