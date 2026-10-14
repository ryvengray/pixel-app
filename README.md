# Gray

## 宠物首页

首页是全屏动态背景和实时 3D 玉绿色圆润三角形宠物。宠物会呼吸、漂浮、眨眼、轻微扭动，轻触时弹跳并变换嘴型。右上角小圆钮或长按宠物可展开底部菜单，进入 Obsidian 同步、选择跟随系统/浅色/深色主题、开启减少动态效果。主题偏好会保留，离开首页时暂停渲染。系统栏默认隐藏，从屏幕边缘滑动可临时唤出。

文字聊天已接入私有助理服务：从右上角菜单进入“和 Gray 聊聊”。完整对话和长期记忆以 Pixel 本地数据库为主，服务端仅保留后台任务所需的同步记忆副本。可在“我的长期记忆”中查看、修正、手动添加或删除记忆。

## 私人助理服务

服务端代码在 [`server/`](server/README.md)，与 Android App 保持在同一个项目中。它负责调用 DeepSeek、执行提醒和保存待推送消息。将 `server/.env.example` 复制为 `server/.env`，填入带 `/gray` context 的 frp 公网 HTTPS 地址和 `DEEPSEEK_API_KEY` 后，在项目根目录运行 `docker compose up -d --build`；`.env`、服务端数据库和 Firebase 服务账户文件均被 Git 忽略。完整的 Docker、frp 和 Nginx 配置见 [`server/DEPLOYMENT.md`](server/DEPLOYMENT.md)。手机通过一次性二维码配对，不需要输入长期访问令牌。

App 的“助理设置”包含两种连接方式：

- 正常情况：调用你本地电脑上的私有服务。手机不保留服务端 DeepSeek Key。
- 服务端不可用时：可选择配置个人 DeepSeek Key 作为手机直连兜底。该 Key 与服务访问令牌都由 Android Keystore 加密保存，不写入 APK、数据库或 Git。

没有网络时，App 仍可查看、编辑记忆和历史，但无法获得模型回复；服务端恢复后才能继续后台提醒和主动通知。Android 端已接入 Firebase Cloud Messaging：扫码配对后会自动注册设备令牌，服务端使用 Firebase 服务账户发送系统推送。Android 13 及以上首次配对时会请求通知权限。

适用于 Pixel 9 Pro 的原生 Android App（Android 12 及以上）。点击“立即同步”，通过 Termux 执行固定目录 `~/storage/shared/Documents/obsidian` 的 Git 同步。

流程：检查仓库 → 有更改时 `git add -A` 和 `git commit` → `git pull --no-rebase --no-edit` → `git push`。使用当前分支的 upstream 远程和分支，不强制推送。`.gitignore` 中的文件不会提交，其他新增、修改、删除会全部提交。拉取采用合并方式，出现冲突时停止，需手动解决后重试。

## 手机首次配置

安装支持 RUN_COMMAND 的 Termux（包名 `com.termux`，建议使用官方 GitHub 或 F-Droid 版本）。无需 Termux:API。

在 Termux 执行：

```bash
pkg update
pkg install git openssh coreutils util-linux
termux-setup-storage
mkdir -p ~/.termux
# 若已存在该配置项，将其改为 true，不要保留 false 的重复项。
echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
cd ~/storage/shared/Documents/obsidian
git status
git branch -vv
```

目录必须已经是 Git 仓库，当前分支需有 upstream。没有时按实际远程分支配置，例如 `git branch --set-upstream-to=origin/main main`。如果 Git 提示 dubious ownership，仅对这个可信仓库运行 `git config --global --add safe.directory ~/storage/shared/Documents/obsidian`。

先在 Termux 手动完成一次 pull 和 push，确认认证可用。推荐 SSH 密钥；提前信任远程主机，使用可无交互认证的密钥或已配置的 ssh-agent。App 不会弹出 Git 密码输入框，SSH 强制 BatchMode，HTTPS 禁止终端询问密码。

