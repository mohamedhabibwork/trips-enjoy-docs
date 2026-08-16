#!/usr/bin/env bash
# validate-docs.sh — verify every Mermaid block in docs/ parses and every
# Markdown link resolves. Used by `make validate-docs`.
#
# Exit codes:
#   0  all blocks parsed, all links resolved
#   1  one or more Mermaid blocks failed to parse
#   2  one or more Markdown links are broken
#   3  both
#
# Prerequisites (auto-installed on first run with npx):
#   - @mermaid-js/mermaid-cli (Mermaid renderer; uses puppeteer)
#   - markdown-link-check
#
# Optional environment:
#   DOCS_DIR  override the docs root (default: ./docs)
#   SKIP_MERMAID=1 to skip Mermaid validation
#   SKIP_LINKS=1   to skip link validation

set -u

DOCS_DIR="${DOCS_DIR:-docs}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

if [[ ! -d "$DOCS_DIR" ]]; then
  echo "validate-docs: $DOCS_DIR is not a directory"
  exit 3
fi

EXIT_CODE=0
MERMAID_BLOCKS=0
MERMAID_OK=0
MERMAID_FAIL=0
LINKS_CHECKED=0
LINKS_OK=0
LINKS_BROKEN=0

# ---------------------------------------------------------------------------
# 1. Mermaid block validation
# ---------------------------------------------------------------------------
if [[ "${SKIP_MERMAID:-0}" != "1" ]]; then
  echo "validate-docs: walking $DOCS_DIR for Mermaid blocks..."
  TMP_DIR="$(mktemp -d -t mermaid-XXXXXX)"
  trap 'rm -rf "$TMP_DIR"' EXIT

  # Extract every ```mermaid ... ``` block, write to a temp file, then ask
  # mermaid-cli to render it. A non-zero exit means the syntax is invalid.
  # Use awk to slice fenced blocks.
  awk -v tmpdir="$TMP_DIR" '
    BEGIN { in_block = 0; block_id = 0 }
    /^```mermaid[[:space:]]*$/ { in_block = 1; block_id++; current = sprintf("%s/block-%05d.mmd", tmpdir, block_id); next }
    /^```[[:space:]]*$/ && in_block { in_block = 0; close(current); next }
    in_block { print > current }
  ' $(find "$DOCS_DIR" -type f -name "*.md" | sort)

  MERMAID_BLOCKS=$(find "$TMP_DIR" -type f -name "*.mmd" | wc -l | tr -d ' ')
  echo "validate-docs: found $MERMAID_BLOCKS Mermaid block(s)"

  if [[ "$MERMAID_BLOCKS" -gt 0 ]]; then
    if ! command -v npx >/dev/null 2>&1; then
      echo "validate-docs: WARN — npx not available; skipping Mermaid validation"
    else
      # Render each block to a throwaway SVG. We don't care about the
      # output, only the exit code.
      for f in "$TMP_DIR"/*.mmd; do
        if [[ ! -s "$f" ]]; then
          echo "validate-docs: FAIL empty block $(basename "$f")"
          MERMAID_FAIL=$((MERMAID_FAIL + 1))
          continue
        fi
        if npx -y -p @mermaid-js/mermaid-cli@10.9.0 mmdc -i "$f" -o "${f%.mmd}.svg" --quiet >/dev/null 2>"$TMP_DIR/last_err"; then
          MERMAID_OK=$((MERMAID_OK + 1))
        else
          MERMAID_FAIL=$((MERMAID_FAIL + 1))
          echo "validate-docs: Mermaid parse failed for $(basename "$f"):"
          sed 's/^/    /' "$TMP_DIR/last_err" | head -10
        fi
      done
      echo "validate-docs: Mermaid: $MERMAID_OK ok, $MERMAID_FAIL failed"
      if [[ "$MERMAID_FAIL" -gt 0 ]]; then
        EXIT_CODE=$((EXIT_CODE | 1))
      fi
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 2. Markdown link validation
# ---------------------------------------------------------------------------
if [[ "${SKIP_LINKS:-0}" != "1" ]]; then
  echo "validate-docs: walking $DOCS_DIR for broken local Markdown links..."
  if ! command -v npx >/dev/null 2>&1; then
    echo "validate-docs: WARN — npx not available; skipping link validation"
  else
    # markdown-link-check is a single binary. We invoke it per file with a
    # config that ignores external links (we only care about repo-internal).
    CAT_CONFIG="$(mktemp -t mdlc-XXXXXX.json)"
    trap 'rm -rf "$TMP_DIR" "$CAT_CONFIG"' EXIT
    cat > "$CAT_CONFIG" <<JSON
{
  "replacementPatterns": [],
  "httpHeaders": [],
  "ignorePatterns": [
    { "pattern": "^https?://" }
  ],
  "aliveStatusCodes": [200, 206, 301, 302, 307, 308, 999]
}
JSON

    for f in $(find "$DOCS_DIR" -type f -name "*.md" | sort); do
      # Extract the relative path from the repo root so the line numbers in
      # any error output are unambiguous.
      rel="${f#./}"
      result=$(npx -y -p markdown-link-check@3.12.2 markdown-link-check --config "$CAT_CONFIG" --quiet "$f" 2>&1 || true)
      LINKS_CHECKED=$((LINKS_CHECKED + 1))
      if [[ -n "$result" ]]; then
        LINKS_BROKEN=$((LINKS_BROKEN + 1))
        echo "validate-docs: broken links in $rel:"
        echo "$result" | sed 's/^/    /' | head -10
      else
        LINKS_OK=$((LINKS_OK + 1))
      fi
    done
    echo "validate-docs: links: $LINKS_OK files clean, $LINKS_BROKEN files with broken local links"
    if [[ "$LINKS_BROKEN" -gt 0 ]]; then
      EXIT_CODE=$((EXIT_CODE | 2))
    fi
  fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
echo "validate-docs: summary"
echo "  Mermaid blocks: $MERMAID_OK ok / $MERMAID_FAIL failed (of $MERMAID_BLOCKS total)"
echo "  Markdown files: $LINKS_OK ok / $LINKS_BROKEN with broken links (of $LINKS_CHECKED total)"
echo "  exit code: $EXIT_CODE"

exit $EXIT_CODE
