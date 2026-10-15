#!/usr/bin/env bash
# Build, wirelessly install, and launch Greta Dev from Ubuntu.
set -euo pipefail

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
valid_port() { [[ "$1" =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 )); }
adb_devices() { timeout 8s "$adb_bin" devices 2>/dev/null; }

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
device_config="$project_root/.greta-wireless-device"
saved_ip=""
saved_connect_port=""

if [[ -f "$device_config" ]]; then
  saved_ip="$(sed -n '1p' "$device_config")"
  saved_connect_port="$(sed -n '2p' "$device_config")"
  if [[ -z "$saved_ip" || "$saved_ip" == *:* ]] || ! valid_port "$saved_connect_port"; then
    saved_ip=""
    saved_connect_port=""
  fi
fi

prompt_for_device() {
  local suggested_ip="${1:-}"
  local suggested_port="${2:-}"
  read -r -p "手机地址（IP 或 .local 名称）${suggested_ip:+ [$suggested_ip]}：" phone_ip
  phone_ip="${phone_ip:-$suggested_ip}"
  [[ -n "$phone_ip" ]] || die "手机地址不能为空。"
  [[ "$phone_ip" != *:* ]] || die "这里只填写设备地址，端口请单独填写。"

  read -r -p '这台电脑是否需要首次配对？[y/N]：' need_pair
  if [[ "$need_pair" =~ ^[Yy]$ ]]; then
    read -r -p "配对端口（来自「使用配对码配对设备」弹窗）${default_pair_port:+ [$default_pair_port]}：" pair_port
    pair_port="${pair_port:-$default_pair_port}"
    valid_port "$pair_port" || die "配对端口应为 1–65535。"

    read -r -p '六位数配对码：' pair_code
    [[ "$pair_code" =~ ^[0-9]{6}$ ]] || die "配对码应为六位数字。"

    printf '\n正在配对 %s:%s…\n' "$phone_ip" "$pair_port"
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

  read -r -p "无线调试连接端口（无线调试主页显示的端口）${suggested_port:+ [$suggested_port]}：" connect_port
  connect_port="${connect_port:-$suggested_port}"
  valid_port "$connect_port" || die "连接端口应为 1–65535。"
}

using_saved=false
if [[ -n "$default_ip" && -n "$default_connect_port" ]]; then
  phone_ip="$default_ip"
  connect_port="$default_connect_port"
  [[ "$phone_ip" != *:* ]] || die "设备地址不要附带端口。"
  valid_port "$connect_port" || die "连接端口应为 1–65535。"
elif [[ -n "$saved_ip" && -n "$saved_connect_port" ]]; then
  phone_ip="$saved_ip"
  connect_port="$saved_connect_port"
  using_saved=true
  printf '使用已保存设备：%s:%s\n' "$phone_ip" "$connect_port"
else
  # If adb already knows exactly one IP/hostname device, adopt it immediately.
  devices_output="$(adb_devices || true)"
  mapfile -t known_devices < <(printf '%s\n' "$devices_output" | awk 'NR > 1 && $2 == "device" && $1 ~ /:[0-9]+$/ {print $1}')
  if (( ${#known_devices[@]} == 1 )); then
    phone_ip="${known_devices[0]%:*}"
    connect_port="${known_devices[0]##*:}"
    printf '发现已连接设备：%s:%s\n' "$phone_ip" "$connect_port"
  else
    prompt_for_device "$default_ip" "$default_connect_port"
  fi
fi

connect_device() {
  local device="$phone_ip:$connect_port"
  local devices_output connect_result connect_status
  local -a online_devices=()
  printf '\n正在连接 %s…\n' "$device"
  selected_device=""
  devices_output="$(adb_devices || true)"
  if printf '%s\n' "$devices_output" | awk -v target="$device" '$1 == target && $2 == "device" {found=1} END {exit !found}'; then
    selected_device="$device"
    printf '设备已经连接。\n'
  else
    set +e
    connect_result="$(timeout 12s "$adb_bin" connect "$device" 2>&1)"
    connect_status=$?
    set -e
    [[ -n "$connect_result" ]] && printf '%s\n' "$connect_result"
    if (( connect_status == 124 )); then
      printf 'adb connect 未及时返回，继续检查手机是否已经在线…\n'
    fi

    devices_output="$(adb_devices || true)"
    if printf '%s\n' "$devices_output" | awk -v target="$device" '$1 == target && $2 == "device" {found=1} END {exit !found}'; then
      selected_device="$device"
    else
      mapfile -t online_devices < <(printf '%s\n' "$devices_output" | awk 'NR > 1 && $2 == "device" {print $1}')
      if (( ${#online_devices[@]} == 1 )); then
        selected_device="${online_devices[0]}"
        printf '发现已在线设备：%s\n' "$selected_device"
      elif (( ${#online_devices[@]} > 1 )); then
        printf '检测到多个在线设备：\n'
        printf '  %s\n' "${online_devices[@]}"
        read -r -p '请输入要安装到的设备序列号：' selected_device
        printf '%s\n' "${online_devices[@]}" | awk -v target="$selected_device" '$0 == target {found=1} END {exit !found}' || return 1
      else
        printf '\nadb 设备列表：\n%s\n' "$devices_output" >&2
        return 1
      fi
    fi
  fi
}

if ! connect_device; then
  if [[ "$using_saved" == true ]]; then
    printf '\n已保存的连接已失效，请输入无线调试页面当前显示的信息。\n'
    prompt_for_device "$saved_ip" ""
    connect_device || die "仍然无法连接手机，请确认无线调试已开启。"
  else
    die "手机未进入可安装状态。请保持无线调试开启，并确认填写的是连接端口而不是配对端口。"
  fi
fi

printf '%s\n%s\n' "$phone_ip" "$connect_port" > "$device_config"
chmod 600 "$device_config"
printf '已保存设备连接，下次运行无需再次输入。\n'

printf '\n正在构建 Greta Dev…\n'
./gradlew :app:assembleDevDebug

apk_path="app/build/outputs/apk/dev/debug/app-dev-debug.apk"
[[ -f "$apk_path" ]] || die "构建完成，但没有找到 $apk_path。"

printf '\n正在安装到 %s…\n' "$selected_device"
"$adb_bin" -s "$selected_device" install -r -d "$apk_path"

printf '\n正在启动 Greta Dev…\n'
"$adb_bin" -s "$selected_device" shell am start -n dev.pixel.sync.dev/dev.pixel.sync.MainActivity >/dev/null

printf '\n完成：Greta Dev 已安装并启动。\n'
printf '下次直接运行同一脚本即可。\n'
