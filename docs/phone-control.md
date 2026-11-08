# Pixel / ESP32 手机控制桥接

本模块接入现有 FastAPI 服务，不另起业务后端。Ubuntu 通过无线 ADB 截图，ESP32 主动轮询指令并通过蓝牙 HID 操作手机。
当前完成截图接收、指令队列、执行回执、设备状态同步；尚未接入视觉模型自动规划、语音、中文输入或鼠标像素坐标校准。

## 配置及启动

在 `server/.env` 添加两项**不同的随机令牌**，分别用 `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'` 生成：

```dotenv
PHONE_CONTROL_ADMIN_TOKEN=<用于Ubuntu截图采集/操作控制，至少24字符>
PHONE_CONTROL_DEVICE_TOKEN=<用于ESP32轮询，至少24字符>
# 默认在 DATABASE_PATH 同目录生成 phone-control.db
# PHONE_CONTROL_DB_PATH=/data/phone-control.db
```

不配置则接口返回 503；设备令牌不能用于上传截图和排队动作。不要把管理员令牌配置到 ESP32。
运行 `docker compose up -d --build greta-service` 更新。现有 compose 使用 host 网络并监听 0.0.0.0:8787；
ESP32 使用 `http://Ubuntu局域网IP:8787`。若使用防火墙，按实际 LAN 网段允许访问 8787。
仅在可信局域网使用 HTTP，不向公网转发这一端口。现有服务其他接口和认证方式不变。

## Ubuntu 无线截图采集

ADB 在 Ubuntu **宿主机**安装和运行，无需把 USB/ADB 设备挂进业务容器：

```bash
sudo apt install adb python3-venv
python3 -m venv .venv-phone
.venv-phone/bin/pip install httpx==0.28.1
adb version
```

需要支持 `adb pair` 的版本。Pixel 开启开发者选项→无线调试，手机和 Ubuntu 在同一 Wi-Fi：

```bash
adb pair PHONE_IP:PAIRING_PORT
# 输入手机显示的配对码。配对端口与连接端口不同。
adb connect PHONE_IP:DEBUG_PORT
adb devices -l
```

设备状态必须为 `device`，不能为 offline / unauthorized。无线调试端口可能随重启变化。
ADB 配对授权属于调试通道，蓝牙配对属于输入通道，两者都要完成。

在宿主机设置 `PHONE_CONTROL_ADMIN_TOKEN` 环境变量，与服务中的值相同，然后运行：

```bash
.venv-phone/bin/python server/phone_bridge.py capture --serial PHONE_IP:DEBUG_PORT --interval 2
```

仅获取一帧使用 `--interval 0`。Ctrl-C 停止。脚本从 ADB 直接读取 PNG 字节，不保存到手机相册，
每次上传覆盖上一张。受保护窗口可能无法截图；手机需要保持在可交互状态。
该脚本是前台采集进程，关闭终端会停止；确认运行后可自行纳入现有 systemd 管理。

## ESP32 配置与联调

固件在独立仓库目录 `/Users/ryven/Projects/ink-screen/esp32-screen`。
烧录后用其中 `tools/configure.py` 写入 Wi-Fi、Ubuntu URL 和设备令牌。
Pixel 蓝牙配对 `Pixel-Control`，确认键盘布局为美式英语；按板子 BOOT 使其 ARMED。

```bash
.venv-phone/bin/python server/phone_bridge.py status
.venv-phone/bin/python server/phone_bridge.py command '{"type":"move","dx":20,"dy":0}'
.venv-phone/bin/python server/phone_bridge.py command '{"type":"click","button":1}'
# 先在空白测试输入框验证，换行会产生回车：
.venv-phone/bin/python server/phone_bridge.py command '{"type":"text","text":"hello"}'
.venv-phone/bin/python server/phone_bridge.py cancel
```

CLI 默认访问 `http://127.0.0.1:8787`，可在子命令之前用 `--server URL` 更改。
每条命令返回 ID，可通过 `GET /v1/phone-control/commands/{id}` 检查执行回执，或用 status 查看最近20条。
`--id` 可指定幂等 ID，同 ID 同动作重试不会重复排队，同 ID 不同动作返回409。

