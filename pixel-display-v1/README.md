# Pixel Display v1 — reTerminal E1001

一个局域网内的最小实现：pixel-app 调用 FastAPI，服务端渲染 800×480 黑白画面并保存最新版本；MQTT 通知设备，设备通过 HTTP 下载、校验并刷新屏幕，再通过 HTTP 报告结果。断线或 API 重启后，设备通过 60 秒轮询恢复。固件保持 Wi-Fi 和 MQTT 在线，适合 USB 常供电；并非电池长续航方案。服务端只保存每个设备的最新画面，中间更新可能被合并。

## 官方硬件依据

E1001 为 ESP32-S3、7.5 英寸 800×480 GDEY075T7 黑白屏、UC8179 控制器。Seeed 官方 Arduino 示例的屏幕 SPI 引脚是 SCK 7、MOSI 9、CS 10、DC 11、RST 12、BUSY 13，HSPI 2 MHz。本项目使用相同引脚与 GxEPD2 的 GDEY075T7 驱动。参考：[Seeed Arduino 显示教程](https://wiki.seeedstudio.com/reterminal_e10xx_with_arduino/) 和 [E1001 入门指南](https://wiki.seeedstudio.com/getting_started_with_reterminal_e1001/)。建议初次刷机先按照官方教程验证硬件及 USB 串口；本项目还未在实体 E1001 上验证。

## 部署 Ubuntu

需要 Docker Engine 与 Compose 插件，Ubuntu 主机和 E1001 处于可互通的可信局域网。执行：

```sh
cp .env.example .env
# 编辑 .env，把 PIXEL_TOKEN 换成足够长的随机字符串，例如 openssl rand -hex 32
nano .env
docker compose up --build -d
docker compose logs -f api
curl http://127.0.0.1:8000/health
```

防火墙需允许设备访问 TCP 8000、1883。API 的 `/docs` 提供交互式文档；所有 `/v1/` 路由均需 `Authorization: Bearer TOKEN`。数据保存在 Compose 卷 `pixel-data`，卸载时不要使用 `docker compose down -v`，除非确定删除数据。

**网络边界：**这是可信局域网原型。HTTP 明文传递共享令牌；MQTT 默认匿名、无加密、无 ACL，局域网内其他客户端可以读取通知、注入通知或造成干扰。不要映射至公网、不使用不可信 Wi-Fi。生产部署应增加 HTTPS、每设备凭据、MQTT TLS/账户与 topic ACL、限制访问及令牌轮换。API 令牌对所有设备通用。

## 编译与烧录

安装 PlatformIO，复制 `firmware/include/config.example.h` 为 `firmware/include/config.h`，填写 2.4 GHz Wi-Fi、Ubuntu 主机局域网 IP、相同 token 和设备 ID（字母、数字、下划线、连字符）。不要使用 `localhost`。该文件被 `.gitignore` 排除。

```sh
cd firmware
pio run -e e1001
pio run -e e1001 -t upload
pio device monitor -b 115200
```

USB 串口/下载模式、具体板载配置参考 Seeed 官方文档。示例选用通用 ESP32-S3 板定义和 OPI PSRAM 配置；因未实际编译或烧录，首次部署须检查 PlatformIO 平台、库版本及板载 USB 设置。如需进入下载模式请遵循官方指南。屏幕驱动、布局及显示方向仍须实体检查。

## 端到端联调

1. 启动服务，配置并烧录固件，查看串口已连接 Wi-Fi。健康检查的 `mqtt_connected` 为 true 表示 API 已连接 MQTT；设备日志未必立即输出连接状态。
2. 在 Ubuntu 上将 `TOKEN` 设置为 `.env` 中的值：

```sh
TOKEN='换成你的令牌'
curl -sS -X POST http://127.0.0.1:8000/v1/devices/desk-01/display \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data '{"title":"你好，Pixel","body":"第一条消息\nE1001 联调成功"}'
curl -sS http://127.0.0.1:8000/v1/devices/desk-01/desired \
  -H "Authorization: Bearer $TOKEN"
```

3. `display` 返回内容的 SHA-256 `version`。等待屏幕全刷，串口出现 `displayed <version>`；再次查询 desired，`displayed_version` 等于 `version` 且 `status` 为 `displayed`。前者仅表示服务端接受并保存，不代表设备已经显示。`mqtt_queued` 仅表示发布请求已提交给 MQTT 客户端，并非设备接收保证。
4. 断开设备网络，提交一条新消息，再重新联网；设备应该在连接或下一轮询时显示最新画面。服务端重启后也可执行同样检查。连续提交多条时仅保证最终画面，不保证显示每一条。
5. 若显示失败：先检查 IP、Wi-Fi 和令牌，再看 `docker compose logs api mosquitto` 与串口；检查字体、设备 ID、8000/1883 端口和位图 SHA-256。刷屏过程会阻塞网络；更新很频繁时请降低频率。

## pixel-app 接入

`examples/pixel_app.py` 是标准库实现和工具 schema 示例，实际注册方式须适配你的 pixel-app 工具框架；项目没有假定该应用的内部接口。配置 `PIXEL_API_BASE=http://<ubuntu-ip>:8000` 和 `PIXEL_TOKEN`，把工具调用分派给 `display_text(device_id, title, body)`。返回版本号后，按需查询 desired 确认显示状态。也可直接调用上述 REST API。

## 协议和限制

- `POST /v1/devices/{id}/display`：JSON `title`、`body`，长度上限分别为 80、1000。渲染为纯黑白；正文按字符换行，超出画布截断；标题不自动换行。支持容器内 Noto CJK 字体。
- `GET /v1/devices/{id}/desired`：未创建时 `version: null`；有画面时返回当前版本、更新时间、最后确认版本、确认时间与状态。`last_seen` 是最后一次成功接受的确认，不是心跳；新画面提交后旧状态可能保留，需比较版本。
- `GET /v1/devices/{id}/bitmap?version=<sha256>`：仅提供当前版本，不匹配返回 404。格式恰好 48,000 字节，800×480，逐行、每字节高位先行、1 白 0 黑，无文件头。HTTP 内容长度必须为 48000。
- `POST /v1/devices/{id}/ack`：`{"version":"...","status":"displayed"}` 或 `failed`；若期间目标版本改变，返回 409，设备下次轮询新版本。固件保存已显示版本到 NVS，重启时保留画面，丢失确认时重发。`displayed` 表示程序执行了刷新，不是对实际屏幕的光学确认。
- MQTT topic `pixel/v1/devices/<id>/notify`，服务端发布 JSON `{"version":"<sha256>"}`，QoS 1、不保留；消息只是提示，设备忽略内容并通过认证 HTTP 获取最新状态。掉线依靠轮询恢复。固定设备 ID 不应被多个设备共享。
- 全屏刷新、无部分刷新/灰阶；不限制推送频率，调用方应避免高频刷屏。没有上传图片、后台任务、独立设备注册、OTA、电量读取、深睡眠或可靠消息队列。内容与数据库明文存储。
