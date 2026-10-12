#!/usr/bin/env bash
# Create the release signing key used by local Ubuntu builds and GitHub Actions.
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"
signing_dir=.signing
store_file="$signing_dir/release.jks"
properties_file="$signing_dir/signing.properties"

if [[ -e "$store_file" || -e "$properties_file" ]]; then
  printf 'Signing files already exist. Keep the existing key for updates; do not replace it.\n' >&2
  exit 1
fi
command -v keytool >/dev/null 2>&1 || { printf 'Missing keytool. Install JDK 17 first.\n' >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { printf 'Missing openssl. Install it first.\n' >&2; exit 1; }

mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
export PIXEL_SIGNING_PASSWORD="$(openssl rand -base64 48 | tr -d '\n')"
trap 'unset PIXEL_SIGNING_PASSWORD' EXIT

keytool -genkeypair \
  -keystore "$store_file" \
  -storetype JKS \
  -alias pixel-sync \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000 \
  -dname 'CN=Pixel Obsidian Sync' \
  -storepass:env PIXEL_SIGNING_PASSWORD \
  -keypass:env PIXEL_SIGNING_PASSWORD

cat > "$properties_file" <<EOF
RELEASE_STORE_FILE=.signing/release.jks
RELEASE_STORE_PASSWORD=$PIXEL_SIGNING_PASSWORD
RELEASE_KEY_ALIAS=pixel-sync
RELEASE_KEY_PASSWORD=$PIXEL_SIGNING_PASSWORD
EOF
chmod 600 "$store_file" "$properties_file"
printf 'Release signing key created in .signing/. Back up this folder securely; it is excluded from Git.\n'
