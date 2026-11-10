#!/usr/bin/env bash
# Reconnect the paired phone without rebuilding/installing the app or sending input.
set -euo pipefail

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
adb_bin="${ADB_BIN:-}"
serial="${ADB_SERIAL:-}"
while (( $# )); do
  case "$1" in
    --adb|--serial)
      (( $# >= 2 )) || die "$1 缺少参数"
      if [[ "$1" == --adb ]]; then adb_bin="$2"; else serial="$2"; fi
      shift 2 ;;
    -h|--help)
      printf '用法：bash scripts/reconnect-wireless-ubuntu.sh [--adb ADB路径] [--serial 后台设备名]\n'
      printf '默认使用 pixel-phone-vision 用户服务的设备名，未安装服务时使用仓库模板。\n'
      exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done
command -v timeout >/dev/null || die '需要 Ubuntu 的 timeout 命令'
if [[ -z "$adb_bin" ]]; then
  for candidate in "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" "$HOME/Android/sdk/platform-tools/adb" \
    /opt/android-sdk/platform-tools/adb; do
    if [[ -x "$candidate" ]]; then adb_bin="$candidate"; break; fi
  done
  [[ -n "$adb_bin" ]] || adb_bin="$(command -v adb || true)"
fi
[[ -x "$adb_bin" ]] || die '找不到 adb，请用 --adb 指定 Android SDK platform-tools/adb'
if [[ -z "$serial" ]]; then
  service_command="$(timeout 5s systemctl --user show pixel-phone-vision.service --property=ExecStart --value 2>/dev/null || true)"
  serial="$(printf '%s\n' "$service_command" | sed -nE 's/.*--serial[[:space:]]+([^[:space:];]+).*/\1/p')"
  if [[ -z "$serial" ]]; then
    serial="$(sed -nE 's/.*--serial[[:space:]]+([^[:space:]]+).*/\1/p' "$project_root/server/pixel-phone-vision.service")"
  fi
fi
[[ -n "$serial" && "$serial" != -* && "$serial" != *[[:space:]]* ]] || die '无效的设备名，请用 --serial 指定后台配置的设备名'
printf '使用 adb：%s\n目标设备：%s\n' "$adb_bin" "$serial"
ready() {
  [[ "$(timeout 8s "$adb_bin" -s "$serial" get-state 2>/dev/null || true)" == device ]] &&
    [[ "$(timeout 8s "$adb_bin" -s "$serial" shell echo wireless-ok 2>/dev/null | tr -d '\r' || true)" == wireless-ok ]]
}
if ! ready; then
  # Retry discovery while mDNS starts up. Keep the service name as the transport
  # serial so existing gateways continue to address this exact device.
  for attempt in 1 2 3; do
    printf '正在重连（%s/3）…\n' "$attempt"
    timeout 8s "$adb_bin" mdns services || true
    timeout 15s "$adb_bin" connect "$serial" || true
    if ready; then break; fi
    if (( attempt < 3 )); then sleep 2; fi
  done
fi
if ! ready; then
  printf '\n当前设备：\n' >&2
  timeout 8s "$adb_bin" devices -l >&2 || true
  printf '\n请确认手机和 Ubuntu 在同一局域网，并开启手机「开发者选项 → 无线调试」。\n' >&2
  printf '若尚未配对或授权已失效，使用同一 Ubuntu 用户执行：\n  %q pair 手机IP:配对端口\n' "$adb_bin" >&2
  printf '输入手机显示的配对码后，再运行本脚本。配对端口和连接端口不同。\n' >&2
  printf '若 mDNS 不可用，可用 --serial 手机IP:连接端口，但后台服务的 --serial 也必须一致。\n' >&2
  exit 1
fi
printf '\n无线调试已连接，shell 读取验证通过。请刷新控制页面并重新识别。\n'
