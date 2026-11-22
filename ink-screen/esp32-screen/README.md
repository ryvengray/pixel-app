# ESP32 Gravity AHT20 温湿度采集

板子开机后采集一次，之后每隔 60 分钟采集并通过 2.4 GHz Wi-Fi 上传。LCD 显示最近一次有效读数、传感器、Wi-Fi 和上传状态。此版本已移除蓝牙键鼠功能。

## 接线

按仓库记录中的 LCDWIKI E32R32P 屏幕板，断电后接板上的 **P4 / I²C** 扩展口。Gravity AHT20（SEN0528）地址为 `0x38`。

| Gravity AHT20 | ESP32 屏幕板 P4 |
| --- | --- |
| `+` | `3V3` |
| `-` | `GND` |
| `SDA` | `IIC SDA`（GPIO32） |
| `SCL` | `IIC SCL`（GPIO25） |

P4 是 1.25 mm 间距接口。使用匹配 P4 的转接线，按信号名逐线核对两端的线序。参见 [LCDWIKI 用户手册](https://www.lcdwiki.com/res/E32R32P/3.2inch_ESP32-32E_E32R32P_E32N32P_User_Manual.pdf) 和 [DFRobot 模块说明](https://wiki.dfrobot.com/sen0528/)。

## 手机设置 Wi-Fi

1. 上电后，未配置网络/上传信息或断网超过约 30 秒时，设备开放 `Pixel-Setup-xxxx` 热点。也可在正常开机后按住 **BOOT 3 秒**手动开启（不要按住 BOOT 再上电）。
2. 手机连接这个热点，密码为 `12345678`（LCD 上的 `Pass`）。手机提示无互联网时选择保持连接。
3. 浏览器输入完整地址 **http://192.168.4.1/**（不要使用 HTTPS）。连接设置热点时直接进入页面，无需再次登录；通过家里局域网访问时，用户名为 `admin`，密码为 `12345678`。
4. 填写家里的 **2.4 GHz Wi-Fi** 名称和密码。服务器地址使用 **`https://ryven.xin/gray`**，令牌填写水墨屏已有的 **`INK_TOKEN`**。也可以先留空令牌，只配置 Wi-Fi。
5. 点击“保存并连接”。设备重启后自动连接，LCD 显示局域网 IP；以后手机连接同一路由器，可打开 `http://屏幕上的IP` 修改配置，登录信息仍显示在屏幕上。

密码和令牌留空会保留原值；切换到开放网络时勾选“这是无密码网络”。网页不会回显已保存的 Wi-Fi 密码和 `INK_TOKEN`。网页保存网络配置会恢复 DHCP，避免旧静态 IP 导致新网络无法连接。连接失败会重新开放设置热点，已连接且上传配置完整、没有手机连接热点时，热点在开启两分钟后关闭。配网期间暂停旧网络重连；保存后重启连接新网络。

设置页由 ESP32 本地提供，无需服务器在线。设置热点与网页登录共用简易密码 `12345678`，用户名为 `admin`。请勿将设备的设置页端口转发到公网。

## 服务器与上传

服务器复用 `server/.env` 中已有的 `INK_TOKEN`，无需新建令牌。在服务器仓库根目录构建并启动新版服务：

```bash
docker compose up -d --build greta-service
curl -fsS https://ryven.xin/gray/health
```

现有 Nginx `/gray/` 转发规则会去掉前缀。固件将上传到 `https://ryven.xin/gray/v1/sensors/aht20/readings`，使用 `Authorization: Bearer <INK_TOKEN>`。HTTPS 使用 DigiCert Global 根证书验证服务器，并在联网后通过 NTP 校时；校时成功前显示 `Waiting for clock`。如果域名以后更换为其他 CA，需要更新 `firmware/src/trusted_roots.h`。局域网 HTTP 地址也可使用。

开机后 LCD 应显示温湿度，成功上传后显示 `Upload: OK`。查询前在当前终端设置与服务器相同的 `INK_TOKEN`：

```bash
curl -fsS -H "Authorization: Bearer ${INK_TOKEN}" \
  "https://ryven.xin/gray/v1/sensors/aht20/readings?limit=3"
```

首次联调可按 RESET 触发新采集，无需等一小时。服务器仍需部署包含温湿度接口和共享令牌鉴权的新版代码。

## 编译、烧录与 USB 配置

```bash
pio run -d firmware
pio run -d firmware -t upload --upload-port /dev/cu.usbserial-1440
```

可选的 USB 配置方式（交互输入 Wi-Fi 密码和 `INK_TOKEN`）：

```bash
python tools/configure.py --server https://ryven.xin/gray --ssid YOUR_WIFI
pio device monitor --port /dev/cu.usbserial-1440 --baud 115200
```

`tools/configure.py` 依赖 pyserial，可使用安装 PlatformIO 的同一 Python 环境。串口发送 `{"op":"status"}` 查看状态，`{"op":"setup"}` 开启热点，`{"op":"network","ip":""}` 恢复 DHCP。USB 配置沿用板上静态 IP；网页配置恢复 DHCP。

## 断线与数据记录

读取失败后每 30 秒重试。断网期间继续按小时采集，最近 24 条待上传读数保存在内存，重启会清空。连网后会补传，服务器按读数 ID 去重。Wi-Fi 已连接但上传失败时，每 30 秒重试。

读数保存在现有 SQLite 数据库，字段为 `sample_id`、`temperature_c`、`humidity_pct` 和 `received_at`。`received_at` 是服务器收到数据的 UTC 时间，补传也使用收到时的时间。上传和查询可走域名 HTTPS，也可使用家中局域网 HTTP。
