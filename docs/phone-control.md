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
复用现有 ink-screen 的六位 TOTP 验证码登录和 HttpOnly 会话，登录长期保持（365天会话，每次打开页面自动续期）；不需要把管理员令牌粘贴到网页。
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


## 手机画面、点击与滑动

`/esp32-screen` 现在显示手机截图，画面约每秒更新。在电脑页面勾选「允许点击与滑动」后，
点击预览可点按手机，按住拖动可滑动，也可使用「向上滑动」「向下滑动」按钮。
点击/滑动由 Ubuntu 无线 ADB 执行；原有文字输入仍由 ESP32 蓝牙键盘执行。
ADB 预览控制独立于 ESP32 的 BOOT 开关；蓝牙文字输入仍需板子 ARMED。

Ubuntu 宿主机运行截图桥接进程（服务容器不需要内置 ADB）：

```bash
python3 server/phone_bridge.py --env-file server/.env bridge \
  --serial adb-48121FDAP00574-fqmAsw._adb-tls-connect._tcp \
  --adb /home/leigang/Android/Sdk/platform-tools/adb --interval 1
```

进程从 dotenv 只读取管理员令牌，不输出令牌。部署的用户服务名为 `pixel-phone-bridge.service`，
可使用 `systemctl --user status pixel-phone-bridge`、`journalctl --user -u pixel-phone-bridge -n 30` 检查。
用户服务按现有 Linux 用户会话生命周期运行；系统启动后无人登录时是否启动取决于用户的 linger 设置。

页面每隔一段时间向 `POST /v1/phone-control/screen/watch` 续租，租期15秒；
没有可见页面继续续租时，桥接进程停止抓图，但保留心跳。
`POST /bridge/poll` 仅接受管理员 Bearer 令牌，浏览器 Cookie 不能冒充执行进程。
`POST /touch` 接收 `{id,version,action}`，action为tap或swipe；`GET /touch/{id}` 查询结果。

网页按图片实际显示尺寸换算原始像素坐标，拖动时保持画面不变。
服务保留近期帧元信息，只接受5秒内且与当前画面尺寸一致的操作；执行前再截图核对尺寸，
避免手机旋转后按旧坐标点击。受保护内容可能无法截图，显示的是截图预览而非视频流。
操作单次下发，超时或进程重启不自动重放，网页可查询原操作ID。
输入成功仅代表ADB完成指令，实际应用状态仍需通过更新后的手机画面确认。


## 实时视频与持续触控

调试页已切换为 scrcpy 3.3.4 硬件H.264 → WebRTC，目标30 FPS、长边1280、2Mbps；
服务器直接打包转发编码视频，不做逐帧JPEG上传，也不重新编码。页面显示实际解码帧率和控制通道RTT；
RTT不是端到端视频延迟，静止画面的帧率可能低于目标值。

按下、移动、松开通过RTC DataChannel持续传输，不再等待拖动结束后提交一条ADB swipe。
移动事件上限约30次/秒，网络积压时丢弃多余移动事件，断线、取消、窗口失焦、旋转或暂停会释放触点。
严格检查视频尺寸、方向epoch、坐标、事件顺序；不重放已发送动作。
当前单个实时查看会话，新页面连接会接管旧会话；旧页面可手动重新连接。

独立网关使用 `server/realtime-requirements.txt`，只监听Ubuntu本机127.0.0.1:8788，
由主服务 `/v1/phone-control/realtime/offer` 和 `/close/{id}` 通过现有管理员登录代理信令。
浏览器不会获得网关令牌。视频/触控使用局域网WebRTC UDP直连，无外部STUN/TURN；
跨公网不属于当前配置，若连接失败请检查局域网UDP与无线ADB。

用户服务 `pixel-phone-realtime.service` 按需推送临时scrcpy服务并运行，关闭页面后清理手机端进程和ADB转发。
独立虚拟环境：`~/.local/share/pixel-realtime/venv`。
官方组件：https://github.com/Genymobile/scrcpy/releases/tag/v3.3.4
scrcpy-server-v3.3.4 SHA256：8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e。
该文件是临时ADB服务，不是安装到手机的常驻APK。固定协议实现参考官方doc/develop.md与ControlMessageReader。

原截图/触控接口及pixel-phone-bridge服务保留兼容，但实时页不再向screen/watch续租。
ESP32蓝牙键盘和BOOT控制保持原有方式。
测试：常规pytest覆盖信令权限、参数及二进制协议；网关虚拟环境中运行
`PYTHONPATH=server python tests/realtime_gateway_checks.py` 验证触点生命周期。

## 本地视觉定位（Ollama / Qwen3-VL）

完整启动、运行、监控、排障和测试步骤见 [本地视觉模型运维手册](local-vision-operations.md)。

在 Ubuntu 宿主机运行 `docker compose -f compose.vision.yaml up -d`，然后执行
`docker exec pixel-ollama ollama pull qwen3-vl:2b-instruct`。
模型数据存储在 `pixel-ollama` 卷，仅监听 `127.0.0.1:11434`；CPU 配额6核，单任务推理。
首次下载需要联网，下载完成后截图识别在本地运行。

宿主机视觉网关使用 Python 的 FastAPI、uvicorn、httpx、Pillow（依赖版本可参考
`server/requirements.txt`），服务模板为 `server/pixel-phone-vision.service`。
安装前按实际部署修改模板中的用户、仓库路径、ADB 路径和设备 serial。
复制到 `~/.config/systemd/user/` 后执行：

