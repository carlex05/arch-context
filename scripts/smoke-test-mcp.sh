#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT_DIR/target/archcontext.jar"
WORKSPACE="$(mktemp -d)"
STDOUT_FILE="$(mktemp)"
STDERR_FILE="$(mktemp)"

cleanup() {
  rm -rf "$WORKSPACE"
  rm -f "$STDOUT_FILE" "$STDERR_FILE"
}
trap cleanup EXIT

echo "Building ArchContext..."
mvn -q -f "$ROOT_DIR/pom.xml" clean package

echo "Preparing sample workspace at $WORKSPACE..."
cp -R "$ROOT_DIR/examples/sample-workspace/.archcontext" "$WORKSPACE/.archcontext"

echo "Importing sample context..."
java -jar "$JAR" import --root "$WORKSPACE"

echo "Running doctor..."
java -jar "$JAR" doctor --root "$WORKSPACE"

echo "Sending minimal MCP initialize request..."
(
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"archcontext-smoke-test","version":"0"}}}'
  sleep 3
) | timeout 5 java -jar "$JAR" mcp --root "$WORKSPACE" >"$STDOUT_FILE" 2>"$STDERR_FILE" || true

if ! grep -q '"serverInfo"' "$STDOUT_FILE"; then
  echo "Expected MCP initialize response was not found on stdout." >&2
  echo "--- stdout ---" >&2
  cat "$STDOUT_FILE" >&2
  echo "--- stderr ---" >&2
  cat "$STDERR_FILE" >&2
  exit 1
fi

echo "MCP initialize response:"
cat "$STDOUT_FILE"
echo
echo "Smoke test passed."
echo "This smoke test validates packaging and a minimal stdio handshake; it does not replace testing with Claude Desktop, Cursor, Claude Code, or another real MCP client."
