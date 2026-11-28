# Greta

## 仓库目录

Android 应用、Pixel 后端和硬件项目统一在此仓库维护：

| 目录 | 内容 |
| --- | --- |
| `app/` | Android 应用 |
| `server/` | Pixel 后端、手机控制与视觉网关 |
| [ink-screen/pixel-display-e1002/](ink-screen/pixel-display-e1002/README.md) | E1002 墨水屏固件、API、MQTT 与 OTA 发布工具 |
| [ink-screen/esp32-screen/](ink-screen/esp32-screen/README.md) | ESP32 Gravity AHT20 温湿度采集固件与配置工具 |

原 `ink-screen` 仓库的代码及提交历史已合入 `ink-screen/`，后续在此提交修改。
迁移说明、构建和测试入口见 [硬件项目说明](ink-screen/README.md)。

## 宠物首页

首页是全屏动态背景和实时 3D 玉绿色圆润三角形宠物。宠物会呼吸、漂浮、眨眼、轻微扭动，轻触时弹跳并变换嘴型。右上角小圆钮或长按宠物可展开底部菜单，进入 Obsidian 同步、选择跟随系统/浅色/深色主题、开启减少动态效果。主题偏好会保留，离开首页时暂停渲染。顶部透明状态栏常驻显示，底部导航栏可从屏幕边缘滑动唤出。

首页可直接和 Greta 聊天：消息集中在上半区，你的气泡显示最新一组连续发言。双方新发言分别替换自己的旧气泡，完整内容仍保存在右上角“历史”。宠物独立位于下半区居中，左右预留展示内容的空间；键盘出现时，消息区与宠物舞台按可用高度调整，保持上下分离。底部横线输入区左侧点击打字，右侧短按显示提示、长按开始语音转文字；深色录音界面支持文字与波形反馈，滑向左上取消、右上编辑，中央松开等待最终转写后发送文本。语音依赖设备已安装的系统识别服务，并需麦克风权限。

工具结果与操作提示通过右上方“动态 · 查看”展开；提醒支持完成、延后 15 分钟，也可在对话中修改或取消。首页偶尔按已有上下文主动开口，设置中可关闭；默认每天至多两次、间隔四小时，23:00–08:00 保持安静。提醒 inbox 在前台同步，服务端用持久投递队列重试推送。消息重试使用稳定请求 ID 和工具检查点，避免回复失败后重复执行。已配对服务端的请求失败会保留为待重试，不自动改走直连；未配对时仍可使用已配置的本地密钥聊天。完整对话和长期记忆以 Pixel 本地数据库为主，服务端仅保留后台任务所需的同步记忆副本。可在”我的长期记忆”中查看、修正、手动添加或删除记忆。

从相册、文件管理器或其他应用打开/分享单张图片到 Greta，默认进入生活记录编辑页，可补充文字、调整日期后保存，也可切换到打印，返回保留草稿。图片随记录保存在本机，配好 Obsidian 后一并导出；打印会准备方向正确、透明底铺白的兼容副本，记录保留原图。

## 私人助理服务

服务端代码在 [`server/`](server/README.md)，与 Android App 保持在同一个项目中。它负责调用 DeepSeek、执行提醒和保存待推送消息。将 `server/.env.example` 复制为 `server/.env`，填入带 `/gray` context 的 frp 公网 HTTPS 地址和 `DEEPSEEK_API_KEY` 后，在项目根目录运行 `docker compose up -d --build`；`.env`、服务端数据库和 Firebase 服务账户文件均被 Git 忽略。完整的 Docker、frp 和 Nginx 配置见 [`server/DEPLOYMENT.md`](server/DEPLOYMENT.md)。手机通过一次性二维码配对，不需要输入长期访问令牌。

App 的“助理设置”包含两种连接方式：

- 正常情况：调用你本地电脑上的私有服务。手机不保留服务端 DeepSeek Key。
- 服务端不可用时：可选择配置个人 DeepSeek Key 作为手机直连兜底。该 Key 与服务访问令牌都由 Android Keystore 加密保存，不写入 APK、数据库或 Git。

