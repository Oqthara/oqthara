#!/usr/bin/env bash
# Installs the oq CLI. Requires Java 21+.
set -euo pipefail

REPO="Oqthara/oqthara"
INSTALL_DIR="${OQ_INSTALL_DIR:-$HOME/.local/bin}"
VERSION="${OQ_VERSION:-latest}"

info() { printf '\033[36m%s\033[0m\n' "$*"; }
fail() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || fail "Java is required. Install JDK 21 or newer."
java_version=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
[ "$java_version" -ge 21 ] || fail "Java 21 or newer is required (found $java_version)."

if [ "$VERSION" = "latest" ]; then
  url="https://github.com/$REPO/releases/latest/download/oq.jar"
else
  url="https://github.com/$REPO/releases/download/v$VERSION/oq.jar"
fi

mkdir -p "$INSTALL_DIR"
tmp=$(mktemp)
info "Downloading $url"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$url" -o "$tmp"
else
  wget -qO "$tmp" "$url"
fi

mv "$tmp" "$INSTALL_DIR/oq.jar"

cat > "$INSTALL_DIR/oq" <<'SCRIPT'
#!/usr/bin/env bash
exec java -jar "$(dirname "$(readlink -f "$0")")/oq.jar" "$@"
SCRIPT
chmod +x "$INSTALL_DIR/oq"

info "Installed to $INSTALL_DIR/oq"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) info "Add this to your shell profile: export PATH=\"$INSTALL_DIR:\$PATH\"" ;;
esac
info "Try: oq --version"
