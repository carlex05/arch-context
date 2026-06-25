#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${PREFIX:-$HOME/.local}"
INSTALL_DIR="${ARCHCONTEXT_INSTALL_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/archcontext}"
BIN_DIR="${ARCHCONTEXT_BIN_DIR:-$PREFIX/bin}"
JAR_SOURCE=""
JAR_URL="${ARCHCONTEXT_JAR_URL:-}"

usage() {
  cat <<'USAGE'
Usage: scripts/install.sh [options]

Options:
  --jar PATH          Install from a local archcontext.jar.
  --url URL           Download archcontext.jar from a release URL.
  --install-dir DIR   Install directory. Default: ~/.local/share/archcontext
  --bin-dir DIR       Wrapper directory. Default: ~/.local/bin
  -h, --help          Show this help.

Environment:
  ARCHCONTEXT_JAR_URL      Release URL used when --url is omitted.
  ARCHCONTEXT_INSTALL_DIR  Install directory.
  ARCHCONTEXT_BIN_DIR      Wrapper directory.
  PREFIX                   Prefix for default bin dir.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)
      JAR_SOURCE="${2:-}"
      shift 2
      ;;
    --url)
      JAR_URL="${2:-}"
      shift 2
      ;;
    --install-dir)
      INSTALL_DIR="${2:-}"
      shift 2
      ;;
    --bin-dir)
      BIN_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

require_java_21() {
  if ! command -v java >/dev/null 2>&1; then
    echo "java is required and was not found on PATH." >&2
    exit 1
  fi
  local version major
  version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    major="$(printf '%s' "$version" | cut -d. -f2)"
  fi
  if [[ -z "$major" || "$major" -lt 21 ]]; then
    echo "Java 21 or newer is required. Found: ${version:-unknown}" >&2
    exit 1
  fi
}

download() {
  local url="$1"
  local output="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$url" -o "$output"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$output" "$url"
  else
    echo "curl or wget is required to install from URL." >&2
    exit 1
  fi
}

require_java_21

TMP_JAR=""
if [[ -n "$JAR_URL" ]]; then
  TMP_JAR="$(mktemp)"
  echo "Downloading ArchContext from $JAR_URL..."
  download "$JAR_URL" "$TMP_JAR"
  JAR_SOURCE="$TMP_JAR"
fi

if [[ -z "$JAR_SOURCE" ]]; then
  JAR_SOURCE="$ROOT_DIR/target/archcontext.jar"
fi

if [[ ! -f "$JAR_SOURCE" ]]; then
  echo "JAR not found: $JAR_SOURCE" >&2
  echo "Build it first with: mvn -q package -Dgit.commit=\$(git rev-parse --short HEAD)" >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
cp "$JAR_SOURCE" "$INSTALL_DIR/archcontext.jar"

cat > "$BIN_DIR/archcontext" <<EOF
#!/usr/bin/env bash
exec java -jar "$INSTALL_DIR/archcontext.jar" "\$@"
EOF
chmod +x "$BIN_DIR/archcontext"

echo "Installed ArchContext:"
"$BIN_DIR/archcontext" --version
echo
echo "Wrapper: $BIN_DIR/archcontext"
echo "JAR:     $INSTALL_DIR/archcontext.jar"
echo
echo "Make sure $BIN_DIR is on PATH."
echo
echo "MCP command example:"
echo "  $BIN_DIR/archcontext mcp --root /absolute/path/to/workspace"

if [[ -n "$TMP_JAR" ]]; then
  rm -f "$TMP_JAR"
fi
