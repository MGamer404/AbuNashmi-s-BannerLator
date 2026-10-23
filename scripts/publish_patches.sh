#!/usr/bin/env bash
# Small-update patches live in their own repo, so Bannerlator's release page keeps only the APKs.
# One release there per Bannerlator cut, same tag, holding that cut's .bldp files.
#
#   publish_patches.sh publish TAG DIR    upload DIR/*.bldp as release TAG (replacing an older TAG),
#                                         check every file landed, print the download base URL
#   publish_patches.sh prune KEEP_TAG...  delete every other patch release (and its tag)
#
# GH_TOKEN needs Contents read/write on the updates repo (secret UPDATES_TOKEN in release.yml).
# UPDATES_REPO overrides the repo (default The412Banner/Bannerlator-updates).
set -euo pipefail
REPO="${UPDATES_REPO:-The412Banner/Bannerlator-updates}"

case "${1:-}" in
publish)
    TAG=$2 DIR=$3
    shopt -s nullglob
    files=("$DIR"/*.bldp)
    [ ${#files[@]} -gt 0 ] || { echo "no .bldp files in $DIR" >&2; exit 1; }
    gh release delete "$TAG" -R "$REPO" --yes --cleanup-tag >/dev/null 2>&1 || true
    gh release create "$TAG" -R "$REPO" --latest=false \
        --title "Bannerlator $TAG update patches" \
        --notes "Small in-app update patches for Bannerlator $TAG, downloaded by the app's updater. Not installable by hand: the APKs are on https://github.com/The412Banner/Bannerlator/releases/tag/$TAG" \
        "${files[@]}" >&2
    have=$(gh release view "$TAG" -R "$REPO" --json assets -q '[.assets[] | "\(.name) \(.size)"] | sort | .[]')
    want=$(for f in "${files[@]}"; do echo "$(basename "$f") $(stat -c %s "$f")"; done | sort)
    [ "$have" = "$want" ] || { echo "uploaded assets do not match the patches" >&2; exit 1; }
    echo "https://github.com/$REPO/releases/download/$TAG"
    ;;
prune)
    shift
    [ $# -gt 0 ] || { echo "prune needs at least one tag to keep" >&2; exit 1; }
    gh release list -R "$REPO" --limit 200 --json tagName -q '.[].tagName' | while read -r t; do
        keep=0
        for k in "$@"; do [ "$t" = "$k" ] && keep=1; done
        if [ $keep = 0 ]; then
            echo "removing patch release $t" >&2
            gh release delete "$t" -R "$REPO" --yes --cleanup-tag >&2
        fi
    done
    ;;
*)
    sed -n '2,12p' "$0" >&2
    exit 2
    ;;
esac
