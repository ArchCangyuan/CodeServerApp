#!/usr/bin/env bash
# Downloads the IronRDP web client (MIT OR Apache-2.0) used by the built-in
# remote desktop, verifies it, and places it next to rdp.html in the app assets.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESTINATIONS=("$ROOT/android/app/src/main/assets/rdp")

WEB_COMPONENT="@devolutions/iron-remote-desktop@0.11.0"
WEB_COMPONENT_SHA256="22359dfb201017ebf7f25c2e496dbbfd1668bcad1a34d0dabfd05a968e1aa50f"
RDP_BACKEND="@devolutions/iron-remote-desktop-rdp@0.7.0"
RDP_BACKEND_SHA256="b008f0e258fd9485c6f2b07747116d4fcbbe51053ce995abd048fb2b79636332"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() {
  local spec="$1" file="$2" expected="$3"
  mkdir -p "$WORK/$file"
  (cd "$WORK/$file" && npm pack "$spec" --silent >/dev/null && tar xzf ./*.tgz)
  local actual
  actual="$(sha256sum "$WORK/$file/package/$file" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch for $spec ($file): $actual" >&2
    exit 1
  fi
  for destination in "${DESTINATIONS[@]}"; do
    mkdir -p "$destination"
    cp "$WORK/$file/package/$file" "$destination/$file"
  done
}

fetch "$WEB_COMPONENT" iron-remote-desktop.js "$WEB_COMPONENT_SHA256"
fetch "$RDP_BACKEND" iron-remote-desktop-rdp.js "$RDP_BACKEND_SHA256"
echo "IronRDP web client ready."
