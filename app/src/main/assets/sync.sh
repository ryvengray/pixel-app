#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
export GIT_TERMINAL_PROMPT=0
export GIT_SSH_COMMAND='ssh -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=2'
stage='准备'
trap 'rc=$?; printf "\n失败：%s（退出码 %s）。后续步骤已停止。\n" "$stage" "$rc" >&2; exit "$rc"' ERR
repo="$HOME/storage/shared/Documents/obsidian"
cd "$repo"
command -v git >/dev/null
command -v flock >/dev/null
command -v timeout >/dev/null
top=$(git rev-parse --show-toplevel)
if [ "$(cd "$top" && pwd -P)" != "$(pwd -P)" ]; then
    echo 'Obsidian 目录必须是仓库根目录，避免提交父目录中的其他文件。' >&2
    exit 1
fi
gitdir=$(git rev-parse --absolute-git-dir)
exec 9>"$gitdir/pixel-sync.lock"
flock -n 9 || { echo '已有同步任务正在执行。' >&2; exit 1; }
for state in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply; do
    if [ -e "$gitdir/$state" ]; then echo '仓库有未完成的合并或变基，请先在 Termux 处理。' >&2; exit 1; fi
done
if [ -n "$(git ls-files -u)" ]; then echo '存在未解决的冲突。' >&2; exit 1; fi
branch=$(git symbolic-ref --quiet --short HEAD)
git rev-parse --verify '@{upstream}' >/dev/null
remote=$(git config --get "branch.$branch.remote")
ref=$(git config --get "branch.$branch.merge")
if [ "$remote" = '.' ] || [[ "$ref" != refs/heads/* ]]; then echo '请为当前分支配置有效的远程跟踪分支。' >&2; exit 1; fi
stage='检查状态'
echo '步骤 1/4：检查状态'
git status --short
stage='提交本地更改'
if [ -n "$(git status --porcelain)" ]; then
    echo '步骤 2/4：提交本地更改'
    git add -A
    if ! git diff --cached --quiet; then git commit -m "Obsidian sync: $(date '+%Y-%m-%d %H:%M:%S %z')"; fi
else
    echo '步骤 2/4：无本地更改，跳过提交'
fi
stage='拉取远程更改'
echo '步骤 3/4：拉取远程更改'
timeout 180 git -c core.editor=true pull --no-rebase --no-edit "$remote" "$ref"
stage='推送本地提交'
echo '步骤 4/4：推送本地提交'
timeout 180 git push "$remote" "HEAD:$ref"
stage='最终检查'
git status --short
if [ -n "$(git status --porcelain)" ]; then echo '同步期间有新文件变化，请再次同步。' >&2; exit 1; fi
echo '同步完成：commit → pull → push 均已完成。'
