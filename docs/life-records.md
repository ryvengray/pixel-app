# 生活记录：日记与收支

## 使用

在首页对 Greta 说「记一下我今天加了油 400 块」「帮我记一笔午饭 28 元」或「记日记今天和朋友散步很开心」。助理提取内容、日期和零到多笔收支，手机校验并写入 SQLite 后才显示「已记录」并播放现有宠物回应动画。不需要再次确认。金额不明时助理应追问，不猜金额。没有服务连接时仍可在菜单 → 生活记录 → 写一条手动记录。

菜单 → 生活记录可查看、修改日期/文字/各笔金额、长按删除并在提示框中立即撤销。默认从底部打开，沿用工具的全屏设置。金额支持 CNY/USD/EUR/JPY/HKD/GBP，不做汇率换算；JPY 只接受整数。没有金额的记录就是日记。

## 首次连接 Obsidian

1. 先按现有说明在手机配置 Termux、Git 和同步脚本需要的认证，现有固定仓库为 `Documents/obsidian`。
2. 生活记录 → Obsidian 设置 → 选择仓库目录，在 Android 系统目录选择器中授权 **Documents/obsidian 本身**。仅支持主存储上的这个路径，与已有 Termux 脚本一致；不能只选父目录或另一个云盘。
3. 在「同步 / 授权」中完成一次 Termux 运行命令授权。此后新增、修改和删除记录可后台同步，不再弹抽屉或倒计时。已有仓库配置不等于 App 已拥有文件权限，因此仍需要这一次目录授权。
4. App 和服务端需一起更新。已部署服务器在仓库根目录按原部署方式重新构建 `greta-service`；使用 BGE 时也重建 `pixel-decision` 以载入新增注册。普通部署：`docker compose up -d --build greta-service`；BGE 部署：`docker compose -f compose.yaml -f compose.decision.yaml up -d --build greta-service pixel-decision`。不更换地址或配对令牌。

## 保存与同步时机

- SQLite `greta.db` 的 `life_record` 表是唯一主数据源，数据库从版本 3 升到 4；保留原有聊天、记忆和话题。
- Agent 的 `create_life_record` 只返回待执行参数，服务端不保存生活记录表、不宣称本机已保存。Android 校验工具名、请求 ID、显式操作意图、日期和金额后落库。聊天文字仍会经过已有 LLM/网关，已有聊天缓存不因此消失。
- 请求 ID 是记录唯一键，同一次消息的网络重试/进程重启重试不会重复记账，删除后重试也不会恢复记录。原话、内容、手机时区、日期、多笔收入/支出随记录保存。金额用十进制字符串保存，校验时精确转换最小货币单位，避免浮点误差。
- 配好目录后安排持久化导出任务，最早约 1.5 秒执行，连续变化合并。导出不要求联网；Git 同步单独安排，最早约 10 秒、要求网络可用。Android 的后台调度和省电策略可能延迟执行，这不是精确计时器。
- 每条记录生成一个稳定文件：`Documents/obsidian/Greta 生活记录/<记录 ID 的 SHA-256 前 32 位>.md`。修改日期也不换路径，不追加重复段落，不修改用户的每日笔记。修改写回同一文件；删除写入删除标记，保留手机墓碑防止重试复活。
- 导出、手动同步和自动同步共用同步锁与 running 状态。已有 Git 任务执行时不改仓库文件，后续任务退避等待。成功回调只确认该次启动前已导出的版本，新记录/修改仍会补同步。跨任务回调和重复回调被忽略。
- 文件先写入、读回校验，再标记已导出；Termux 成功回调才标记已同步。断网保留待同步，临时错误指数退避。权限被撤销/Termux 未安装需用户处理，重新授权或打开记录页会重新安排任务。
- 手动同步会先导出待处理记录；然后沿用原 `sync.sh` 的 commit → pull → push，会提交仓库内所有更改。脚本冲突和认证错误沿用现有停止逻辑，不自动解决 Git 冲突。

Obsidian 是导出副本，本版没有双向导入。直接修改导出文件不会修改手机记录；以后 App 更新该文件时会比较上次导出摘要，检测到外部修改就暂停覆盖并显示原因。处理方式：先将 Obsidian 中修改过的文件重命名/移到其他目录保留，再回到 App 手动同步。由 App 生成的文件才能重建，SQLite 原记录保留。文件写入中断也可能需要这样保留残留文件后重试。

「断开自动同步」会取消排队任务，保留手机记录和已导出文件；已经启动的 Termux 任务继续按原流程结束。没有目录授权时所有记录仍可在手机使用，之后连接会补导出。数据库随 App 卸载清除，当前没有另行云端 SQLite 备份或跨手机合并。

## 实现入口

- `LifeRecords.java`：表结构、参数校验、幂等保存、修改/删除、版本与 Markdown。
- `AssistantApi.java`：共享工具能力、LLM 回应中的本机记录提交；提交后才生成成功回复。
- `RecordsActivity.java`：记录本、编辑器和 Android 目录授权。
- `VaultExport.java`：SAF 文件读写、稳定文件名、摘要冲突保护。
- `LifeSync.java`：持久化 JobScheduler 任务、网络约束、合并与退避。
- `SyncRunner.java` / `ResultReceiver.java`：复用 Termux 命令、同步互斥与版本回执。
- `server/app/client_assets/tools.json`：网关与手机共用工具参数；`decision_tools.json` 注册 REQUIRED 的保存工具和 NONE 的记录本入口。

## 验证

Python 回归（不下载 BGE，不调用真实模型）：

```sh
PYTHONPATH=server python -m unittest discover -s tests -p 'test_*.py'
```

开发版及 instrumentation：

```sh
./gradlew :app:assembleDevDebug :app:assembleDevDebugAndroidTest --offline
adb -s emulator-5554 install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e suite records dev.pixel.sync.dev.test/dev.pixel.sync.ToolUiInstrumentation
adb -s emulator-5554 shell am instrument -w -e suite sync dev.pixel.sync.dev.test/dev.pixel.sync.ToolUiInstrumentation
adb -s emulator-5554 shell am instrument -w -e suite home dev.pixel.sync.dev.test/dev.pixel.sync.ToolUiInstrumentation
```

使用独立模拟器。记录测试使用独立 SQLite 和测试 APK 的缓存目录内容提供器，HTTP 测试用本机假服务，无真实模型/仓库/Git 推送。同步测试要求没有安装 Termux。真实 Termux Git 联调需在已配置设备上另行验证。
