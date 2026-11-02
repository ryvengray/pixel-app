# Ubuntu 一键编译并发布 OTA

在运行 Docker Compose 服务的 Ubuntu 机器上执行脚本，完成编译、校验和发布。不提供固件上传或发布 HTTP 接口。设备只通过认证接口检查、下载固件并上报运行状态。

## 首次准备

1. 更新 Ubuntu 上的项目代码，在 `pixel-display-e1002` 目录运行 `docker compose up --build -d`，让 API 容器包含新的本地发布工具。
2. Ubuntu 需要 Docker Compose、Git、Python 3 和 venv 支持；缺少后者可执行 `sudo apt-get install python3-venv git`。运行脚本的用户需要有 Docker 权限。
3. 将真实设备配置放入 `firmware/include/config.h`，填写 Wi-Fi、服务器地址、API_TOKEN、DEVICE_ID。此文件已被 Git 忽略，不要使用示例占位值。脚本会核对 DEVICE_ID 与目标设备相同。
4. E1002 首次启用 OTA 仍需 USB 烧录一次。分区表使用 `default_8MB.csv`，两个应用分区各 0x330000 字节；后续 OTA 不修改引导程序或分区表。

## 编译并发布

在 Ubuntu 项目的 `pixel-display-e1002` 目录执行：

```sh
python3 scripts/release_firmware.py
```

不传参数时，从 `firmware/include/config.h` 的 `DEVICE_ID` 读取设备名，不会另起一个设备名。版本号自动取数据库中所有已发布版本、设备上报运行版本、已预留版本和源码默认版本的最大值再加 1；首次默认生成 2。编译前在数据库中预留编号，并行运行不会取到相同编号；编译失败可能留下跳号，但不会改变设备发布。

仍可手动指定其中一项或两项：

```sh
python3 scripts/release_firmware.py --device desk-01 --build 20
```

手动版本号最大为 999999999，设备名必须与配置一致。

脚本检查 API 容器与本地配置，然后调用 PlatformIO 编译。如本机没有 `pio`，会在项目 `.ota-tools` 中建立 Python 环境并自动安装 PlatformIO；首次下载编译依赖需要联网。可通过 `--pio /路径/pio` 指定现有工具。代理环境变量会传递给编译工具。

版本号通过编译参数注入，无需手改源码的默认版本号；PSRAM、USB 与原有编译配置保留。生成的 `firmware/.pio/build/e1002/firmware.bin` 会通过容器标准输入传给本地发布工具。工具读取服务现有 `/data/pixel.db`，检查 ESP32-S3 应用标识、镜像摘要、嵌入版本和大小，然后在同一事务中保存版本并指定目标设备。不访问上传或发布接口，也无需另填发布令牌。

编译失败或校验失败时不改变当前发布。同一版本号不能替换成不同内容，也不能发布比设备当前运行或已指定版本更低的版本。编译过程在项目内加锁，避免同一目录的并行任务互相覆盖产物。每台设备的固件含有真实 Wi-Fi 和 API 配置，配置变化或为其他设备编译时也应使用新版本号。勿公开分享构建产物。

## 设备何时更新

设备每次定时或按键唤醒后，先执行原本的上一页、下一页或刷新，再检查新固件。仅安装比当前运行版本更高的版本。估算电量低于 20% 时推迟安装，可连接 USB 电源后按键唤醒。

下载最多约 3 分钟，连续约 15 秒无数据则停止。下载中断、长度不符、SHA-256 或 ESP 镜像验证失败时不切换启动分区，继续运行旧程序；成功后重启，再向服务器上报新运行版本。

查询状态可用现有认证读取接口 `GET /v1/devices/desk-01/firmware`：`release.build` 是发布目标，`running_build` 是最近上报的实际版本，`status` 为 `running` 或 `failed`，`last_seen` 为最近上报时间。只有重启后上报目标版本，才能从服务器确认新程序已启动。

## 撤销待更新版本

同样在 Ubuntu 上执行：

```sh
python3 scripts/release_firmware.py --withdraw
```

撤销默认使用配置中的 DEVICE_ID，也可用 `--device` 指定。撤销不需要编译，也不会卸载已安装的程序。恢复旧行为时，将旧源码以更高版本号重新编译发布，或通过 USB 恢复。本版未启用启动失败自动回滚；新程序自身故障导致无法联网时，可能仍需 USB 恢复。

OTA 沿用可信局域网 HTTP 和 Bearer 认证，SHA-256 负责完整性校验，未实现签名或公网安全发布。底层采用 [Espressif OTA](https://docs.espressif.com/projects/esp-idf/en/v4.4.6/esp32/api-reference/system/ota.html) 的备用应用分区与验证后切换启动。

## 验证范围

本地数据库发布、版本冲突与旧版本拒绝、撤销、设备下载隔离和状态测试通过；脚本编译后发布、编译失败停止发布、设备配置不匹配拒绝和撤销不编译的流程测试通过。用脚本同样的版本注入方式编译成功，并验证实际固件的嵌入版本。Ubuntu 的 Docker Compose 全链路和 E1002 实机 OTA 尚未验证。
