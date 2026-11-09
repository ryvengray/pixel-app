# 本地视觉模型运维：Ollama / Qwen3-VL / ESP32

本文对应 `leigang@192.168.0.160` 的实际部署。仓库在
`/home/leigang/projects/pixel-app`，控制台为
`http://192.168.0.160:8787/esp32-screen`。协议及蓝牙点击细节见
[phone-control.md](phone-control.md)。

## 服务分工

| 组件 | 启动方式 | 地址 / 职责 |
|---|---|---|
| `pixel-ollama` | Docker，`compose.vision.yaml` | `127.0.0.1:11434`；加载本地模型并推理 |
| `pixel-phone-vision` | systemd 用户服务 | `127.0.0.1:8789`；ADB截图、模型定位、蓝牙点击编排 |
| `greta-service` | 原有 Docker Compose | `8787`；登录认证、控制台、视觉API代理、ESP32指令队列 |
| ESP32 `Pixel-Control` | 板上0.1.1固件 | Wi-Fi领取指令，通过BLE HID发送键盘/鼠标报告 |

画面路径是“手机 → 无线ADB → 视觉网关 → 本地Ollama → 目标框/像素坐标”。
点击路径是“确认目标 → 视觉网关读取光标位置 → 指令队列 → ESP32 BLE鼠标”。
ADB只读取截图和诊断信息，识别后的点击由ESP32执行。

当前机器为 i7-1260P、约32GB内存、Intel Iris Xe核显。部署按CPU推理运行，
Docker限制为6个逻辑CPU的计算配额，一次处理一个模型请求。2026-11-09 实测
Ollama版本 `0.40.15`，模型 `qwen3-vl:2b-instruct`、ID `afc393e3572c`，
下载约1.9GB。Compose中的 `latest` 不固定版本；升级前记录版本和镜像ID，
需要复现时使用经过验证的版本标签或镜像摘要。

## 启动

在服务器仓库根目录执行：

```bash
cd /home/leigang/projects/pixel-app
docker compose -f compose.vision.yaml up -d
docker exec pixel-ollama ollama pull qwen3-vl:2b-instruct
docker exec pixel-ollama ollama list
```

`pull`只下载模型。模型存在后，API请求会按需加载，不需要一直运行交互式
`ollama run`。模型文件保存在Docker命名卷 `pixel-ollama`，重启容器会保留权重。
初次下载和更新需要联网，已下载模型的截图推理在服务器本地完成。

视觉网关在Ubuntu宿主机运行，以访问原有无线ADB。当前使用 `/usr/bin/python3`，
需要 FastAPI、uvicorn、httpx、Pillow、Pydantic，可先确认依赖：

```bash
/usr/bin/python3 -c 'import fastapi, uvicorn, httpx, PIL, pydantic; print("dependencies ready")'
/home/leigang/Android/Sdk/platform-tools/adb devices -l
```

