# 首页输入与等待反馈

首页保留最新一组用户发言和助理回复。用户消息固定在上方对话区，不再随着软键盘在顶部/底部之间重新挂载。长消息仍可点击展开，较小屏幕上的对话区会限制高度并允许滚动，底部只放输入与最近工具结果。

文字输入使用独立的编辑状态：点击“写点什么…”后立即隐藏语音/闲置提示，键盘出现前收到的隐藏回调不会撤销编辑。只有键盘实际从显示变为隐藏、且没有草稿时，才收起空输入框。已有草稿在关闭键盘后保留；空白消息禁用发送。发送后收起键盘，文字进入上方消息区。IME 动画期间只移动输入栏，动画结束再确认键盘状态，避免中间帧反复切换编辑/语音布局。

输入栏使用 16sp 文字、16dp 左侧内边距、60dp 最小外框高度和独立 44dp 发送按钮。正文去掉系统 EditText 默认背景及额外字体留白，最多自然展开五行，继续输入在框内滚动。浅色/深色均有独立输入底色和细边框。

发送合并等待和 Agent 网络请求期间，宠物进入轻微摆动、抬眼及头顶圆点的思考状态，原有旧回复暂时隐藏，文字状态显示“Greta 正在想…”。回复成功、失败或首页暂停时停止等待反馈；失败保留重试入口。开启“减少动态效果”后不播放循环动效，仍保留静态状态提示。

主要实现：

- `HomeConversationView.java`：编辑状态、上方消息区、自动增高与请求反馈。
- `MainActivity.java`：IME 动画过程的输入栏位置和宠物状态连接。
- `PetScene.java` / `pet.frag`：平滑思考状态与绘制。

使用独立开发版模拟器回归（临时使用模拟器内的本地 HTTP 测试服务，不调用真实 LLM；测试结束恢复设置并删除测试消息）：

```bash
./gradlew :app:assembleDevDebug :app:assembleDevDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk
adb -s emulator-5554 shell settings put secure show_ime_with_hard_keyboard 1
adb -s emulator-5554 shell am instrument -w -e suite home dev.pixel.sync.dev.test/dev.pixel.sync.ToolUiInstrumentation
```

不传 `-e suite home` 可运行原有工具抽屉回归。测试截图写入开发版应用缓存中的 `home-editor.png`、`home-multiline.png`、`home-waiting.png`、`home-idle.png`，可通过 `adb exec-out run-as dev.pixel.sync.dev cat cache/<filename>` 导出。