```bash
systemctl --user daemon-reload
systemctl --user enable --now pixel-phone-vision
```

网关只监听 `127.0.0.1:8789`，读取现有管理员令牌；通过主服务管理员认证代理：

- `GET /v1/phone-control/vision/status`：模型下载和忙碌状态。
- `POST /v1/phone-control/vision/locate`：`{"target":"屏幕右上角的设置按钮"}`。

`/esp32-screen` 的「识别手机目标」可输入目标，查看截图、标注框及原始屏幕中心坐标。
每次请求通过无线 ADB 抓取新截图，长边缩至1280像素送入模型；模型按0..1000归一化
坐标返回边界，网关校验范围和非空边界后转换到原始手机尺寸。
响应包含 `captured_at`、`version`、`width`、`height`、`bounds`、`point`、
`elapsed_seconds`、`image_base64` 和 `input_executed:false`。
目标缺失时返回 `found:false`。非法定位结果被拒绝，不会夹取越界值后执行。
坐标只代表截图时刻，页面变化或旋转后必须重新识别；不保证模型识别准确。

定位接口本身不发送输入。识别后可通过下述显式蓝牙点击接口执行一次点击。
网关识别超时为240秒；CPU实际耗时取决于图片和目标，应以现场实测为准。

检查：`journalctl --user -u pixel-phone-vision -n 30`、`docker logs --tail 30 pixel-ollama`。
测试：`python -m pytest tests/test_phone_vision.py tests/test_phone_control.py tests/test_phone_screen.py`。

2026-11-09 现场验证（i7-1260P / 32GB / CPU配额6核）：

- 模型：`qwen3-vl:2b-instruct`（Ollama ID `afc393e3572c`），镜像配置 SHA256
  `8d0356014a54ff18edb6c99e8ae18422790ffff537848e0bc07f5a04a3fd33d7`。
- Pixel原始画面960×2142；“左上角返回箭头”得到框 `[42,176,84,231]`、
  中心 `(63,203)`，与采集截图的位置核对一致，首次请求32.52秒。
- 不存在的“香蕉支付”按钮返回 `found:false`、`point:null`，热运行28.40秒。
- 首次推理时容器约2.8GiB内存、CPU约600%；本次测试不足以代表所有页面准确率或耗时。
- 27项相关自动测试通过；识别请求均为 `input_executed:false`，未执行任何手机点击。
- 服务器已启用 `Linger=yes`，用户视觉网关和Docker模型容器均配置自动启动。


## 识别后的蓝牙点击

在 `/esp32-screen` 输入“点赞按钮”并识别，检查红色目标框后，点击新增的
「蓝牙点击识别目标」。需要 ESP32 在线、蓝牙已连接且 BOOT 处于 ARMED；
无需更新0.1.1固件或重新配对。结果返回后90秒内可提交点击，截图总有效期不超过180秒。

执行路径是 ESP32 BLE HID 鼠标；无线ADB仅负责截图、读取前台窗口和光标图层，
没有通过ADB注入点击。服务读取 `dumpsys SurfaceFlinger` 中唯一的 `[BBQ] Sprite` 图层
位置，并通过 `dumpsys input` 确认普通箭头光标（resolvedPointerType 1000）。
首次操作自动向左上角移动，确定光标热点偏移；随后逐次发送相对位移，读取反馈修正，
直到热点距离目标中心不超过3像素，才发送一次BLE左键点击。

会检查ESP32启动ID和就绪状态、其他未结束键鼠命令、屏幕尺寸/方向、前台窗口、
目标区域图像差异及定位有效期；不支持的光标/方向或无法得到唯一反馈时停止点击。
当前实测适用于Pixel竖屏和普通箭头光标，其他Android版本的图层格式可能不同。
目标区域比较在鼠标移入前进行；这些检查不能保证应用业务完成，点击后应查看新截图。

新增的管理员接口：

- `POST /v1/phone-control/vision/click`：`{"id":"32位十六进制任务ID","result_id":"定位返回的result_id"}`。
- `GET /v1/phone-control/vision/click/{id}`：查询同一任务的进度及 `running/succeeded/failed/unknown`。

坐标只从服务器保存的定位结果读取，不接受客户端另传坐标。同一定位只能提交一次任务，
同一任务ID重复提交只查询现有状态。状态保存在宿主机
`~/.local/share/pixel-vision/clicks.db`，重启后未完成任务标为unknown，不自动重放。
蓝牙报告成功不等于已点赞，响应以 `report_sent:true`、`business_confirmed:false` 表明这个区别。
网页断线时查询原ID，不另建点击；任务结束后需重新识别才能再次点击。

相关测试：`python -m pytest tests/test_phone_bluetooth.py tests/test_phone_vision.py`
及 `node tests/test_phone_vision_ui.cjs`，覆盖反馈收敛、过期、窗口变化、未知回执、
任务去重、重启恢复和页面双击/丢失响应查询。


2026-11-09 蓝牙点击现场验证：点赞目标 `(883,1829)`，点击前读取的真实光标热点
`(881.97,1828.25)`，与目标相距约1.3像素。Pixel-Control Mouse 的输入设备记录到
一次 `EV_KEY BTN_MOUSE DOWN/UP`；点击后截图中白色爱心变为橙红色。
该实测确认本次点赞生效，不代表任意页面都能准确识别或点击。
35项相关Python测试及页面交互测试通过。
