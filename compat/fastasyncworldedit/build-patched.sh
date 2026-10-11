#!/bin/sh
set -eu

if [ "$#" -lt 1 ]; then
    printf '%s\n' 'Usage: build-patched.sh /absolute/source/path [Gradle tasks/options]' >&2
    exit 2
fi

source_path="$1"
shift
support_path=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -d "$source_path" ]; then
    python3 "$support_path/prepare-source.py" "$source_path"
fi
source_path=$(CDPATH= cd -- "$source_path" && pwd)
repository_path=$(CDPATH= cd -- "$support_path/../.." && pwd)
case "$source_path/" in
    "$repository_path/"*)
        printf '%s\n' 'FAWE source and build outputs must be outside the Wormholes repository' >&2
        exit 2
        ;;
esac
if [ "$#" -eq 0 ]; then
    set -- :worldedit-core:test :worldedit-bukkit:shadowJar
fi
cd "$source_path"
for source_patch in "$support_path"/*.patch; do
    if ! patch --batch --reverse --dry-run -p1 -i "$source_patch" >/dev/null; then
        printf '%s\n' "Source does not match the required patch: $source_patch" >&2
        exit 2
    fi
done
exec ./gradlew \
    -PsourceCommit=9e5e49efe4f48ab6418831d241113e3f3b8d1691 \
    -PsourceDate=26.10.09 \
    -ProotVersion=2.16.1-wormholes-1 "$@"
