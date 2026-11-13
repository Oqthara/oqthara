#!/usr/bin/env bash
# install.sh - install the oq CLI (Java 21+).
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash
#   OQ_VERSION=0.1.0-alpha.2 OQ_INSTALL_DIR="$HOME/bin" ./scripts/install.sh
#
# OQ_VERSION accepts "latest" (default), "0.1.0-alpha.2" or "v0.1.0-alpha.2".
# OQ_INSTALL_DIR defaults to "$HOME/.local/bin".
# OQ_REQUIRE_SIGNATURE=1 refuses a release whose signature is missing or unverifiable.
#
# Test seams (defaults are the real endpoints):
#   OQ_RELEASE_API       default https://api.github.com/repos/Oqthara/oqthara/releases?per_page=30
#   OQ_RELEASE_TAG_BASE  default https://github.com/Oqthara/oqthara/releases/download
#   OQ_JAVA              default java
#
# Designed for "curl | bash": no $BASH_SOURCE, no python/node/jq.
set -euo pipefail

ATOM_URL="https://github.com/Oqthara/oqthara/releases.atom"
API_URL="${OQ_RELEASE_API:-https://api.github.com/repos/Oqthara/oqthara/releases?per_page=30}"
TAG_BASE="${OQ_RELEASE_TAG_BASE:-https://github.com/Oqthara/oqthara/releases/download}"
INSTALL_DIR="${OQ_INSTALL_DIR:-$HOME/.local/bin}"
VERSION="${OQ_VERSION:-latest}"
JAVA_BIN="${OQ_JAVA:-java}"

tmp=""
launcher_tmp=""

cleanup() {
  if [ -n "$tmp" ]; then rm -f -- "$tmp"; fi
  if [ -n "$launcher_tmp" ]; then rm -f -- "$launcher_tmp"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

info() { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

fetch_text() {
  if has curl; then
    curl -fsSL --retry 2 --connect-timeout 15 --max-time 120 "$1"
  else
    wget -qO- "$1"
  fi
}

fetch_file() {
  if has curl; then
    curl -fsSL --retry 2 --connect-timeout 15 --max-time 300 "$1" -o "$2"
  else
    wget -qO "$2" "$1"
  fi
}

sha256_of() {
  if has shasum; then
    shasum -a 256 "$1" | sed 's/[[:space:]].*//'
  else
    sha256sum "$1" | sed 's/[[:space:]].*//'
  fi
}

normalize_tag() {
  case "$1" in
    v*) printf '%s\n' "$1" ;;
    *) printf 'v%s\n' "$1" ;;
  esac
}

# Latest non-draft release, prereleases included. API first, atom fallback.
# The API JSON is split per release and parsed with grep/sed only.
discover_tag() {
  local json tag
  if json=$(fetch_text "$API_URL"); then
    tag=$(printf '%s' "$json" | tr -d '\r\n' \
      | sed -e 's/"tag_name"/\
"tag_name"/g' \
      | grep -E '"draft"[[:space:]]*:[[:space:]]*false' \
      | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      | sed -n '1p')
    if [ -n "$tag" ]; then
      printf '%s\n' "$tag"
      return 0
    fi
  fi
  if json=$(fetch_text "$ATOM_URL"); then
    tag=$(printf '%s' "$json" | tr -d '\r\n' \
      | grep -oE 'releases/tag/[^"<]+' \
      | sed -n '1p' \
      | sed 's#.*releases/tag/##')
    if [ -n "$tag" ]; then
      printf '%s\n' "$tag"
      return 0
    fi
  fi
  return 1
}

check_java() {
  if ! has "$JAVA_BIN"; then
    fail "Java 21+ is required (java was not found on PATH)."
  fi
  local raw first ver major
  raw=$("$JAVA_BIN" -version 2>&1 || true)
  first=${raw%%$'\n'*}
  ver=$(printf '%s' "$first" | sed -n 's/.*"\([^"]*\)".*/\1/p')
  if [ -z "$ver" ]; then
    fail "Could not determine the Java version from: $first"
  fi
  case "$ver" in
    1.*) major=$(printf '%s' "$ver" | sed -n 's/^1\.\([0-9][0-9]*\).*/\1/p') ;;
    *) major=$(printf '%s' "$ver" | sed -n 's/^\([0-9][0-9]*\).*/\1/p') ;;
  esac
  if [ -z "$major" ]; then
    fail "Could not parse the Java version from: $ver"
  fi
  if [ "$major" -lt 21 ]; then
    fail "Java 21+ is required (found $ver)."
  fi
}

if ! has curl && ! has wget; then
  fail "curl or wget is required to download oq."
fi
if has shasum || has sha256sum; then
  :