## HTTP 接口

全部前缀 `/v1/phone-control`。使用 `Authorization: Bearer TOKEN`：

| 方法/路径 | 权限 | 用途 |
|---|---|---|
| POST `/screenshots` | admin | multipart `file`，PNG/JPEG ≤4MiB、≤20MP；保存为去元数据JPEG |
| GET `/screenshots/latest` | admin | 最新JPEG；响应头有版本和接收时间，无缓存 |
| GET `/status` | admin | ESP32心跳、状态日志、截图元信息、最近命令 |
| POST `/commands` | admin | `{id, action, ttl_seconds:15}`，TTL最大60秒 |
| GET `/commands/{id}` | admin | 单条动作状态和结果 |
| POST `/commands/cancel` | admin | 取消尚未下发的队列，不撤回已下发动作 |
| POST `/device/poll` | device | ESP32状态、上次ACK；返回下一条动作或null |

状态字段/动作结构见固件 README。当前单服务绑定一块 ESP32 和一台 Pixel；多设备需要独立令牌映射后再扩展。
截图服务不会调用现有 DeepSeek 文本模型；未来视觉后端可以读取最新截图，明确检查时间/版本，
并通过 commands 接口下发已验证动作。

鼠标仅为**相对位移**，不是绝对屏幕坐标。AI 给出的 `(x,y)` 不能直接当作 dx/dy；需要建立鼠标位置反馈/校准。
中文文本目前返回422，不能据此宣称能够自动发送中文微信消息。

## 交付语义

心跳5秒内视为在线；离线、蓝牙未连接或未ARMED时拒绝新动作。暂停/蓝牙断开会取消未下发队列。
动作先持久化为 dispatched 再返回设备，最多同时一条在执行；重启不重放旧队列。
设备上报ACK后记录 succeeded/failed。已下发但超时为 unknown，未下发超时为 expired。
HTTP响应丢失会导致动作可能未执行，但**不自动重试键鼠副作用**；根据新截图决定是否创建新ID。
输入报告发送成功的回执只表示固件已发送，不表示 Android 应用已接受或目标业务已经完成。
SQLite保存最新一张截图和命令记录；生产使用时按需要制定历史命令清理周期。

## 验证

```bash
python -m pytest tests/test_phone_control.py
```

覆盖权限、未就绪拒绝、参数校验、幂等、并发单次下发、ACK、重启、暂停、超时和截图限制。


## ESP32 浏览器控制台

访问 `http://192.168.0.160:8787/esp32-screen`；反向代理前缀下也可以使用 `/gray/esp32-screen`。
复用现有 ink-screen 的六位 TOTP 验证码登录和 HttpOnly 会话，有效期一天；不需要把管理员令牌粘贴到网页。
管理员 Bearer API 和 ESP32 设备令牌仍然分别可用；设备令牌不能用于网页输入。

页面显示设备在线、蓝牙、ARMED/PAUSED、IP、固件、运行时间、内存、日志和最近指令，约每两秒更新。
请在电脑浏览器打开页面，在 Pixel 上选中目标输入框，并短按 BOOT 启用控制。
填写单行英文、数字或英文标点后点击「确认并输入」。页面不会追加回车或点击发送。
提交使用 `POST /v1/phone-control/type`，结构为 `{id, text}`，最大160字符、TTL8秒。
此入口拒绝中文、换行和制表符；通用底层 commands API 保留原有功能。

请求结果不明确时页面查询原指令ID，不自动重复创建；执行回执只证明固件发送结果，不代表应用业务完成。
页面为离线、未配对、暂停和非法文本禁用提交按钮，并显示执行失败原因。

当前现场联调地址为 ESP32 `192.168.0.210`、网关 `192.168.0.1`：原 `.106` 的MAC与板子不同，
换址后已验证连续心跳；建议在路由器中为板子MAC保留 `.210`，避免DHCP后续重分配。
固件0.1.1修复已配对重连时的本地通知标志，并检查通知API结果；已通过Android getevent验证数字1/2/3的DOWN/UP事件。
