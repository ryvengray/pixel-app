# E1002 今日屏幕

Pixel 服务新增 `/ink-screen` 管理页面。输入已配对的 Pixel 设备令牌、水墨屏设备 ID，点击“加载今日内容”，可编辑 JSON、预览并发送。令牌只保存在页面内存中。

服务端环境变量：

```dotenv
INK_API_BASE=http://127.0.0.1:18000
INK_TOKEN=与ink-screen的PIXEL_TOKEN一致
INK_LOCATION=你的城市
INK_LATITUDE=你的纬度
INK_LONGITUDE=你的经度
# Docker 已安装中文字体；其他环境设置字体路径
INK_FONT_PATH=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc
```

天气来自 Open-Meteo，获取当天高低温、降水概率，按温度和降雨提供基础穿衣建议。未配置位置或请求失败时明确显示缺失状态。英语与名言使用本地每日轮换内容。

待办取 Pixel 服务 reminder 表内上海时区今天的 pending 提醒；不包含尚未从 Android 同步的本地日历待办。默认屏幕最多显示四条，页面 JSON 可编辑。

接口均使用 Pixel 原有 Bearer 设备鉴权：

- `GET /v1/ink/today/data`：加载天气、提醒和每日语句。
- `POST /v1/ink/today/preview.png`：Today JSON 转 800×480 PNG。
- `POST /v1/ink/devices/{id}/today`：同一 JSON 渲染并发送。
- `POST /v1/ink/devices/{id}/image`：原始图片 body 转发至 ink-screen。

ink-screen 新增 `POST /v1/devices/{id}/image`，使用原有 PIXEL_TOKEN 鉴权，body 为原始图片字节，不是 multipart。限制 15 MB / 2400 万像素，自动按 EXIF 转向、白底合成、等比留白、六色抖动，打包为原有 192000 字节协议；保留历史、版本哈希和 MQTT 通知，固件无需改动。

页面直接展示服务器生成的 PNG，而非截取浏览器页面：渲染结果固定，不依赖浏览器字体、视口或截图进程。发送成功只代表目标画面已保存，实际显示仍需设备刷新确认。

部署需分别更新两个服务并重建容器。此实现未添加定时刷新，也未配置实际设备地址或令牌。

## 照片与磨砂玻璃布局

管理页面支持选择 8 MB 以内的背景照片。Today JSON 新增 `background` 字段，接受 base64 图片（页面自动填入），最大 2400 万像素。照片自动按 EXIF 方向处理、居中裁切为 800×480；建议主体位于左侧。文字卡片在背景局部模糊后叠加白色半透明蒙层，预先合成为图片，设备无需支持透明或模糊效果。右侧显示天气和最多三条待办、每日英语，左下显示名言。

背景不单独持久保存；发送后合成画面会进入 ink-screen 既有历史记录。网页全彩预览中的细腻玻璃效果在六色屏转换后会简化，需实机验证。

六色屏优化：最终 PNG 仅含协议六色；照片背景使用抖动，卡片单独二值化为纯白底黑字，避免细字被彩色噪点破坏。网页预览即此六色 PNG。未选择照片时使用原生六色几何背景。此规则替代上文全彩玻璃预览说明；保留圆角卡片布局，降低透明质感以优先保证实体屏可读性。

## 宿主机图库与定时轮换

配置示例分为两份：项目根目录 `.env.example` 复制为 `.env`，用于 Compose 的宿主机目录等变量替换；`server/.env.example` 复制为 `server/.env`，用于屏幕地址、令牌、设备 ID 和天气位置。已有 `.env` 请合并新增字段，不要覆盖现有凭据。两个服务同在 Ubuntu 时使用 `INK_API_BASE=http://127.0.0.1:18000`，并保留 `NO_PROXY` / `no_proxy`；屏幕服务在另一台机器时，改为其局域网地址，并将该地址加入两个代理绕过列表。

Compose 默认将 `pixel-app/server/ink-backgrounds/` 挂载到服务的 `/ink-backgrounds`。可直接往此目录放入 JPG、JPEG、PNG、WebP 图片；无需重启。复制大文件建议先使用 `.tmp` 后缀，完成后再改回图片扩展名。无效图片会跳过，符号链接不会读取。

自定义宿主机目录：在项目根目录的 Compose `.env` 设置 `INK_BACKGROUND_HOST_DIR=/你的绝对路径`，然后重建服务。请使用专门的图库目录；初始化容器会将此目录的所有者设为 UID 10001，以便 App 上传。直接添加的照片需允许 UID 10001 读取。

在 `server/.env` 设置 `INK_DEVICE_ID=desk-01`，并配置 `INK_API_BASE` 和 `INK_TOKEN`。每分钟检查上海时区时间，每天 06:00、14:00、20:00 按文件名顺序换下一张，重新加载当天内容并发送。没有有效图片则跳过。一次发送失败会于下一分钟重试，成功后保存时间段和照片名到图库内 `.rotation.sqlite`，重启不会重复发送同一时间段。恢复服务时只补当前已到时间段，不回放全部错过的任务；凌晨 6 点前不补发。仅一张照片时会重复使用它。

网页有“只加入图库”和“立即轮换”按钮。Android 分享图片选择“水墨屏”后，点击“显示到水墨屏并加入背景图库”：上传照片持久保存，再与当天内容合成并发送。需安装本次更新后的 APK。若照片保存成功但屏幕发送失败，照片仍保留在图库中。

接口：`GET /v1/ink/backgrounds` 查看文件名与时间表，`POST /v1/ink/backgrounds` 用原始图片 body 上传，`?display=true` 表示同时显示，`POST /v1/ink/rotate` 手动执行下一张。均使用 Pixel 设备鉴权。网页发送带背景的今日画面也会自动保存背景。

以上持久化行为替代前文“背景不单独保存”的描述。未开启额外 Codex 自动化；定时任务运行在 Pixel FastAPI 服务内，服务必须持续运行。请勿删除图库中的隐藏状态文件，除非需要重置轮换进度。