else
  fail "SHA-256 verification requires 'shasum' (macOS) or 'sha256sum' (Linux)."
fi

check_java

if [ "$VERSION" = "latest" ]; then
  tag=$(discover_tag) || fail "Could not resolve the latest oq release (API and atom feed failed)."
else
  tag=$(normalize_tag "$VERSION")
fi
tag=$(normalize_tag "$tag")

jar_url="$TAG_BASE/$tag/oq.jar"
sha_url="$TAG_BASE/$tag/oq.jar.sha256"
sig_url="$TAG_BASE/$tag/oq.jar.sigstore.json"

info "Downloading oq $tag"
mkdir -p -- "$INSTALL_DIR"
jar="$INSTALL_DIR/oq.jar"
tmp="$INSTALL_DIR/.oq.jar.tmp.$$"

if ! fetch_file "$jar_url" "$tmp"; then
  fail "Download failed: $jar_url"
fi

sha_text=$(fetch_text "$sha_url") || fail "Missing checksum asset: $sha_url"
expected=$(printf '%s\n' "$sha_text" \
  | sed -n 's/^\([0-9a-fA-F]\{64\}\).*/\1/p' \
  | sed -n '1p' \
  | tr 'A-F' 'a-f')
if [ -z "$expected" ]; then
  fail "Invalid checksum file at $sha_url"
fi

actual=$(sha256_of "$tmp")
if [ "$actual" != "$expected" ]; then
  fail "Checksum mismatch for oq.jar (expected $expected, got $actual)."
fi

# A checksum proves the download is intact, not that it came from us: whoever can
# replace the jar can replace the checksum beside it. A signature closes that gap.
# Older releases were published before signing existed, so a missing bundle is a
# documented state rather than a hard failure — but it is reported, and it is never
# described as "verified". OQ_REQUIRE_SIGNATURE=1 turns it into a failure for anyone
# who needs the stronger guarantee.
bundle="$INSTALL_DIR/.oq.jar.sigstore.json.$$"
if fetch_file "$sig_url" "$bundle" 2>/dev/null && [ -s "$bundle" ]; then
  if has cosign; then
    if ! cosign verify-blob --bundle "$bundle" "$tmp" >/dev/null 2>&1; then
      rm -f -- "$bundle"
      fail "Signature verification FAILED for $jar_url. The jar does not match its signed bundle; do not run it."
    fi
    info "Signature verified (cosign, keyless signer)."
  else
    cat >&2 <<EOF
warning: oq $tag is signed, but 'cosign' is not installed so the signature was not checked.
         The SHA-256 matched, which detects corruption and NOT tampering.
         Install cosign (https://docs.sigstore.dev/cosign/installation/) to check it.
EOF
    if [ "${OQ_REQUIRE_SIGNATURE:-0}" = "1" ]; then
      rm -f -- "$bundle"
      fail "OQ_REQUIRE_SIGNATURE=1 but cosign is not installed."
    fi
  fi
  rm -f -- "$bundle"
else
  rm -f -- "$bundle"
  cat >&2 <<EOF
warning: oq $tag has no published signature, only a SHA-256.
         That detects corruption and NOT tampering: a replacement jar could ship with a
         replacement checksum. Releases from the next tag onward are signed.
EOF
  if [ "${OQ_REQUIRE_SIGNATURE:-0}" = "1" ]; then
    fail "OQ_REQUIRE_SIGNATURE=1 but $tag publishes no signature ($sig_url)."
  fi
fi

if ! smoke=$("$JAVA_BIN" -jar "$tmp" --version 2>&1); then
  fail "Downloaded oq.jar failed to run (java -jar ... --version)."
fi
smoke_first=${smoke%%$'\n'*}
case "$smoke_first" in
  "oq "*) ;;
  *) fail "Downloaded oq.jar did not report an oq version (got: $smoke_first)" ;;
esac

if ! mv -f -- "$tmp" "$jar"; then
  fail "Could not replace $jar."
fi
tmp=""

launcher="$INSTALL_DIR/oq"
launcher_tmp="$INSTALL_DIR/.oq.tmp.$$"
cat > "$launcher_tmp" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
# OQ_JAVA lets a user pin a specific JVM without changing PATH.
exec "${OQ_JAVA:-java}" -jar "$here/oq.jar" "$@"
SCRIPT
chmod +x "$launcher_tmp"
if ! mv -f -- "$launcher_tmp" "$launcher"; then
  fail "Could not install the launcher at $launcher."
fi
launcher_tmp=""

info "Installed oq $tag to $launcher"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) info "Add this to your shell profile: export PATH=\"$INSTALL_DIR:\$PATH\"" ;;
esac
info "Try: oq --version"
