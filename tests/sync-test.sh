#!/usr/bin/env bash
set -euo pipefail
script=$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/sync.sh
root=$(mktemp -d)
export HOME="$root/home"
export GIT_CONFIG_NOSYSTEM=1
# Git Bash lacks flock. This opt-in tests Git behavior only, not locking.
if [ "${MOCK_FLOCK:-0}" = 1 ]; then
    flock() { return 0; }
    export -f flock
    echo 'MOCK_FLOCK=1: repository locking is not tested'
fi
mkdir -p "$HOME/storage/shared/Documents"
git config --global user.name Test
git config --global user.email test@example.com
git config --global core.autocrlf false
git init --bare "$root/remote.git" >/dev/null
git clone "$root/remote.git" "$HOME/storage/shared/Documents/obsidian" 2>/dev/null
cd "$HOME/storage/shared/Documents/obsidian"
echo first > note.md
git add .
git commit -m initial >/dev/null
git push -u origin HEAD >/dev/null 2>&1
git clone "$root/remote.git" "$root/peer" >/dev/null 2>&1
# Local change is committed and pushed.
echo local >> note.md
bash "$script" > "$root/local.log" 2>&1
test -z "$(git status --porcelain)"
test "$(git rev-parse HEAD)" = "$(git --git-dir="$root/remote.git" rev-parse HEAD)"
test -f "$HOME/.cache/gray/obsidian-sync.lock"
test ! -e .git/pixel-sync.lock
if [ "${MOCK_FLOCK:-0}" != 1 ]; then
    if flock "$HOME/.cache/gray/obsidian-sync.lock" bash "$script" > "$root/lock.log" 2>&1; then
        echo 'Expected concurrent sync to be rejected' >&2
        exit 1
    else
        test "$?" -eq 75
    fi
fi
# Clean run does not create an extra commit.
before=$(git rev-parse HEAD)
bash "$script" > "$root/clean.log" 2>&1
test "$before" = "$(git rev-parse HEAD)"
# Diverging nonconflicting edits merge and push.
git -C "$root/peer" pull >/dev/null 2>&1
echo remote > "$root/peer/remote.md"
git -C "$root/peer" add .
git -C "$root/peer" commit -m remote >/dev/null
git -C "$root/peer" push >/dev/null 2>&1
echo local > local.md
bash "$script" > "$root/merge.log" 2>&1
test -f remote.md
test "$(git rev-parse HEAD)" = "$(git --git-dir="$root/remote.git" rev-parse HEAD)"
# Conflicting edits fail and must not push.
git -C "$root/peer" pull >/dev/null 2>&1
echo peer > "$root/peer/note.md"
git -C "$root/peer" commit -am conflict >/dev/null
git -C "$root/peer" push >/dev/null 2>&1
remoteHead=$(git --git-dir="$root/remote.git" rev-parse HEAD)
echo ours > note.md
if bash "$script" > "$root/conflict.log" 2>&1; then exit 1; fi
test "$remoteHead" = "$(git --git-dir="$root/remote.git" rev-parse HEAD)"
test -n "$(git ls-files -u)"
if bash "$script" > "$root/unresolved.log" 2>&1; then exit 1; fi
echo "PASS: commit/push, clean run, divergent merge, conflict stops push, unresolved conflict rejected"
echo "Test logs: $root"
