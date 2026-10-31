#!/bin/sh
#
# build-guide.sh - regenerate the theme-guide artifacts from the markdown source.
#
# Single source of truth:
#     THEME-GUIDE.md      (EN)   +   THEME-GUIDE.de.md   (DE)
# Generated (never hand-edited):
#     app/src/main/assets/dashboard/theme-guide.html   bilingual, shown in-app
#     THEME-GUIDE-EN.pdf  +  THEME-GUIDE-DE.pdf         scientific-paper PDFs
#
# Repo URL + app name are derived from the git remote, so this same script is
# byte-identical across every Laufbursche Edition app repo (tr / sf / nv / tb).
# Called by .githooks/pre-commit when a markdown source is staged, and reusable
# standalone:  sh tools/build-guide.sh
#
# Requires: python (with beautifulsoup4), pandoc, xelatex (MiKTeX/TeX Live).
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

PY=${PYTHON:-python}

# --- locate pandoc (PATH, then the per-user Windows install) ------------------
if [ -n "${PANDOC:-}" ] && command -v "$PANDOC" >/dev/null 2>&1; then
  :
elif command -v pandoc >/dev/null 2>&1; then
  PANDOC=pandoc
elif [ -n "${LOCALAPPDATA:-}" ] && [ -x "${LOCALAPPDATA}/Pandoc/pandoc.exe" ]; then
  PANDOC="${LOCALAPPDATA}/Pandoc/pandoc.exe"
elif [ -x "${HOME:-}/AppData/Local/Pandoc/pandoc.exe" ]; then
  PANDOC="${HOME}/AppData/Local/Pandoc/pandoc.exe"
else
  echo "build-guide: ERROR - pandoc not found (install pandoc or set PANDOC=/path/to/pandoc)" >&2
  exit 3
fi
export PANDOC

# --- derive repo slug / url / app name from the git remote --------------------
REMOTE=$(git config --get remote.origin.url 2>/dev/null || echo "")
# strip scheme/host and a trailing .git  ->  "Owner/repo"
SLUG=$(printf '%s' "$REMOTE" \
  | sed -E 's#^git@[^:]+:##; s#^https?://[^/]+/##; s#\.git$##')
[ -n "$SLUG" ] || SLUG="Laufbursche42/$(basename "$ROOT")"
REPO_URL="https://github.com/${SLUG}"
RAW_URL="https://raw.githubusercontent.com/${SLUG}/main"
BLOB_URL="${REPO_URL}/blob/main"

case "$SLUG" in
  */tr-lb-edition) APP="Teverun" ;;
  */sf-lb-edition) APP="SoFlow"  ;;
  */nv-lb-edition|*/navee-lb-edition) APP="Navee" ;;
  */tb-lb-edition) APP="ThunderBike" ;;
  *) APP="Laufbursche Edition" ;;
esac

AUTHOR=${GUIDE_AUTHOR:-Laufbursche42}
GVER=$(sed -n '1p' THEME-GUIDE.version 2>/dev/null | tr -d '[:space:]')
[ -n "$GVER" ] || GVER="1.0"

echo "build-guide: slug=$SLUG app=$APP guide-version=$GVER"

# --- 1) in-app bilingual HTML -------------------------------------------------
"$PY" tools/build_guide_html.py \
  app/src/main/assets/dashboard/theme-guide.html \
  "$REPO_URL" \
  "en|THEME-GUIDE.md|${RAW_URL}/THEME-GUIDE.md|${BLOB_URL}/THEME-GUIDE-EN.pdf" \
  "de|THEME-GUIDE.de.md|${RAW_URL}/THEME-GUIDE.de.md|${BLOB_URL}/THEME-GUIDE-DE.pdf"

# --- 2) paper PDFs (EN + DE) --------------------------------------------------
"$PY" tools/build_guide_pdf.py THEME-GUIDE.md    en THEME-GUIDE-EN.pdf "$REPO_URL" "$APP" "$GVER" "$AUTHOR"
"$PY" tools/build_guide_pdf.py THEME-GUIDE.de.md de THEME-GUIDE-DE.pdf "$REPO_URL" "$APP" "$GVER" "$AUTHOR"

# --- 3) tidy the xelatex scratch dir -----------------------------------------
rm -rf tex 2>/dev/null || true

echo "build-guide: done."