安装 App 后，授予“在 Termux 环境中运行命令”权限；如果弹窗未出现，打开 App 内“调用权限设置”，检查附加权限。保持 Termux 正常运行，必要时在系统设置中允许其后台运行。同步时暂停在 Obsidian 编辑；完成后日志若提示又有变化，再同步一次。

## Android Studio 运行与构建

项目提供两个构建变体：

| 变体 | 用途 | 应用名称 | 包名 | 推送 |
| --- | --- | --- | --- | --- |
| `devDebug` | 本机开发、调试 | Gray Dev | `dev.pixel.sync.dev` | 关闭 |
| `prodDebug` | 调试正式配置 | Gray | `dev.pixel.sync` | 使用正式 Firebase 配置 |
| `prodRelease` | 正式发布 | Gray | `dev.pixel.sync` | 使用正式 Firebase 配置 |

日常开发请运行 `devDebug`。它和正式版包名不同，可以同时安装；聊天记录、应用设置和加密密钥也分别保存在各自应用空间。开发版没有 Firebase 注册配置，因此不会注册或接收正式推送。

### 在 Android Studio 中运行开发版

1. 在 Android Studio 选择 **File > Open**，打开本项目根目录（包含 `settings.gradle` 的目录）。
2. 等待 Gradle Sync 完成。项目使用 Android SDK 35、JDK 17 和 Gradle 8.11.1；如果提示缺少 SDK 或 JDK，请在 SDK Manager 安装 SDK Platform 35，并在 **Settings > Build, Execution, Deployment > Build Tools > Gradle** 将 Gradle JDK 设为 JDK 17。
3. 打开 **View > Tool Windows > Build Variants**。
4. 在 `app` 模块的 **Active Build Variant** 下拉框中选择 **`devDebug`**。
5. 连接 Android 手机并开启 USB 调试，或启动 Android 虚拟设备；在工具栏设备列表中选择目标设备。
6. 选择运行配置 **app**，点击工具栏的绿色 **Run ▶** 按钮。首次安装显示为 **Gray Dev**。

修改应用后再次点击 **Run**，Android Studio 只会更新开发版，不会覆盖已安装的正式版。卸载开发版也不会删除正式版的应用数据。

### 在 Ubuntu 终端无线安装开发版

手机和电脑连接同一网络，在手机的“开发者选项 → 无线调试”中查看 IP 和端口，然后运行：

```bash
bash scripts/install-debug-wireless-ubuntu.sh
```

脚本会引导首次无线配对，随后构建、覆盖安装并启动 `devDebug`。连接成功后会将设备地址和连接端口保存在本机的 `.gray-wireless-device` 中，后续直接运行脚本即可；端口失效时脚本才会重新询问。手机弹窗中的配对端口和无线调试主页中的连接端口是两个不同的端口。配对码与配对端口不会保存。

### 构建正式版

需要在 Android Studio 调试正式配置时选择 `prodDebug`。要构建可发布的正式 APK，请先按下方“正式打包与 Obtainium 更新”配置固定签名密钥，再在 Build Variants 选择 `prodRelease`，然后选择 **Build > Generate App Bundles or APKs > Build APKs**。正式版包名保持 `dev.pixel.sync`，可作为独立安装包更新已安装的正式版。

也可通过 Gradle 构建开发版和正式调试版：

```bash
./gradlew :app:assembleDevDebug :app:assembleProdDebug
```

APK 分别输出到 `app/build/outputs/apk/dev/debug/` 和 `app/build/outputs/apk/prod/debug/`。正式发布请使用下方固定签名的 `prodRelease`，不要把 debug APK 当作正式更新包。

## 正式打包与 Obtainium 更新