没有网络时，App 仍可查看、编辑记忆和历史，但无法获得模型回复；服务端恢复后才能继续后台提醒和主动通知。Android 端已接入 Firebase Cloud Messaging：扫码配对后会自动注册设备令牌，服务端使用 Firebase 服务账户发送系统推送。Android 13 及以上首次配对时会请求通知权限。

适用于 Pixel 9 Pro 的原生 Android App（Android 12 及以上）。打开同步工具后倒计时 3 秒自动开始，可在倒计时内取消或点击“立即同步”跳过等待；同步期间按钮禁用并显示加载状态。通过 Termux 执行固定目录 `~/storage/shared/Documents/obsidian` 的 Git 同步。

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
| `devDebug` | 本机开发、调试 | Greta Dev | `dev.pixel.sync.dev` | 关闭 |
| `prodDebug` | 调试正式配置 | Greta | `dev.pixel.sync` | 使用正式 Firebase 配置 |
| `prodRelease` | 正式发布 | Greta | `dev.pixel.sync` | 使用正式 Firebase 配置 |

日常开发请运行 `devDebug`。它和正式版包名不同，可以同时安装；聊天记录、应用设置和加密密钥也分别保存在各自应用空间。开发版没有 Firebase 注册配置，因此不会注册或接收正式推送。

### 在 Android Studio 中运行开发版

1. 在 Android Studio 选择 **File > Open**，打开本项目根目录（包含 `settings.gradle` 的目录）。
2. 等待 Gradle Sync 完成。项目使用 Android SDK 35、JDK 17 和 Gradle 8.11.1；如果提示缺少 SDK 或 JDK，请在 SDK Manager 安装 SDK Platform 35，并在 **Settings > Build, Execution, Deployment > Build Tools > Gradle** 将 Gradle JDK 设为 JDK 17。
3. 打开 **View > Tool Windows > Build Variants**。
4. 在 `app` 模块的 **Active Build Variant** 下拉框中选择 **`devDebug`**。
5. 连接 Android 手机并开启 USB 调试，或启动 Android 虚拟设备；在工具栏设备列表中选择目标设备。
6. 选择运行配置 **app**，点击工具栏的绿色 **Run ▶** 按钮。首次安装显示为 **Greta Dev**。

修改应用后再次点击 **Run**，Android Studio 只会更新开发版，不会覆盖已安装的正式版。卸载开发版也不会删除正式版的应用数据。

### 在 Ubuntu 终端无线安装开发版

手机和电脑连接同一网络，在手机的“开发者选项 → 无线调试”中查看 IP 和端口，然后运行：

```bash
bash scripts/install-debug-wireless-ubuntu.sh
```

脚本会引导首次无线配对，随后构建、覆盖安装并启动 `devDebug`。连接成功后会将设备地址和连接端口保存在本机的 `.greta-wireless-device` 中，后续直接运行脚本即可；端口失效时脚本才会重新询问。手机弹窗中的配对端口和无线调试主页中的连接端口是两个不同的端口。配对码与配对端口不会保存。

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

### GitHub Releases（源码放 Gitee + 本地打包分发）

源码保留在当前私有 Gitee 仓库；公开的 `ryvengray/pixel-app-releases` 仓库仅保存说明文件和 Release 附件（不包含源码）。Obtainium 中添加 `https://github.com/ryvengray/pixel-app-releases` 即可跟踪正式版更新。

#### 首次准备

1. 安装 GitHub CLI（macOS 可执行 `brew install gh`），并登录：

   ```bash
   gh auth login
   ```

2. 准备好本地构建凭据（均已被 `.gitignore` 忽略，不要提交到仓库）：
   - 将备份的 `.signing/release.jks` 与 `.signing/signing.properties` 放到项目根目录（若首次创建新签名可执行 `python3 scripts/release.py init-signing`）。
   - 将 `google-services.json` 放到 `app/google-services.json`（构建 `prodRelease` 必需）。
   - 配置好 JDK 17 与 Android SDK 35（设置 `ANDROID_HOME` 或在 `local.properties` 配置 `sdk.dir`）。

3. 如果公开分发仓库尚未创建，首次发布时加上 `--create-repo`（仅写入 [distribution/README.md](distribution/README.md)，绝不推送源码历史）：

   ```bash
   python3 scripts/release.py publish --create-repo
   ```

