# Gray Android

Gray Android 安装包及更新日志。此仓库用于分发安装包，源码单独维护。

## 安装

需要 Android 12 或更新版本。到 [Releases](https://github.com/OWNER/REPOSITORY/releases/latest) 下载 `.apk` 并安装，按系统提示允许安装应用。

## 使用 Obtainium 更新

在 Obtainium 中选择“添加应用”，输入：

```
https://github.com/OWNER/REPOSITORY
```

保存后即可检查和下载新版本。每个正式版本只有一个通用 APK；`SHA256SUMS` 可用于校验下载文件。

应用持续使用相同发布签名，后续版本可覆盖升级。此前若安装了不同签名的调试版本，需要先卸载。
