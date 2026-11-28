#!/bin/bash
set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "$(dirname "$0")"
if python3 scripts/release.py publish "$@"; then
  echo "发布完成，版本变更已提交并推送至当前分支的上游。"
else
  echo "发布未完成。重新运行将继续本次版本，不会重复增加版本号。"
  read -r -p "按回车关闭窗口…" || true
  exit 1
fi
read -r -p "按回车关闭窗口…" || true
