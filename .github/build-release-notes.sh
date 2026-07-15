#!/usr/bin/env bash
# Build the GitHub Release notes for one version.
#   usage: .github/build-release-notes.sh <version>      e.g. 0.59
#
# The body is, in order of preference:
#   1. a hand-written section in CHANGELOG.md headed "## <version>" (manual notes), OR
#   2. the commit subjects since the previous release tag (automatic).
# The fixed footer in .github/release-footer.md (Disclaimer + phoning-home note) is
# always appended, so every release carries it.
set -euo pipefail

VERSION="${1:?usage: build-release-notes.sh <version>}"
CHANGELOG="CHANGELOG.md"
FOOTER=".github/release-footer.md"

# 1) hand-written CHANGELOG.md section for exactly this version, if any
BODY=""
if [ -f "$CHANGELOG" ]; then
  BODY="$(awk -v v="$VERSION" '
    index($0, "## " v) == 1 { grab = 1; next }
    /^## / { grab = 0 }
    grab { print }
  ' "$CHANGELOG")"
fi

# 2) fall back to the commit list since the previous tag (or the last 30 commits)
if [ -z "$(printf %s "$BODY" | tr -d '[:space:]')" ]; then
  PREV="$(git tag --sort=-creatordate 2>/dev/null | head -n1 || true)"
  if [ -n "$PREV" ]; then
    BODY="$(git log --no-merges --pretty='- %s' "${PREV}..HEAD" 2>/dev/null || true)"
  fi
  if [ -z "$(printf %s "$BODY" | tr -d '[:space:]')" ]; then
    BODY="$(git log --no-merges --pretty='- %s' -30)"
  fi
fi

printf '## What is new in v%s\n\n%s\n' "$VERSION" "$BODY"

if [ -f "$FOOTER" ]; then
  printf '\n'
  cat "$FOOTER"
fi
