# Pixel Display v1 — E1002

此版本适用于 Seeed reTerminal E1002 7.3 英寸六色电子纸，包含 Ubuntu Docker Compose 服务端、MQTT、文本渲染、设备固件及 pixel-app 调用示例。当前版本为首次实机验证准备的原型：尚未完成固件编译、烧录或真实屏幕联调。

## 硬件与工作方式

官方资料：800×480、GDEP073E01 面板、ED2208 控制器，原生黑、白、红、黄、绿、蓝六色。官方示例使用 `GxEPD2_7C<GxEPD2_730c_GDEP073E01>`，SPI SCK 7、MOSI 9、CS 10、DC 11、RST 12、BUSY 13、2 MHz。本项目采用 [Seeed_GxEPD2](https://github.com/Seeed-Projects/Seeed_GxEPD2) 和 [Seeed E1002 示例](https://wiki.seeedstudio.com/cn/reterminal_e10xx_with_arduino/)。官方规格表写刷新约 15–20 秒，示例写约 25–30 秒；实测时间可能不同。E1002 不支持局部刷新。[型号对比](https://wiki.seeedstudio.com/cn/reterminal_e10xx_main_page/)

服务端将文字渲染为六色图片；设备接收 MQTT 通知后通过 HTTP 获取最新版本、校验 SHA-256、全屏刷新并确认。断线后每 60 秒轮询恢复。设备限制上一次刷新完成后至少 60 秒才启动下一次，频繁提交只保证最终画面。固件保持 Wi-Fi 在线，适合 USB 常供电，不能按官方低功耗固件续航估算。重启后的刷新间隔不会保留。

## Ubuntu 服务端

需要 Docker Engine 和 Compose 插件。Ubuntu 与 E1002 使用同一可信局域网：

```sh
cp .env.example .env
# 编辑 .env，将 PIXEL_TOKEN 换成强随机值，例如 openssl rand -hex 32
nano .env
docker compose up --build -d
docker compose logs -f api
curl http://127.0.0.1:8000/health
```

默认占用 8000 和 1883 端口。数据存放在本 Compose 项目的 `pixel-data` 卷。`docker compose down -v` 会删除其数据。

此原型使用明文 HTTP 共享令牌及匿名明文 MQTT，仅用于可信局域网。不要暴露到公网；其他局域网用户可以监听或注入 MQTT 消息。正式部署需要 HTTPS、MQTT TLS 和账户/ACL、设备独立凭据和网络访问控制。

## 固件

安装 PlatformIO，复制 `firmware/include/config.example.h` 为 `config.h`，填写 2.4 GHz Wi-Fi、服务器局域网 IP、与 `.env` 一致的令牌和唯一设备 ID。`API_BASE` 形如 `http://192.168.1.10:8000`，不要用 `localhost`。

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
curl -sS -X POST http://127.0.0.1:8000/v1/devices/desk-01/display \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data '{"title":"你好，Pixel","body":"E1002 六色测试\n来自 pixel-app 的消息","title_color":"blue"}'
curl -sS http://127.0.0.1:8000/v1/devices/desk-01/desired \
  -H "Authorization: Bearer $TOKEN"
```

`display` 返回的 `version` 是画面 SHA-256，表示服务端已保存，不代表屏幕已更新。等待刷新，串口输出 `displayed <version>`，再查询 desired：`displayed_version` 与 `version` 相同且 `status` 为 `displayed`。确认只是程序执行了刷新，不能证明屏幕视觉显示正确。测试断线恢复时，先断开设备网络、提交新内容，再联网；下次连接或轮询应获取最新版本。`last_seen` 是确认时间，不是心跳。提交新版本后旧状态可能保留，应始终比较版本。

如失败，检查 IP、网络、令牌、MQTT/HTTP 端口，查看 `docker compose logs api mosquitto` 和串口。颜色顺序、画面方向和刷新行为需通过实体屏幕确认。镜像和库下载需要网络。

## pixel-app 接口

`examples/pixel_app.py` 提供纯 Python 调用函数和通用工具 schema，实际注册方式取决于 pixel-app 的框架。设置 `PIXEL_API_BASE`、`PIXEL_TOKEN` 后，分派到 `display_text(device_id, title, body, title_color)`。`title_color` 可选 `black`、`white`、`red`、`yellow`、`green`、`blue`，默认 `blue`。正文为黑色、分隔线为红色；正文按字符换行，超出屏幕部分截断，标题不自动换行。容器内置 Noto CJK 字体。

## 设备协议

- API 均需要 `Authorization: Bearer <token>`，设备 ID 仅允许字母、数字、`_` 和 `-`。
- `POST /v1/devices/{id}/display` 接受 JSON `title`、`body`、`title_color`。`GET /v1/devices/{id}/desired` 返回目标和已确认版本；首次无目标时返回 `version: null`。
- `GET /v1/devices/{id}/bitmap?version=<sha256>` 返回当前版本，旧版本返回 404。HTTP body 固定 192,000 字节，800×480，按行排列，每字节高半字节为左像素、低半字节为右像素。索引 0 白、1 黑、2 红、3 黄、4 绿、5 蓝；6–15 保留。SHA-256 对完整 body 计算。这是本项目传输格式，不是面板寄存器颜色编码。
- `POST /v1/devices/{id}/ack` 接受 `{"version":"...","status":"displayed"}` 或 `failed`；目标已变化时返回 409。
- MQTT topic `pixel/v1/devices/<id>/notify`，QoS 1、非 retained，消息为 `{"version":"..."}`。它仅触发检查；可靠性依赖 HTTP 轮询。`mqtt_queued` 表示客户端接受发布请求，不保证设备收到。
- 首版为文本面板，支持六种标题颜色，不包含图片上传、图像抖动、深睡眠、OTA、注册或电量监测。固件上一次已显示版本保存在 NVS；断电后屏幕保留画面，确认丢失时重发。

## 自动测试

安装 `server/requirements.txt`、`pytest`、`httpx` 和 Noto CJK 字体后运行 `pytest tests/test_api.py`。测试使用模拟 MQTT，不覆盖设备、真实 broker 或屏幕。
