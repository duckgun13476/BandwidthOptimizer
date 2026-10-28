#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"
if command -v pwsh >/dev/null 2>&1; then
    exec pwsh -NoLogo -NoProfile -File "$script_dir/build.ps1" "$@"
fi
config_path=${NEXUS_BUILD_CONFIG:-}
if [ -z "$config_path" ]; then
    user_home=${HOME:-${USERPROFILE:-}}
    if [ -n "$user_home" ]; then
        config_path=$user_home/.gradle/nexus-build.properties
    fi
fi
if [ -n "$config_path" ] && [ -f "$config_path" ]; then
    echo 'Warning: pwsh is unavailable; using native Gradle without the owner-local Nexus cache.' >&2
fi
if [ ! -f "$script_dir/gradlew" ]; then
    echo 'Error: pwsh and native gradlew are unavailable.' >&2
    exit 127
fi
if [ "$#" -eq 0 ]; then
    exec "$script_dir/gradlew" packageAllVersions
fi
exec "$script_dir/gradlew" "$@"