#### 日常一键发布

在 macOS Finder 中双击 [publish.command](publish.command)，或在项目根目录执行：

```bash
python3 scripts/release.py publish
```

脚本会自动完成：

1. 将 `app/version.properties` 的 `VERSION_NAME` 末位加一、`VERSION_CODE` 加一。
2. 运行 `bash tests/sync-test.sh`，本地执行 `./gradlew --no-daemon assembleProdRelease` 并用 `apksigner` 校验签名，输出 `dist/gray-<版本>.apk` 与 `dist/SHA256SUMS`。
3. 在 `ryvengray/pixel-app-releases` 创建草稿 Release，上传 APK 与校验文件后公开。
4. 在当前源码仓库仅提交 `app/version.properties`（提交信息 `Release Android v<版本>`），并推送到当前分支配置的上游分支（例如 Gitee 的 `origin/main`）。

若中途失败，脚本会保留 `release-state.json`，重新运行同一命令即可从断点继续，不会重复递增版本号。

只构建不发布可执行：

```bash
python3 scripts/release.py build
```

验证发布脚本逻辑（不调用 GitHub 或 Gradle）：

```bash
python3 -m unittest tests/test_release.py -v
```

## 失败处理与测试

App 只有收到 Termux 成功退出结果才显示成功；结果通过 PendingIntent 返回并保存在 App 本地，重新打开仍可查看。日志在结束后显示，不是实时输出。超过 10 分钟无回调会显示“结果未知”，这不表示任务已取消；请在 Termux 检查后重试，仓库文件锁防止并发执行。网络 pull/push 各限时 180 秒。Android 强制结束 Termux、网络中断、Git hooks 或凭据助手卡住仍可能导致任务无法完成。

同步锁位于 Termux 私有目录 `~/.cache/greta/obsidian-sync.lock`，避免 Android 共享存储不支持 flock 的问题。

冲突后在 Termux 运行 `git status`，手动解决冲突并完成合并提交，再点击同步。App 不会自动丢弃笔记或撤销提交。

在提供 Git、Bash、flock、timeout 的 Linux/Termux 环境运行 `bash tests/sync-test.sh`，验证提交推送、无变化、分叉合并和冲突停止。测试仅使用临时仓库。

当前验证：Windows Git Bash 下通过以上 Git 流程测试（使用 `MOCK_FLOCK=1` 模拟缺失的 flock，未验证真实文件锁）。`1.0.0` Release APK 已通过本地构建及签名检查，尚未进行 Pixel 真机验证。

接口参考：[Termux RUN_COMMAND 官方文档](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)。


## 对话与陪伴架构

当前实现、陪伴 agent 的目标分层、记忆与主动跟进缺口、可靠性问题及实施顺序见 [Greta 陪伴 Harness 架构](docs/greta-companion-harness-architecture.md)。


重要话题已接入：聊天回复后由私有服务异步整理，在首页菜单“重要话题”查看原话、修正或忘记。默认只记住、不追问；可选一个时间仅在首页关心一次，或明确选择系统通知。离线修改有持久同步队列，待同步时不代表服务端已生效。实现与边界见 [重要话题说明](docs/greta-important-topics.md)。

### 可选 Decision Engine

CPU 工具路由与参数提取接入见 [Decision Engine 部署与测试](docs/decision-engine.md)。默认关闭，可通过 `compose.decision.yaml` 启用 BGE 路由容器，手机继续使用现有配对服务地址。

日记与记账已合并为「生活记录」：支持对助理说一句话自动本地保存，以及授权后的 Obsidian 后台导出/同步。首次配置和保存规则见 [生活记录说明](docs/life-records.md)。

## 个人晨报、技术动态与副业痛点周报

首页菜单 → **晨报与灵感订阅** 提供三项高信噪比预设（原「社会热点」「国际大事」预设已移除），也支持添加自定义主题或独立 RSS / X 来源：

