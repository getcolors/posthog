#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-posthog-green/green"
grep -q 'io.github.getcolors.posthog.workflow/workflow' "$launcher"
grep -q 'def \^:private posthog-sha' "$launcher"
[[ -L "$root/green" ]] && [[ $(readlink "$root/green") == skills/package-posthog-green/green ]]
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp "$launcher" "$tmp/green"; chmod +x "$tmp/green"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/colors.yml" > "$tmp/colors.yml"
(cd "$tmp" && POSTHOG_LIB_ROOT="$root" ./green build >/dev/null)
[[ -f "$tmp/.colors/posthog-fixture/posthog-infrastructure/main.tf" ]]
mkdir -p "$tmp/nested/path"
(cd "$tmp/nested/path" && POSTHOG_LIB_ROOT="$root" ../../green build >/dev/null)
out=$(cd "$tmp" && POSTHOG_LIB_ROOT="$root" COLORS_PAR_PROFILE=wrong ./green build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out"
[[ ! -d "$tmp/.colors/wrong" ]]
echo 'launcher: all checks passed'
