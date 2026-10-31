# E1002 今日屏幕

Pixel 服务新增 `/ink-screen` 管理页面。输入已配对的 Pixel 设备令牌、水墨屏设备 ID，点击“加载今日内容”，可编辑 JSON、预览并发送。令牌只保存在页面内存中。

服务端环境变量：

```dotenv
INK_API_BASE=http://ink-api:18000
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