设备应为 `device`。无线调试重新连接方法见 [手机控制指南](phone-control.md#ubuntu-无线截图采集)。
`server/.env` 中沿用两个不同的 `PHONE_CONTROL_ADMIN_TOKEN` 和
`PHONE_CONTROL_DEVICE_TOKEN`；网关只读取管理员令牌，不把令牌交给浏览器。

安装或更新用户服务模板：

```bash
mkdir -p ~/.config/systemd/user
cp server/pixel-phone-vision.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now pixel-phone-vision
```

模板包含本机用户名、ADB路径、设备serial和仓库绝对路径，换机器时先修改模板。
若使用独立Python虚拟环境，把模板 `ExecStart` 的解释器替换成对应路径。
当前服务器 `loginctl show-user leigang -p Linger` 为 `Linger=yes`，
允许用户服务在无人登录时启动。模型容器使用 `restart: unless-stopped`，
视觉网关使用 `Restart=on-failure`。

修改主服务API或控制台后，需要把新代码打进主服务镜像：

```bash
docker compose up -d --build --no-deps greta-service
```

只修改宿主机视觉网关时：

```bash
systemctl --user restart pixel-phone-vision
```

## 一次请求如何运行

1. 浏览器通过现有验证码登录，提交需要找到的目标。
2. 网关读取前台窗口，执行 `adb exec-out screencap -p`，并核对截图时窗口没有切换。
3. 截图长边缩至1280像素，以JPEG和目标描述调用 Ollama `/api/chat`。
4. 使用 `temperature=0`、`num_ctx=4096`、`num_predict=180`、`num_thread=6`，
   要求模型返回JSON以及0～1000的归一化目标框。
5. 网关校验坐标，换算回手机原始尺寸，返回标注所需的数据和短期有效的 `result_id`。
6. 点击「蓝牙点击识别目标」后，网关单独创建任务，校准/移动光标，到达目标后发送一次BLE点击。
   页面轮询任务状态并显示操作后的截图。

模型请求的 `keep_alive` 为10分钟，闲置到期后可卸载权重释放内存；Ollama服务仍运行。
`ollama list`检查“是否已下载”，`ollama ps`检查“是否正在内存中加载”，两者含义不同。
如需主动释放模型内存：

```bash
docker exec pixel-ollama ollama stop qwen3-vl:2b-instruct
```

下一次请求会重新加载模型。网关模型请求超时240秒，主服务代理等待上限260秒。
当前两次定位实测为32.52秒、28.40秒，首次推理容器约2.8GiB内存、CPU约600%。
这里的600%对应6个逻辑CPU配额；耗时会随图片和目标变化，适用于逐步操作。

## 状态、日志与资源监控

```bash
# 容器、模型与资源
docker compose -f compose.vision.yaml ps
docker exec pixel-ollama ollama --version
docker exec pixel-ollama ollama list
docker exec pixel-ollama ollama ps
docker stats --no-stream pixel-ollama

# 宿主机网关
systemctl --user status pixel-phone-vision --no-pager
systemctl --user is-enabled pixel-phone-vision
journalctl --user -u pixel-phone-vision -n 50 --no-pager
journalctl --user -u pixel-phone-vision -f

# Ollama和主服务日志
docker logs --tail 50 pixel-ollama
docker logs --tail 50 greta-service

# 无需登录的本机健康检查
curl -fsS http://127.0.0.1:11434/api/version
curl -fsS http://127.0.0.1:11434/api/tags
curl -fsS http://127.0.0.1:8787/health
```

主服务的 `GET /v1/phone-control/vision/status` 需要管理员Bearer令牌或控制台登录会话。
`ready:true`表示已下载指定模型，`busy`表示正在识别，`click_busy`表示正在执行蓝牙点击。
直接访问视觉网关 `/health` 而没有管理员令牌会返回401，这是认证结果。
当前监控依靠这些状态接口、systemd/Docker状态和日志，未另行配置告警服务。

状态及一次**仅识别、不点击**的API冒烟测试可在服务器执行以下脚本。
它从现有配置读取令牌，不输出令牌或截图Base64：

```bash
python3 - <<'PY'
from pathlib import Path
import httpx

config = Path('/home/leigang/projects/pixel-app/server/.env')
token = next(line.split('=', 1)[1].strip().strip('"').strip("'")
             for line in config.read_text().splitlines()
             if line.startswith('PHONE_CONTROL_ADMIN_TOKEN='))
base = 'http://127.0.0.1:8787/v1/phone-control'
with httpx.Client(trust_env=False, timeout=280,
                 headers={'Authorization': 'Bearer ' + token}) as client:
    status = client.get(base + '/vision/status')
    status.raise_for_status()
    print(status.json())
    result = client.post(base + '/vision/locate', json={'target': '点赞按钮'})
    result.raise_for_status()
    print({key: value for key, value in result.json().items()
           if key != 'image_base64'})
PY
```

## 自动测试与实机测试

在仓库根目录安装 `server/requirements.txt` 和pytest后执行。也可用临时虚拟环境，
与生产进程隔离：

```bash
python3 -m venv /tmp/pixel-vision-tests
/tmp/pixel-vision-tests/bin/pip install -r server/requirements.txt pytest
/tmp/pixel-vision-tests/bin/python -m pytest \
  tests/test_phone_bluetooth.py tests/test_phone_vision.py \
  tests/test_phone_control.py tests/test_phone_screen.py \
  tests/test_realtime_api.py tests/test_screen_sessions.py -q
node tests/test_phone_vision_ui.cjs
```

本次35项Python测试和页面交互测试通过。自动测试使用临时数据库、模拟模型及蓝牙反馈，
不会操作真实手机。覆盖鉴权、坐标校验、光标收敛、过期、页面切换、未知回执、
任务去重、服务重启恢复、页面双击及丢失响应后的原任务查询。

实机测试从控制台执行：

1. 保持手机无线调试在线，ESP32蓝牙连接，按BOOT使板子ARMED。
2. 显示一个明确目标，识别后检查目标框；隐藏的控件需要先恢复显示。
3. 点击「蓝牙点击识别目标」，观察校准、移动和执行进度。
4. 检查新的手机画面，核对实际业务结果。

2026-11-09 实测点赞目标 `(883,1829)`，点击前光标热点为
`(881.97,1828.25)`，误差约1.3像素；鼠标输入设备记录到一次
`EV_KEY BTN_MOUSE DOWN/UP`，点击后的白色爱心变为橙红色。
API仍返回 `report_sent:true`、`business_confirmed:false`，因为蓝牙报告成功本身
不能证明任何应用业务一定完成。这次点赞结果通过截图另行核对。

## 常见问题与数据位置

| 现象 | 检查方式 |
|---|---|
| 模型未就绪 | `ollama list`确认准确标签；查看容器日志和 `/vision/status` |
| `ollama ps`为空但ready为true | 已下载模型当前未驻留内存；下次请求会加载 |
| 截图失败 | `adb devices -l`、手机无线调试和实际serial；更新unit后重启网关 |
| 找不到点赞按钮 | 查看本次截图；播放器控件可能隐藏，模型也可能识别错误 |
| 点击按钮禁用 | 检查ESP32在线、蓝牙和ARMED，以及结果是否已过期 |
| 页面/光标校验失败 | 恢复目标页面、竖屏和普通箭头光标后重新识别 |
| 点击结果unknown | 查看手机及原任务ID；系统不会自动重复点击 |

数据保留位置：

- 模型权重：Docker卷 `pixel-ollama`，容器内 `/root/.ollama`。
- 主服务指令队列：现有 `/data/phone-control.db`（宿主机 `server/data/`）。
- 点击任务：`~/.local/share/pixel-vision/clicks.db`，由宿主机用户服务管理。
- 部署模板：`server/pixel-phone-vision.service`，已安装副本在 `~/.config/systemd/user/`。
- 本次实机验证记录：`~/.local/share/pixel-vision/validation/`，未提交到Git。

定位结果只在网关进程内短期保存，重启后需要重新识别。已持久化但未完成的点击任务
重启后标为unknown，不重放。当前蓝牙坐标点击仅验证过Pixel竖屏及普通箭头光标，
其他Android版本可能需要适配光标图层格式。
