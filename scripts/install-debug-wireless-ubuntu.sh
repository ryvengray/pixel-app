#!/usr/bin/env bash
# Build, wirelessly install, and launch Gray Dev from Ubuntu.
set -euo pipefail

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
valid_port() { [[ "$1" =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
adb_bin=""
adb_candidates=()
[[ -n "$sdk_root" ]] && adb_candidates+=("$sdk_root/platform-tools/adb")
[[ -n "${ANDROID_SDK_ROOT:-}" ]] && adb_candidates+=("$ANDROID_SDK_ROOT/platform-tools/adb")
[[ -n "${ANDROID_HOME:-}" ]] && adb_candidates+=("$ANDROID_HOME/platform-tools/adb")
adb_candidates+=(
  "$HOME/Android/Sdk/platform-tools/adb"
  "$HOME/Android/sdk/platform-tools/adb"
  "/opt/android-sdk/platform-tools/adb"
)
if command -v adb >/dev/null 2>&1; then
  adb_candidates+=("$(command -v adb)")
fi
for candidate in "${adb_candidates[@]}"; do
  if [[ -x "$candidate" ]]; then
    adb_bin="$candidate"
    break
  fi
done
[[ -n "$adb_bin" ]] || die "找不到 adb。请安装 Android SDK Platform Tools。"

if ! command -v java >/dev/null 2>&1; then
  die "找不到 Java。请安装 JDK 17，例如：sudo apt install openjdk-17-jdk"
fi

java_major="$(java -version 2>&1 | awk -F'[\".]' '/version/ {if ($2 == "1") print $3; else print $2; exit}')"
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
  if [[ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    die "构建需要 JDK 17 或更高版本，当前 Java 版本为 ${java_major:-未知}。"
  fi
fi

printf '\nGray Dev 无线安装（Ubuntu）\n'
printf '请先让电脑和手机连接同一个网络，并在手机上开启「开发者选项 → 无线调试」。\n\n'
printf '使用 adb：%s\n' "$adb_bin"
"$adb_bin" version | sed -n '1,2p'
printf '\n'

default_ip="${1:-}"
default_connect_port="${2:-}"
default_pair_port="${3:-}"

read -r -p "手机 IP${default_ip:+ [$default_ip]}：" phone_ip
phone_ip="${phone_ip:-$default_ip}"
[[ -n "$phone_ip" ]] || die "手机 IP 不能为空。"
[[ "$phone_ip" != *:* ]] || die "这里只填写 IP，不要附带端口。"

read -r -p '这台电脑是否需要首次配对？[y/N]：' need_pair
if [[ "$need_pair" =~ ^[Yy]$ ]]; then
  read -r -p "配对端口（来自「使用配对码配对设备」弹窗）${default_pair_port:+ [$default_pair_port]}：" pair_port
  pair_port="${pair_port:-$default_pair_port}"
  valid_port "$pair_port" || die "配对端口应为 1–65535。"

  read -r -p '六位数配对码：' pair_code
  [[ "$pair_code" =~ ^[0-9]{6}$ ]] || die "配对码应为六位数字。"

  printf '\n正在配对 %s:%s…\n' "$phone_ip" "$pair_port"
  # Avoid talking to a daemon left behind by another adb installation, which
  # can produce "protocol fault" even though the phone and pairing code are OK.
  "$adb_bin" kill-server >/dev/null 2>&1 || true
  if ! pair_output="$("$adb_bin" pair "$phone_ip:$pair_port" "$pair_code" 2>&1)"; then
    printf '%s\n' "$pair_output" >&2
    if [[ "$pair_output" == *"protocol fault"* ]]; then
      printf '检测到 adb 协议异常，正在重启 Android SDK 的 adb 后重试…\n'
      "$adb_bin" kill-server >/dev/null 2>&1 || true
      sleep 1
      "$adb_bin" pair "$phone_ip:$pair_port" "$pair_code"
    else
      exit 1
    fi
  else
    printf '%s\n' "$pair_output"
  fi
fi

read -r -p "无线调试连接端口（无线调试主页显示的端口）${default_connect_port:+ [$default_connect_port]}：" connect_port
connect_port="${connect_port:-$default_connect_port}"
valid_port "$connect_port" || die "连接端口应为 1–65535。"

device="$phone_ip:$connect_port"
printf '\n正在连接 %s…\n' "$device"
connect_result="$("$adb_bin" connect "$device")"
printf '%s\n' "$connect_result"

if ! "$adb_bin" devices | awk -v target="$device" '$1 == target && $2 == "device" {found=1} END {exit !found}'; then
  die "手机未进入可安装状态。请保持无线调试页面开启，确认连接端口没有填成配对端口。"
fi

printf '\n正在构建 Gray Dev…\n'
./gradlew :app:assembleDevDebug

apk_path="app/build/outputs/apk/dev/debug/app-dev-debug.apk"
[[ -f "$apk_path" ]] || die "构建完成，但没有找到 $apk_path。"

printf '\n正在安装到 %s…\n' "$device"
"$adb_bin" -s "$device" install -r -d "$apk_path"

printf '\n正在启动 Gray Dev…\n'
"$adb_bin" -s "$device" shell am start -n dev.pixel.sync.dev/dev.pixel.sync.MainActivity >/dev/null

printf '\n完成：Gray Dev 已安装并启动。\n'
printf '下次手机端口未变化时，可运行：\n'
printf '  bash scripts/install-debug-wireless-ubuntu.sh %q %q\n' "$phone_ip" "$connect_port"