正式版包名固定为 `dev.pixel.sync`，开发版包名为 `dev.pixel.sync.dev`。正式版使用同一个签名密钥，更新时必须保留该密钥。密钥和密码存放于 `.signing/`，已经被 Git 忽略；请将整个目录安全备份，勿提交到公开仓库。首次切换到正式签名时，如果 Android 提示签名不匹配，请卸载旧的同包名应用后重新安装；开发版与正式版包名不同，不需要互相卸载。

首次创建密钥，然后构建：

```powershell
$env:JAVA_HOME='你的 JDK 17 目录'
.\scripts\New-ReleaseKey.ps1
.\scripts\Build-Release.ps1
```

APK 和 SHA256 校验文件输出至 `dist/`。后续更新只运行构建脚本，不要重新生成签名密钥。

### GitHub Releases

源码仓库：[ryvengray/pixel-app](https://github.com/ryvengray/pixel-app)。Obtainium 中添加此链接即可跟踪正式版更新。GitHub Actions 配置在 `.github/workflows/release.yml`。首次需在 GitHub 仓库的 Actions Secrets 中配置签名信息；已经登录 GitHub CLI 时可以执行：

```powershell
.\scripts\Set-GitHubSigningSecrets.ps1 -Repository 'ryvengray/pixel-app'
git push origin main
git tag v1.0.0
git push origin v1.0.0
```

推送 `v*` 标签将自动测试、构建、验证签名，并发布一个含 APK 的 GitHub Release。标签必须与 `app/version.properties` 的 `VERSION_NAME` 一致，例如 `v1.0.0`。Obtainium 添加该 GitHub 仓库链接即可获取 Release APK，不需要自己下载 Actions artifacts。每个 Release 仅提供一个通用 APK，适用于 Pixel 9 Pro。

后续发布前，将 `VERSION_CODE` 增加为更大的整数，并更新 `VERSION_NAME`；提交推送后创建对应新标签。不更换包名或签名密钥，不复用旧标签。

### Ubuntu 自动发布

以下步骤用于全新 Ubuntu 发布机。`gh` 使用 HTTPS 凭据推送代码和标签；GitHub Actions 已配置的签名 Secrets 会留在仓库中，不需要因更换电脑重新设置。

#### 安装 GitHub CLI、JDK 和 Android SDK

安装系统依赖和 GitHub CLI 官方 APT 源（命令参考 [GitHub CLI Linux 安装说明](https://github.com/cli/cli/blob/trunk/docs/install_linux.md)）：

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg git openjdk-17-jdk unzip zip

sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg >/dev/null
sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
  | sudo tee /etc/apt/sources.list.d/github-cli.list >/dev/null
sudo apt update
sudo apt install -y gh
```

确认系统选择的是 JDK 17。若 `java -version` 仍显示 Java 8，可切换 alternatives：

```bash
sudo update-alternatives --config java
sudo update-alternatives --config javac
java -version
javac -version
```

在两个选择菜单中都选路径包含 `java-17-openjdk` 的项目。

安装 Android 命令行工具，并配置项目要求的 SDK Platform 35 与 Build Tools 35.0.0。下面固定使用 Google 当前提供的命令行工具包版本；需要更新时，可从 [Android 命令行工具页面](https://developer.android.com/studio#command-line-tools-only) 获取最新 Linux 下载链接。SDK 组件安装使用官方 [`sdkmanager`](https://developer.android.com/tools/sdkmanager) 工具。

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
tools_tmp="$(mktemp -d)"
curl -fL https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
  -o "$tools_tmp/commandlinetools.zip"
unzip -q "$tools_tmp/commandlinetools.zip" -d "$tools_tmp"
mv "$tools_tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
rm -rf "$tools_tmp"

if [ -n "${ZSH_VERSION:-}" ]; then
  shell_rc="$HOME/.zshrc"
else
  shell_rc="$HOME/.bashrc"
fi
cat >> "$shell_rc" <<'EOF'
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF
source "$shell_rc"

JAVA17_BIN="$(find /usr/lib/jvm -path '*/java-17-*/bin/java' -print -quit)"
if [ -z "$JAVA17_BIN" ]; then echo "找不到 JDK 17，请安装 openjdk-17-jdk" >&2; exit 1; fi
JAVA17_HOME="${JAVA17_BIN%/bin/java}"
printf 'export JAVA_HOME="%s"\nexport PATH="$JAVA_HOME/bin:$PATH"\n' "$JAVA17_HOME" >> "$shell_rc"
source "$shell_rc"

sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
java -version
gh --version
sdkmanager --list_installed
```

`sdkmanager --licenses` 会逐项显示 Android SDK 许可；阅读并接受后再继续安装组件。

#### 登录并准备项目

使用有 `ryvengray/pixel-app` 写入权限的 GitHub 账号登录。终端会显示一次性验证码，按提示在浏览器完成授权：

```bash
gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git
gh auth status
gh repo clone ryvengray/pixel-app
cd pixel-app
```

将旧电脑备份的**同一份** `.signing/` 目录复制到项目根目录；不要重新运行 `New-ReleaseKey.sh`，否则会生成无法更新现有正式版的新密钥。`app/google-services.json` 也需要从旧电脑安全复制，或从 Firebase Console 下载到 `app/`，供这台电脑本地构建 `prodRelease` 使用。两个文件都被 Git 忽略，不要提交。

```bash
chmod 700 .signing
chmod 600 .signing/*
```

GitHub Actions 的仓库 Secrets 已经存在时，无需再次运行 `Setup-GitHubSigningSecrets.sh`。仅当仓库 Secrets 尚未配置或签名密钥确实更换时，才需要重新配置；更换密钥会导致已安装正式版无法通过普通更新安装。

#### 发布
```

发布时不传版本号，脚本会显示当前版本，并提供“主版本升级”“功能更新”“修复更新”三项选择；也可直接传入确定的版本号以跳过菜单。随后脚本自动递增 `VERSION_CODE`、构建及校验已签名 APK、提交版本修改、推送分支和 `v*` 标签：

```bash
bash scripts/release-ubuntu.sh
# 或直接指定版本号
bash scripts/release-ubuntu.sh 1.2.0
```

标签推送会触发 GitHub Actions 创建 GitHub Release，Obtainium 继续使用同一仓库链接追踪更新。脚本会拒绝脏工作区、已存在标签、缺失 SDK 或签名文件，避免发布不可安装的更新。

Gitee 不运行 GitHub Actions；若只保留 Gitee，可先用本地构建脚本生成签名 APK，再上传到 Gitee 的 Release，但 Obtainium 需要另行验证并配置通用 HTML 来源。

## 失败处理与测试

App 只有收到 Termux 成功退出结果才显示成功；结果通过 PendingIntent 返回并保存在 App 本地，重新打开仍可查看。日志在结束后显示，不是实时输出。超过 10 分钟无回调会显示“结果未知”，这不表示任务已取消；请在 Termux 检查后重试，仓库文件锁防止并发执行。网络 pull/push 各限时 180 秒。Android 强制结束 Termux、网络中断、Git hooks 或凭据助手卡住仍可能导致任务无法完成。

同步锁位于 Termux 私有目录 `~/.cache/gray/obsidian-sync.lock`，避免 Android 共享存储不支持 flock 的问题。

冲突后在 Termux 运行 `git status`，手动解决冲突并完成合并提交，再点击同步。App 不会自动丢弃笔记或撤销提交。

在提供 Git、Bash、flock、timeout 的 Linux/Termux 环境运行 `bash tests/sync-test.sh`，验证提交推送、无变化、分叉合并和冲突停止。测试仅使用临时仓库。

当前验证：Windows Git Bash 下通过以上 Git 流程测试（使用 `MOCK_FLOCK=1` 模拟缺失的 flock，未验证真实文件锁）。`1.0.0` Release APK 已通过本地构建及签名检查，尚未进行 Pixel 真机验证。

接口参考：[Termux RUN_COMMAND 官方文档](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)。
