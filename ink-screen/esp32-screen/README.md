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

## 回家联调

1. 在家里服务器的 `server/.env` 中添加 `AHT20_DEVICE_TOKEN`，值用 `openssl rand -hex 32` 生成。在服务器的仓库根目录构建并启动新版服务。

   ```bash
   docker compose up -d --build greta-service
   ```

2. 在电脑的仓库中进入本目录，编译、烧录，然后写入 Wi-Fi 和温湿度上传令牌。将串口名、服务器 IP 和 SSID 换成现场的值。`tools/configure.py` 依赖 pyserial，可用安装了 PlatformIO 的同一个 Python 环境运行。令牌和 Wi-Fi 密码会交互输入。

   ```bash
   pio run -d firmware
   pio run -d firmware -t upload --upload-port /dev/cu.usbserial-1440
   python tools/configure.py --server http://192.168.0.160:8787 --ssid YOUR_WIFI
   pio device monitor --port /dev/cu.usbserial-1440 --baud 115200
   ```

   固件沿用板上已保存的 Wi-Fi、服务地址和静态 IP，上传使用新配置的温湿度令牌。串口发送一行 `{"op":"status"}` 可查看状态，发送 `{"op":"network","ip":""}` 可恢复 DHCP。

3. 开机后 LCD 应显示温度和湿度。Wi-Fi 连接后应显示 `Upload: OK`。从同一局域网查询已保存的数据。

   ```bash
   curl -fsS -H "Authorization: Bearer ${AHT20_DEVICE_TOKEN}" \
     "http://192.168.0.160:8787/v1/sensors/aht20/readings?limit=3"
   ```

   查询前在当前终端将 `AHT20_DEVICE_TOKEN` 设置为服务器中相同的值。首次联调可以按 RESET 触发一次新采集，无需等一小时。

## 断线与数据记录

读取失败后每 30 秒重试。断网期间继续按小时采集，最近 24 条待上传读数保存在内存，重启会清空。连网后会补传，服务器按读数 ID 去重。Wi-Fi 已连接但上传失败时，每 30 秒重试。

读数保存在现有 SQLite 数据库，字段为 `sample_id`、`temperature_c`、`humidity_pct` 和 `received_at`。`received_at` 是服务器收到数据的 UTC 时间，补传也使用收到时的时间。上传和查询走家中局域网 HTTP。
