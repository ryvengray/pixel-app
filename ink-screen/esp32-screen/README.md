# ESP32 Pixel Controller

此子项目在 `pixel-app/ink-screen/esp32-screen/` 维护。以下命令从此目录执行。

第一版：BLE 键盘/相对鼠标 + Wi-Fi 轮询 pixel-app 后端 + LCD 状态/最近日志。
不包含语音识别、AI 自动规划、中文输入法处理或绝对坐标点击；先验证基础链路。

## 硬件

- 实测：ESP32-D0WD-V3 revision 3.1，240 MHz，4 MB Flash。
- USB 串口：`/dev/cu.usbserial-1440`（重新插拔可能改变）。
- 屏幕按与用户图片及参数匹配的 LCDWIKI E32R32P 配置，ST7789P3，240×320。
- 官方引脚表：https://www.lcdwiki.com/3.2inch_ESP32-32E_Display
- LCD：CS15 / DC2 / SCK14 / MOSI13 / MISO12 / BL27 / RESET 共用 EN。
- 触摸 CS33、SD CS5 拉高避免总线冲突；音频 EN4 拉高关闭。
- 当前不启用触摸。屏幕使用英文，避免缺中文字库导致乱码。

## 编译、烧录

```bash
pio run -d firmware
pio run -d firmware -t upload --upload-port /dev/cu.usbserial-1440
pio device monitor --port /dev/cu.usbserial-1440 --baud 115200
```

依赖固定为 espressif32 6.10.0 / Arduino 2.0.17、TFT_eSPI 2.5.43、ArduinoJson 6.21.5。
采用 huge_app 分区，无 OTA。出厂 Flash 已备份到 `backups/factory-4mb.bin`，不提交 Git。
恢复时先关闭串口监视器，然后执行：

```bash
python /path/to/esptool.py --port /dev/cu.usbserial-1440 write_flash 0 backups/factory-4mb.bin
```

## 首次配置

先在 Pixel 后端设置两个不同的随机令牌，详见 [手机控制指南](../../docs/phone-control.md)。
在有 pyserial 的 Python 环境中运行（本机 PlatformIO Python 已安装）：

```bash
python tools/configure.py --server http://UBUNTU_LAN_IP:8787 --ssid YOUR_2_4_GHZ_WIFI
```

本机也可以通过 SSH 自动读取已部署后端的设备令牌，不需要手工复制：

```bash
/Users/ryven/.platformio/penv/bin/python3 tools/configure.py --server http://192.168.0.160:8787 --ssh leigang@192.168.0.160
```

交互输入 Wi-Fi 名称和密码，令牌不会打印。

交互输入 Wi-Fi 密码及 `PHONE_CONTROL_DEVICE_TOKEN`。配置通过 USB 串口写入板上 NVS，
不写源码、不打印令牌。重配后自动重启。使用局域网 HTTP，暂不支持 HTTPS。

在 Pixel 蓝牙设置中配对 **Pixel-Control**，检查外接实体键盘布局为美式英语。
板子初始 PAUSED，短按 BOOT 后为 ARMED，再按暂停；蓝牙断开后自动暂停。
屏幕显示 BLE、IP、服务器通信、剩余堆内存及最近六条日志，串口也输出同样日志。
HTTP 单次等待最长约 1.2 秒，因此 BOOT 不属于硬件级即时断电停止。

## 协议

每约 750ms POST `/v1/phone-control/device/poll`，Bearer 设备令牌。
请求含 boot_id、ble_connected、armed、ip、heap、uptime_ms、firmware、logs，以及上次动作 ack。
响应 `command: null` 或 `{id, action}`。

支持动作：

- `{"type":"move","dx":20,"dy":0,"wheel":0}`：各轴 -127..127，单位为 HID 相对移动量，非屏幕像素。
- `{"type":"click","button":1}`：1 左、2 右、4 中。
- `{"type":"key","usage":40,"modifiers":0}`：USB HID usage，40 回车、42 退格、43 Tab；修饰键位掩码。
- `{"type":"text","text":"hello"}`：最多 160 个美式键盘可输入 ASCII 字符，支持换行/Tab。
- `{"type":"release"}`：释放所有按键。

动作自动释放按键；不实现持续按下/拖动。UTF-8 中文会在发送任何按键前被拒绝。
HTTP 响应丢失或板子重启时不会自动重放动作，后端可能标记 unknown，需要重新截图核对。
服务器已接入本地模型定位和带光标反馈的坐标点击，见下方说明；自动多步任务规划仍需后续接入。

## 网络诊断与固定 IP

串口发送一行 `{"op":"status"}` 可查看当前状态、Wi-Fi MAC、RSSI 和连接状态；不返回密码或令牌。
发生地址冲突时，可通过串口发送如下配置，保留已有 Wi-Fi 和服务器信息并重启：

```json
{"op":"network","ip":"192.168.0.210","gateway":"192.168.0.1","mask":"255.255.255.0"}
```

先确认地址未被使用，再设置，并在路由器中保留该地址，避免 DHCP 后续分配给其他设备。
恢复自动 DHCP 使用 `{"op":"network","ip":""}`。固定地址保存在 NVS，重新烧录程序仍然保留。

## 0.1.1 联调记录

- 修复已配对设备重连时 Bluedroid 本地通知标志的兼容性处理，参考：https://github.com/T-vK/ESP32-BLE-Keyboard/blob/master/BleKeyboard.cpp
- 键盘/鼠标通知通过回调检查发送结果，通知失败不再返回成功。
- 串口 status 另含 keyboard_notify / mouse_notify / last_notify_status，便于排查。
- 已通过 Pixel 的 getevent 验证数字 1、2、3 的 DOWN/UP 事件；接收通知不再只依赖固件回执推断。
- 控制页：http://192.168.0.160:8787/esp32-screen 。使用现有 ink-screen 验证码登录。


## 本地模型识别与蓝牙坐标点击

服务器代码位于同仓库的 `server/`。当前部署在 `leigang@192.168.0.160`：
Ollama容器运行 `qwen3-vl:2b-instruct`，宿主机视觉网关通过无线ADB截图，
返回模型识别的目标框和手机原始像素坐标。

打开控制台识别目标并确认标注框后，点击「蓝牙点击识别目标」。服务器读取Android光标
图层位置，通过现有相对鼠标指令逐步校准与移动，最后让ESP32发送一次BLE左键点击。
无需修改0.1.1固件的HID描述符，也无需重新配对。无线ADB负责读取画面和位置反馈，
识别后的点击由板子通过蓝牙完成。

板子需要在线、蓝牙已连接且处于ARMED。结果过期、前台页面变化、光标反馈异常或蓝牙
断开时会停止；未知执行结果不自动重放。当前仅实测Pixel竖屏和普通箭头光标。

模型启动、运行参数、日志监控、自动测试和实机测试详见：
[本地视觉模型运维手册](../../docs/local-vision-operations.md)。
