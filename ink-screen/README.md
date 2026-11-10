# Ink Screen

此项目已合入 `pixel-app` 的 `ink-screen/` 子目录，与 Android 应用、手机控制服务一起维护。
原仓库提交历史已保留；无需初始化子模块或继续向原仓库提交。

从 `pixel-app` 仓库根目录运行：

```bash
# 构建 ESP32 蓝牙键鼠控制器（不烧录）
pio run -d ink-screen/esp32-screen/firmware

# 构建 E1002（先配置本机 firmware/include/config.h）
pio run -d ink-screen/pixel-display-e1002/firmware

# E1002 服务端、OTA 发布脚本测试，需安装对应依赖
cd ink-screen/pixel-display-e1002
python3 -m pytest tests
```

各子项目文档中的相对路径均以对应子项目目录为起点。
Python 测试请在 E1002 目录单独运行，避免与 Pixel 后端的同名 `app` 模块混用。
`.env`、`firmware/include/config.h`、出厂备份和构建产物继续由 Git 忽略。
本次迁移已复制本机配置与 ESP32 备份；`.pio` 和 Python 缓存可重新生成。

服务器现有部署目录和容器不因代码迁移自动改变。后续切换部署目录时，
保留原有 `.env` 和 Compose 项目名，以沿用已有数据卷；不要重新初始化数据卷。

基于 Seeed reTerminal E1002 六色电子纸的显示项目。

服务端部署、固件烧录与接口说明见 [E1002 项目文档](pixel-display-e1002/README.md)。

首次使用请看 [E1002 操作手册](pixel-display-e1002/操作手册.md)。


ESP32蓝牙键鼠控制器、固件烧录和配置说明见 [ESP32控制器文档](esp32-screen/README.md)。
该控制器配合同仓库服务器的本地视觉模型，实现手机目标识别与蓝牙坐标点击。