- **个人晨报**：将手机日历中的近期普通日程/待办（严格排除大姨妈等隐私记录）、服务端提醒、重要话题、天气与室内温湿度整合为一份早报；距离上次外部技术汇总满 2～3 天且有高价值内容时一并附上。若当天没有任何待办提醒与技术精选，则推送一则经过考据的历史典故故事。
- **AI 与技术动态**：聚焦大模型实质突破、实用开源工具、Agent 工程实践与 Hacker News 高赞技术热帖。服务端在 08:00–22:00 定期巡检一手 RSS/Atom 标题与正文摘要，结合长期记忆过滤水文：遇到旗舰模型发布或现象级开源突破时实时单推（每日最多 2 条，夜间转入次日晨报），常规优质内容攒够 2～3 天汇总推送（同时开启个人晨报时自动并入晨报，不重复打扰）。
- **副业痛点周报**：巡检 Ask HN、Lobsters 与 Reddit 垂直实操版块（小微商家、跨境独立站、运维自动化、自托管与效率工具）中的真实吐槽与求助帖，严格剔除自夸软文与泛大众伪需求，每 **2～3 天**提炼推送一张精简灵感卡（包含 **目标人群 + 痛点原话 + 现有凑合方案 + 商业化思维** 及原帖链接），没有高含金量痛点时不凑数。

连接私人助理服务后，也可以在对话中管理订阅，例如：“开启每天早上 8 点的个人晨报”或“开启 AI 与技术动态”。页面与聊天共用服务端订阅状态。

锁屏通知展示去除全部格式的纯文本要点预览；应用内对话气泡、历史记录、通知抽屉、工具结果与详情页均自动渲染 Markdown 与内联 HTML 样式，避免裸露格式符号。

### 新闻源与 X 账号

在 **新闻订阅 → 新闻源与 X 账号** 中可订阅内置的 OpenAI 官方动态、Google AI 博客、Karpathy 博客，也可添加公网 HTTPS RSS 2.0 / Atom 地址。每个来源可分别选择每日时间、关闭订阅。来源订阅独立于主题搜索，不会自动归入某个主题；目前每个启用的来源每天单独整理一份简报，因此来源增多会增加总结次数。失败状态会显示在来源卡片上，失败后最多每小时重试。首次仅获取近 48 小时、具有有效发布时间的条目。

X 预设 `@elonmusk` 和 `@karpathy`，也支持添加其他公开账号的用户名。默认不连接 X，不产生 X API 调用；可以先保存关注账号，页面会显示“等待连接 X”。服务端可以选择以下一种方式：

- **官方 X API**：在 `server/.env` 配置 `X_BEARER_TOKEN`。接口按用量收费，需自行在 X 开发者平台配置权限、额度和消费上限。正常每账号每天请求一次最新的最多 10 条原创帖子，排除回复、转发，不分页，不保证覆盖全部动态。账号 ID 和当天获取结果会缓存，模型失败重试不会再次读取当天已缓存的帖子。帖子文本最多取 2000 字符，摘要不代表对作者观点的事实核实。
- **已有的 RSS 转换服务**：配置 `X_RSS_BASE_URL=https://你的转换服务/twitter/user`，程序请求 `<base>/<username>`，要求返回 RSS/Atom。此方式优先于官方 API，读取转换后的标题。项目不提供公共转换服务，不保证第三方转换服务的可用性，也不自动购买服务。

凭据只放在服务端，不会返回手机。新增来源和 X 配置需要重新部署服务端，并更新 App。自定义 RSS 和 X RSS 转换服务目前使用直接公网 HTTPS 连接（不走服务端的 HTTP 代理），会检查解析地址并固定目标 IP、检查重定向，阻止访问内网地址；需确保服务端能直接访问这些来源。Google News 和官方 X API 沿用服务端代理设置。

### 从手机 X 通知生成摘要（无需 X API）

入口：**新闻订阅 → 新闻源与 X 账号 → 从手机 X 通知生成摘要**。

1. 开启收集并阅读数据使用说明，前往系统授予 Greta 通知读取权限。
2. 在 X 中关注目标账号并开启该账号的发帖通知，确保手机实际能收到通知。
3. 在 Greta 中填写 `用户名|通知显示名称`，默认预填 `elonmusk|Elon Musk`、`karpathy|Andrej Karpathy`。显示名称需要与通知标题一致；设置每日 08–22 点的摘要时间并保存。
4. 将 App 配对至更新后的私人服务。符合条件的通知会先保存在本机，通过后台任务上传；每日最多 50 条待处理通知合成一份摘要，生成后进入收件箱并按现有推送机制通知来源设备。晚于当天摘要到达的通知留待次日处理；后台上传受 Android 网络与调度影响，不能保证整点送达。

