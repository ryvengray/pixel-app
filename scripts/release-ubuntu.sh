#!/usr/bin/env bash
# Build and publish a signed Android release from Ubuntu.
# GitHub Actions publishes the GitHub Release after the pushed v* tag arrives.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash scripts/release-ubuntu.sh [version]

Without a version argument, the script reads the current stable VERSION_NAME and lets you choose one of:
  1. Major release:  1.2.1 → 2.0.0
  2. Feature update: 1.2.1 → 1.3.0
  3. Bug-fix update: 1.2.1 → 1.2.2

Pass an explicit stable version such as 1.4.0 to skip the interactive menu.

Requirements:
  - Ubuntu with JDK 17, Android SDK Platform 35 and Build Tools 35.0.0
  - ANDROID_HOME or ANDROID_SDK_ROOT points to that Android SDK
  - A clean checkout on a pushed branch with Git author identity configured
  - Existing .signing/release.jks and .signing/signing.properties, or RELEASE_*
    environment variables for the same signing key
  - GitHub Actions signing secrets configured (run Setup-GitHubSigningSecrets.sh once)
EOF
}

die() { printf 'Error: %s\n' "$*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"; }

[[ $# -le 1 ]] || { usage; exit 2; }
requested_version="${1:-}"
if [[ -n "$requested_version" ]]; then
  [[ "$requested_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Explicit version must look like 1.2.0."
elif [[ ! -t 0 ]]; then
  die "Run without a version from an interactive terminal so you can choose the release type."
fi

require_command git
require_command java
require_command sha256sum

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"

[[ -z "$(git status --porcelain)" ]] || die "Working tree is not clean. Commit or stash changes first."
branch="$(git branch --show-current)"
[[ -n "$branch" ]] || die "Checkout a branch, not a detached commit."
git config user.name >/dev/null || die "Set git user.name before publishing."
git config user.email >/dev/null || die "Set git user.email before publishing."
git fetch origin --tags
git pull --ff-only origin "$branch"

java_major="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
[[ "$java_major" =~ ^(17|18|19|20|21)$ ]] || die "JDK 17 or newer is required; found Java $java_major."

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_root" ]] || die "Set ANDROID_HOME or ANDROID_SDK_ROOT."
[[ -d "$sdk_root/platforms/android-35" ]] || die "Missing Android SDK Platform 35."
apksigner="$sdk_root/build-tools/35.0.0/apksigner"
[[ -x "$apksigner" ]] || die "Missing Android SDK Build Tools 35.0.0."

if [[ -z "${RELEASE_STORE_FILE:-}" ]]; then
  [[ -f .signing/signing.properties ]] || die "Missing .signing/signing.properties or RELEASE_* environment variables."
  store_relative="$(awk -F= '$1 == "RELEASE_STORE_FILE" {sub(/^[^=]*=/, ""); print; exit}' .signing/signing.properties)"
  [[ -n "$store_relative" && -f "$store_relative" ]] || die "The configured release keystore is missing."
else
  [[ -f "$RELEASE_STORE_FILE" ]] || die "RELEASE_STORE_FILE does not exist."
  [[ -n "${RELEASE_STORE_PASSWORD:-}" && -n "${RELEASE_KEY_ALIAS:-}" && -n "${RELEASE_KEY_PASSWORD:-}" ]] || die "All RELEASE_* signing variables are required."
fi

version_file="app/version.properties"
current_code="$(awk -F= '$1 == "VERSION_CODE" {print $2; exit}' "$version_file")"
[[ "$current_code" =~ ^[1-9][0-9]*$ ]] || die "Invalid VERSION_CODE."
current_version="$(awk -F= '$1 == "VERSION_NAME" {print $2; exit}' "$version_file")"
[[ "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "VERSION_NAME must be a stable version such as 1.2.1."
current_major="${BASH_REMATCH[1]}"
current_minor="${BASH_REMATCH[2]}"
current_patch="${BASH_REMATCH[3]}"
major_release="$((current_major + 1)).0.0"
feature_release="$current_major.$((current_minor + 1)).0"
bugfix_release="$current_major.$current_minor.$((current_patch + 1))"

if [[ -n "$requested_version" ]]; then
  release_version="$requested_version"
  printf '使用指定版本：%s（当前版本：%s）\n' "$release_version" "$current_version"
else
  printf '\n当前版本：%s\n\n' "$current_version"
  printf '  1) 主版本升级  → %s  适合有明显变化、需要重点说明的更新\n' "$major_release"
  printf '  2) 功能更新    → %s  适合新增功能和常规体验改进\n' "$feature_release"
  printf '  3) 修复更新    → %s  适合问题修复和小幅优化\n\n' "$bugfix_release"
  read -r -p '请选择发布类型 [1-3]：' release_choice
  case "$release_choice" in
    1) release_version="$major_release" ;;
    2) release_version="$feature_release" ;;
    3) release_version="$bugfix_release" ;;
    *) die "请选择 1、2 或 3。" ;;
  esac
fi

tag="v$release_version"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "Local tag $tag already exists."
set +e
git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1
remote_tag_status=$?
set -e
if [[ "$remote_tag_status" -eq 0 ]]; then
  die "Remote tag $tag already exists."
elif [[ "$remote_tag_status" -ne 2 ]]; then
  die "Could not check whether remote tag $tag exists."
fi

next_code=$((current_code + 1))

printf 'Building %s (VERSION_CODE %s) from branch %s…\n' "$tag" "$next_code" "$branch"
sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$next_code/; s/^VERSION_NAME=.*/VERSION_NAME=$release_version/" "$version_file"

bash tests/sync-test.sh
bash gradlew --no-daemon assembleRelease

apk_source="app/build/outputs/apk/release/app-release.apk"
[[ -f "$apk_source" ]] || die "Release APK was not generated."
"$apksigner" verify --verbose "$apk_source"

mkdir -p dist
apk_name="pixel-obsidian-sync-$release_version.apk"
cp "$apk_source" "dist/$apk_name"
(cd dist && sha256sum "$apk_name" > SHA256SUMS)

git add "$version_file"
git commit -m "Release $tag"
git push origin "$branch"
git tag -a "$tag" -m "Release $tag"
git push origin "$tag"

printf '\nPublished %s. GitHub Actions will create the GitHub Release for Obtainium.\n' "$tag"
printf 'Local verification APK: dist/%s\n' "$apk_name"
