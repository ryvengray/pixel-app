# Pixel Display v1 — E1002

首次操作请按 [一步一步操作手册](操作手册.md) 执行。烧录及修改代码后的更新步骤见 [固件更新说明](固件更新说明.md)。

此版本适用于 Seeed reTerminal E1002 7.3 英寸六色电子纸，包含 Ubuntu Docker Compose 服务端、MQTT、文本渲染、设备固件及 pixel-app 调用示例。当前版本为首次实机验证准备的原型：定时休眠版本已使用示例配置编译成功，尚未烧录或完成真实屏幕联调。

## 硬件与工作方式

官方资料：800×480、GDEP073E01 面板、ED2208 控制器，原生黑、白、红、黄、绿、蓝六色。官方示例使用 `GxEPD2_7C<GxEPD2_730c_GDEP073E01>`，SPI SCK 7、MOSI 9、CS 10、DC 11、RST 12、BUSY 13、2 MHz。本项目采用 [Seeed_GxEPD2](https://github.com/Seeed-Projects/Seeed_GxEPD2) 和 [Seeed E1002 示例](https://wiki.seeedstudio.com/cn/reterminal_e10xx_with_arduino/)。官方规格表写刷新约 15–20 秒，示例写约 25–30 秒；实测时间可能不同。E1002 不支持局部刷新。[型号对比](https://wiki.seeedstudio.com/cn/reterminal_e10xx_main_page/)

服务端将文字渲染为六色图片；设备按每日时间表唤醒，通过 HTTP 查询时间表、校时、获取最新版本、校验 SHA-256、全屏刷新并确认，然后断开 Wi-Fi 深度睡眠。默认拉取时间为北京时间 06:30、09:30、12:30、18:30、22:30，可在 FastAPI 中为每台设备修改。服务端保留 MQTT 供旧固件使用，新固件不再订阅。设备同次唤醒内限制两次刷新间隔至少 60 秒；实际续航需实测。

## Ubuntu 服务端

需要 Docker Engine 和 Compose 插件。Ubuntu 与 E1002 使用同一可信局域网：

```sh
cp .env.example .env
# 编辑 .env，将 PIXEL_TOKEN 换成强随机值，例如 openssl rand -hex 32
nano .env
docker compose up --build -d
docker compose logs -f api
curl http://127.0.0.1:18000/health
```

默认占用 18000 和 1883 端口。数据存放在本 Compose 项目的 `pixel-data` 卷。`docker compose down -v` 会删除其数据。

此原型使用明文 HTTP 共享令牌及匿名明文 MQTT，仅用于可信局域网。不要暴露到公网；其他局域网用户可以监听或注入 MQTT 消息。正式部署需要 HTTPS、MQTT TLS 和账户/ACL、设备独立凭据和网络访问控制。

## 固件

安装 PlatformIO，复制 `firmware/include/config.example.h` 为 `config.h`，填写 2.4 GHz Wi-Fi、服务器局域网 IP、与 `.env` 一致的令牌和唯一设备 ID。`API_BASE` 形如 `http://192.168.1.10:18000`，不要用 `localhost`。

```sh
cd firmware
pio run -e e1002
pio run -e e1002 -t upload
pio device monitor -b 115200
```

PlatformIO 从 Seeed GitHub 仓库下载驱动，需要网络和 Git。此依赖当前跟随仓库默认分支；可在确定可编译版本后固定提交。开发板配置使用通用 ESP32-S3 和 OPI PSRAM，固件优先从 PSRAM 分配 192,000 字节画面缓存。USB 模式、分区和设备连接请参考 [E1002 入门指南](https://wiki.seeedstudio.com/getting_started_with_reterminal_e1002/)；本配置尚待实机验证。先运行 Seeed 官方屏幕示例有助于排查硬件与刷机问题。

## 端到端测试

服务启动、烧录固件并联网后，在 Ubuntu 上：

```sh
TOKEN='替换成 .env 中的令牌'
curl -sS -X POST http://127.0.0.1:18000/v1/devices/desk-01/display \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data '{"title":"你好，Pixel","body":"E1002 六色测试\n来自 pixel-app 的消息","title_color":"blue"}'
curl -sS http://127.0.0.1:18000/v1/devices/desk-01/desired \
  -H "Authorization: Bearer $TOKEN"
```

`display` 返回的 `version` 是画面 SHA-256，表示服务端已保存，不代表屏幕已更新。等待刷新，串口输出 `displayed <version>`，再查询 desired：`displayed_version` 与 `version` 相同且 `status` 为 `displayed`。确认只是程序执行了刷新，不能证明屏幕视觉显示正确。测试断线恢复时，先断开设备网络、提交新内容，再联网；下次定时或手动唤醒连接时应获取最新版本。`last_seen` 是确认时间，不是心跳。提交新版本后旧状态可能保留，应始终比较版本。

如失败，检查 IP、网络、令牌、MQTT/HTTP 端口，查看 `docker compose logs api mosquitto` 和串口。颜色顺序、画面方向和刷新行为需通过实体屏幕确认。镜像和库下载需要网络。

## pixel-app 接口

`examples/pixel_app.py` 提供纯 Python 调用函数和通用工具 schema，实际注册方式取决于 pixel-app 的框架。设置 `PIXEL_API_BASE`、`PIXEL_TOKEN` 后，分派到 `display_text(device_id, title, body, title_color)`。`title_color` 可选 `black`、`white`、`red`、`yellow`、`green`、`blue`，默认 `blue`。正文为黑色、分隔线为红色；正文按字符换行，超出屏幕部分截断，标题不自动换行。容器内置 Noto CJK 字体。

## 设备协议

- API 均需要 `Authorization: Bearer <token>`，设备 ID 仅允许字母、数字、`_` 和 `-`。
- `POST /v1/devices/{id}/display` 接受 JSON `title`、`body`、`title_color`。`GET /v1/devices/{id}/desired` 返回目标和已确认版本；首次无目标时返回 `version: null`。
- `GET /v1/devices/{id}/bitmap?version=<sha256>` 返回当前版本，未保存的版本返回 404。HTTP body 固定 192,000 字节，800×480，按行排列，每字节高半字节为左像素、低半字节为右像素。索引 0 白、1 黑、2 红、3 黄、4 绿、5 蓝；6–15 保留。SHA-256 对完整 body 计算。这是本项目传输格式，不是面板寄存器颜色编码。
- `POST /v1/devices/{id}/ack` 接受 `{"version":"...","status":"displayed"}` 或 `failed`；版本未保存时返回 409。
- MQTT topic `pixel/v1/devices/<id>/notify`，QoS 1、非 retained，消息为 `{"version":"..."}`。供旧版常连固件触发检查；定时休眠固件不订阅。`mqtt_queued` 表示客户端接受发布请求，不保证设备收到。
- 首版为文本面板，支持六种标题颜色，支持 OTA（需先 USB 烧录一次），不包含注册。固件上一次已显示版本保存在 NVS；断电后屏幕保留画面，确认丢失时重发。

## 自动测试

安装 `server/requirements.txt`、`pytest`、`httpx` 和 Noto CJK 字体后运行 `pytest tests/test_api.py`。测试使用模拟 MQTT，不覆盖设备、真实 broker 或屏幕。

## 历史记录与按键

服务端为每次提交保存一条历史画面（包括重复提交），按提交顺序浏览；数据持久保存在原 SQLite 数据卷中，升级启动时会自动将原有最新画面补入历史。升级前的更早画面无法恢复。当前不自动清理历史，每条位图约 192 KB，请关注磁盘空间。

按键默认 KEY0/GPIO3 后退、KEY1/GPIO4 前进、KEY2/GPIO5 返回最新，采用上拉、低电平按下和防抖。引脚参考 [Seeed 官方外设说明](https://wiki.seeedstudio.com/reterminal_e10xx_with_arduino_peripherals/)，实物图标顺序仍需按键验证。

唤醒操作窗口内浏览历史时不会被新消息打断，到达首尾时停留；刷新键退出浏览并重新查询最新记录。若内容与已显示画面一致，不重复闪屏。刷新键事件在屏幕刷新期间也会记录，物理刷新完成后处理；屏幕最小 60 秒刷新间隔仍然有效，不能立即中断正在刷新的画面。离线时需要恢复网络才能从服务端获取历史或最新画面，重启后默认回到最新。

新增 `GET /v1/devices/{id}/history?cursor=<seq>&direction=latest|previous|next`，返回 `seq`、`version`、`created`。省略 cursor 时返回最新；首尾越界时返回原记录。bitmap 接口支持历史版本，ack 接受已保存的历史版本；desired 中 `version` 始终代表最新目标，`displayed_version` 可代表正在浏览的历史画面。

升级时先在 Ubuntu 更新服务端代码，执行 `docker compose up --build -d`，再按《固件更新说明》编译并 USB 烧录新固件。不要删除原数据卷。验收：连续提交三条不同消息，后退两次、前进一次；浏览中提交第四条，确认停留；按刷新返回第四条。刷新期间按刷新键，再确认结束后最终回到最新。

## 电池图标

固件在右上角覆盖显示圆角电池进度图标（无数字），正常黑色，估算电量低于 20% 为黄色，低于 10% 为红色；恰好 20% 为黑色，恰好 10% 为黄色。图标使用白底，占用画面 x=714–775、y=22–55，上传图片时也会覆盖该区域。

依据 [Seeed 官方电池接口说明](https://wiki.seeedstudio.com/reterminal_e10xx_with_arduino_peripherals/#battery-management-system)，GPIO21 高电平启用检测，等待 10ms 后通过 GPIO1 多次读取校准毫伏值，平均并乘 2，结束后关闭检测电路。电量由单节锂电池电压曲线估算，受负载、充电和电池老化影响，并非精确电量计。

每次定时或手动唤醒拉取内容时更新电池图标，休眠期间保留画面，不再每 15 分钟唤醒检测。每次启动需联网取得页面后重画。更新需重新 USB 烧录。

电量换算和阈值检查：

```sh
c++ -std=c++11 -Ifirmware/include firmware/tests/battery_test.cpp -o /tmp/ink-battery-test
/tmp/ink-battery-test
```

## 每日定时拉取与深度睡眠

固件默认每天北京时间（Asia/Shanghai，UTC+8）06:30、09:30、12:30、18:30、22:30 唤醒。每次启动先连接 Wi-Fi，查询时间表并用服务端时间校时，再拉取最新内容、刷新电池图标；完成后结束 Wi-Fi 连接并深度睡眠。启动后保留约 30 秒操作窗口，按键操作会延长窗口，单次联网失败约两分钟后也会休眠。休眠保留屏幕画面，不接收 MQTT 推送。

FastAPI 为每台设备提供持久化时间表（使用现有 Bearer 令牌认证）：

- `GET /v1/devices/{device_id}/schedule`：返回 `times`、`minutes`、固定 `timezone` 和当前 Unix 秒 `server_time`。
- `PUT /v1/devices/{device_id}/schedule`：提交 `{"times":["06:30","09:30","12:30","18:30","22:30"]}`。允许 1～24 个互不重复的 `HH:MM`，自动排序，存入 SQLite；重启服务后保留。可直接通过 FastAPI `/docs` 的接口表单配置（authorization 参数填 `Bearer 你的令牌`）。

设备每次醒来都会检查时间表并缓存到 NVS，配置变化后按新表计算下一次唤醒。已睡眠的设备只能在旧表的下一次唤醒时获取修改；三个功能键均可提前唤醒：KEY0（GPIO3）上一页、KEY1（GPIO4）下一页、KEY2（GPIO5）刷新最新内容。休眠前保存当前页面位置，上一页/下一页会相对屏幕保留的页面导航。按键硬件效果需在设备上验证。

服务不可用时使用已缓存的时间表（无缓存则使用默认表）；RTC 时间有效时照常计算下一次唤醒。完全断电且无法从服务器校时时，每小时重试一次，避免一直开着 Wi-Fi。设备时钟有漂移，拉取时间为近似时间，每次成功请求会重新校准。服务器应保持系统时间准确。

部署需更新 FastAPI 服务并重新 USB 烧录固件；若只更新固件而服务尚无 schedule 接口，会使用本地默认时间表和回退逻辑。新版固件不再使用 `POLL_INTERVAL_MS` 或 MQTT 配置；服务端仍保留 MQTT 以兼容旧固件。

本次验证：接口测试 3 项通过，电量与唤醒时间计算的 C++ 检查通过，ESP32-S3 固件使用示例配置编译成功。接口测试在本机使用可用的 Pillow 13 和系统字体，部署仍使用 requirements.txt 中的版本；驱动 Git 下载失败后，编译使用同一官方仓库的源码压缩包。实际联网、定时唤醒和三个功能键唤醒仍需设备验证。

## Wi-Fi 固件更新

已加入每次唤醒检查固件更新、单设备发布和校验后安装，发布改为在 Ubuntu 执行 `python3 scripts/release_firmware.py`（自动读取设备名并递增版本号），不提供上传或发布 HTTP 接口。详见 [OTA 更新说明](OTA更新说明.md)。首次仍需 USB 烧录启用 OTA。