通知监听默认关闭，不读取历史通知，不会调用 X API 或自动打开 X 获取全文。Android 提供的是覆盖应用通知的读取权限，代码在处理内容前限定包名 `com.twitter.android`，并只接受作者匹配且带公开发帖信号的通知。没有社交分类的通知需要明确的发帖频道或发帖标题。私信类别、带消息历史或回复输入框、疑似私信和汇总通知会被跳过。设置页显示系统监听状态、最近收到 X 通知、最近匹配发帖及最近同步时间。X 版本、语言或通知格式不同可能导致通知被跳过；此功能不是完整的账号动态订阅，需在真机上验证 X 的通知格式。原始文字可能被系统截断；摘要始终说明仅依据通知片段，不能视为全文或事实核实。

已接受的作者用户名、通知文字（最多 2000 字符）及通知时间会发送给已配对服务，再交给 DeepSeek 总结；不上传其他通知、图片、通知操作或 X 登录凭据。通知文字及其模型摘要仍按外部资料处理，不执行其中指令。通知更新按作者和文字去重；本机最多保留 500 条、7 天，服务端原始通知最多保留 7 天。可以查看本机最近收集的 30 条通知、清除原始通知，已发布摘要仍保留在聊天收件箱中。

关闭功能会立即停收并清除本机原始通知，联网同步后停用服务端摘要并清除服务端原始通知；离线关闭不能立即撤销已在服务端排队的处理。系统撤销通知读取权限后，后台同步也会停用服务端摘要。开关与关注作者的版本检查会阻止旧上传或生成中的摘要覆盖新设置。此功能独立于官方 X API / RSS 账号订阅：若只使用手机通知方式，请关闭已有的官方 API 账号订阅，以免其他订阅继续读取付费接口。

验证通知筛选规则（无需手机或访问真实通知）：

```sh
javac -d /tmp/greta-x-rules app/src/main/java/dev/pixel/sync/XNotificationRules.java tests/java/dev/pixel/sync/XNotificationRulesTest.java
java -cp /tmp/greta-x-rules dev.pixel.sync.XNotificationRulesTest
```


### Greta 桌面快速对话微件

更新 Android App 后，长按桌面空白处 → 微件 → Greta →「Greta · 快速对话」，拖到桌面即可使用（默认 4×2，可调整大小）。

- 点击「说吧，我在听」直接打开 Greta 并开始语音对话。
- 点击「记一件事」或「添加提醒」开始语音输入，并把本次输入明确交给 Agent 记录或创建提醒。
- 说完点击「发送」，也可以取消或先编辑文字。提醒缺少时间等信息时，继续在对话中补充，以 Agent 的执行结果为准。
- 首次使用会请求麦克风权限；拒绝授权或系统无语音识别服务时改用文字输入。已有未完成输入或 Agent 正忙时保留当前对话，不覆盖草稿。
- 微件只在点击后打开前台录音，不在桌面后台持续监听。使用现有 Agent 服务与账户配置，无需额外配置微件。


### Greta 自定义快捷面板微件

长按桌面 → 微件 → Greta →「Greta · 快捷面板」。顶部可直接语音对话，六个快捷位置默认是语音记事、添加提醒、写一条、日历、新闻订阅、数学冒险岛。点击右上角「自定义」可分别更换为新增日程、生活记录、重要话题、长期记忆、历史记录、打印文件、Obsidian 同步、助理设置等入口。每个微件独立保存配置；取消配置不保存改动。

「写一条」直接打开生活记录编辑器，「新增日程」直接进入日历添加流程。打印与同步入口先打开对应页面，由用户选择文件或确认操作。原有较小的快速对话微件保留。

数学冒险岛已拆分至独立仓库：[ryven/math-adventure](https://gitee.com/ryven/math-adventure)。后端、家长网页、ESP32 固件及部署文档在该仓库维护；本项目保留访问已部署数学冒险岛网页的快捷入口。
