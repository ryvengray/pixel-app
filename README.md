# Gray

## 宠物首页

首页是全屏动态背景和实时 3D 玉绿色圆润三角形宠物。宠物会呼吸、漂浮、眨眼、轻微扭动，轻触时弹跳并变换嘴型。右上角小圆钮或长按宠物可展开底部菜单，进入 Obsidian 同步、选择跟随系统/浅色/深色主题、开启减少动态效果。主题偏好会保留，离开首页时暂停渲染。系统栏默认隐藏，从屏幕边缘滑动可临时唤出。

文字聊天尚未接入；当前点击宠物的回应是本地互动，不是模型回复。

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

## 构建安装

使用 Android Studio 打开此目录，安装 Android SDK 35，使用 JDK 17 和 Gradle 8.11.1。可通过 Android Studio 的 Build APK(s) 构建；或安装 Gradle 后运行：

```powershell
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。正式更新请使用下方固定签名的 Release APK。

## 正式打包与 Obtainium 更新

应用包名固定为 `dev.pixel.sync`。正式版使用同一个签名密钥，更新时必须保留该密钥。密钥和密码存放于 `.signing/`，已经被 Git 忽略；请将整个目录安全备份，勿提交到公开仓库。已安装 debug 版本时，首次切换 release 签名需要卸载 debug App 后重新安装（Obsidian 文件仍在共享目录）。

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

Gitee 不运行 GitHub Actions；若只保留 Gitee，可先用本地构建脚本生成签名 APK，再上传到 Gitee 的 Release，但 Obtainium 需要另行验证并配置通用 HTML 来源。

## 失败处理与测试

App 只有收到 Termux 成功退出结果才显示成功；结果通过 PendingIntent 返回并保存在 App 本地，重新打开仍可查看。日志在结束后显示，不是实时输出。超过 10 分钟无回调会显示“结果未知”，这不表示任务已取消；请在 Termux 检查后重试，仓库文件锁防止并发执行。网络 pull/push 各限时 180 秒。Android 强制结束 Termux、网络中断、Git hooks 或凭据助手卡住仍可能导致任务无法完成。

同步锁位于 Termux 私有目录 `~/.cache/gray/obsidian-sync.lock`，避免 Android 共享存储不支持 flock 的问题。

冲突后在 Termux 运行 `git status`，手动解决冲突并完成合并提交，再点击同步。App 不会自动丢弃笔记或撤销提交。

在提供 Git、Bash、flock、timeout 的 Linux/Termux 环境运行 `bash tests/sync-test.sh`，验证提交推送、无变化、分叉合并和冲突停止。测试仅使用临时仓库。

当前验证：Windows Git Bash 下通过以上 Git 流程测试（使用 `MOCK_FLOCK=1` 模拟缺失的 flock，未验证真实文件锁）。`1.0.0` Release APK 已通过本地构建及签名检查，尚未进行 Pixel 真机验证。

接口参考：[Termux RUN_COMMAND 官方文档](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)。
