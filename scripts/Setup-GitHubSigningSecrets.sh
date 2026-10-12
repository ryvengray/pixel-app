#!/usr/bin/env bash
# Upload the local release signing material to GitHub Actions once per repository.
set -euo pipefail

usage() { printf 'Usage: bash scripts/Setup-GitHubSigningSecrets.sh owner/repository\n' >&2; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

[[ $# -eq 1 ]] || { usage; exit 2; }
repository="$1"
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "Use owner/repository."
command -v gh >/dev/null 2>&1 || die "Install GitHub CLI (gh) first."
gh auth status >/dev/null 2>&1 || die "Run 'gh auth login' first."

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"
properties=.signing/signing.properties
[[ -f "$properties" ]] || die "Missing $properties."

property() { awk -F= -v wanted="$1" '$1 == wanted {sub(/^[^=]*=/, ""); print; exit}' "$properties"; }
store_file="$(property RELEASE_STORE_FILE)"
[[ -n "$store_file" && -f "$store_file" ]] || die "Release keystore is missing."

base64 --wrap=0 "$store_file" | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$repository"
for secret in RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  value="$(property "$secret")"
  [[ -n "$value" ]] || die "Missing $secret in $properties."
  printf '%s' "$value" | gh secret set "$secret" --repo "$repository"
done

firebase_config=app/google-services.json
[[ -f "$firebase_config" ]] || die "Missing $firebase_config. Download it from Firebase Console first."
base64 --wrap=0 "$firebase_config" | gh secret set FIREBASE_ANDROID_CONFIG_BASE64 --repo "$repository"

printf 'GitHub Actions signing and Firebase secrets are configured for %s.\n' "$repository"
