# 手机 Agent：由现有大模型逐步决策

此版接入 pixel-app 已有的后台 Agent 和 `deepseek()` 工具调用客户端。
用户在 App 中说“用微信给张三发消息，内容是明天下午回老家”，聊天 Agent
提取保留原文的目标，调用 `start_phone_task`。后台任务每轮观察实际页面，
由同一个已配置的 DeepSeek 模型选择下一步工具，直到完成、停止或超时。
**没有预设微信搜索、选人、输入、发送的固定顺序。**

代码及模拟测试完成，尚未部署或做实机验证。

## 分工及数据路径

```text
App 用户指令
  → 现有聊天 Agent / DeepSeek：启动自然语言手机任务
  → 后台手机 Agent / 同一个 deepseek()：决定下一步
      → observe_screen：ADB 新截图 → 本地 Qwen → 页面描述、文字、控件
      → click_element：Qwen 定位 → ESP32 蓝牙点击 → 新截图验证
      → type_text：ESP32 输入 → 中文输入法选字 → 新截图核对
      → press_key：ESP32 键盘 → 新截图验证
      → verify_screen / wait_screen
  ← 每轮观察和执行结果回到 DeepSeek，由模型选择下一步
  → finish_task：验证完成证据 → 现有消息收件箱和推送
```

截图图片交给本地 Qwen；提取出的页面描述、可见文字和执行结果会作为上下文
传给现有后台大模型。截图中的文字作为不可信观察数据，不作为操作指令。
中文输入中的“拼音 → 候选字 → 核对前缀”是通用输入工具的实现，不是业务导航脚本。

## Agent 工具

现有聊天 Agent 增加三个服务端工具，不需要 Android 新增 client_tool：

| 工具 | 用途 |
|---|---|
| `start_phone_task(goal)` | 创建后台任务，返回任务ID和running状态 |
| `get_phone_task(task_id?)` | 查询指定任务；省略时查询当前/最近任务 |
| `stop_phone_task(task_id?)` | 停止指定或当前任务后续动作 |

后台手机 Agent 每轮仅执行一个工具调用，可选择观察、点击、输入、按键、验证、
等待、完成或停止。模型决定应用、目标控件、输入内容、分支和执行顺序。
发送消息前要求核对唯一收件人、聊天类型和精确草稿，完成前必须截图验证。
`running`仅代表任务启动，不代表已发送；`succeeded`代表视觉模型验证通过，
不代表对方已收取/已读，也不能消除模型误判。

新增视觉接口均通过原有管理员鉴权代理：

- `POST /v1/phone-control/vision/observe`：`{"objective":"当前是什么页面，有哪些相关控件"}`。
- `POST /v1/phone-control/vision/verify`：`{"assertion":"需要验证的完整页面条件"}`。

## 部署准备

沿用 [现有部署](phone-control.md)：8787主服务、8789视觉网关、本地Ollama、
无线ADB及ESP32。主服务需要已有 `DEEPSEEK_API_KEY` 和手机管理员令牌；
保持现有 `DEEPSEEK_MODEL` / `DEEPSEEK_BASE_URL` 配置。

更新代码后重建主服务容器（新增pypinyin依赖），重启视觉网关：

```bash
docker compose up -d --build greta-service
systemctl --user restart pixel-phone-vision
```

不需要更新ESP32固件。手机保持解锁、ESP32在线且ARMED。
中文需要启用手机**全拼输入法**，允许硬件键盘连接时仍显示屏幕键盘和候选栏。
运行期间不要从控制台、手机或另一台电脑同时操作设备。

部署后可以直接在 App 中发出完整指令，再用“手机任务进展怎么样”“停止手机任务”查询或停止。
已有 App 收件箱接收完成/停止通知；任务在服务器后台运行，不占用长时间聊天HTTP请求。
稳定的 `client_message_id` 用于防止聊天网络重试重复创建任务；无请求ID的聊天不能启动手机任务。

## 命令行联调

CLI仅提交自然语言目标，不再包含任何微信业务步骤。安装HTTP客户端：

```bash
python -m pip install -r server/workflow-requirements.txt
```

只打印目标，不连接手机：

```bash
python server/phone_workflow.py --goal '用微信给张三发消息，内容是明天下午回老家'
```

提交后台执行：

```bash
python server/phone_workflow.py --env-file server/.env --execute \
  --goal '用微信给张三发消息，内容是明天下午回老家'
```

先测草稿时，在自然语言目标中明确说“只填写草稿，不发送”。
CLI在提交前打印任务ID；网络响应丢失时用这个ID查询，不能换ID重复提交：

```bash
python server/phone_workflow.py --env-file server/.env --task-id TASK_ID
python server/phone_workflow.py --env-file server/.env --task-id TASK_ID --stop
```

默认地址`http://127.0.0.1:8787`，可用`--server`修改。
可用`--id`指定32..64位小写十六进制幂等ID。
也可调用管理员接口：

- `POST /v1/phone-control/tasks`：`{"id":"32位十六进制ID","goal":"完整目标"}`。
- `GET /v1/phone-control/tasks/{id}`。
- `POST /v1/phone-control/tasks/{id}/stop`。
- `GET /v1/phone-control/tasks/{id}/trace`：管理员查看完整执行日志，支持分页。

## 排查Agent执行路径

任务状态只显示最近12条关键事件。完整路径持久化在`phone_agent_event`，
不会因状态接口只返回最近事件而丢失。每条事件有任务ID、递增seq、时间戳；
每轮有step（从0开始）、tool_call_id；嵌套阶段通过span_id/parent_span_id关联。

日志包含：

- 原始任务和对话来源、规划模型配置名、工具定义和执行限制。
- 模型每轮新增的输入上下文、返回的工具调用及参数、简短decision_summary。
  首轮保存系统提示词，后续增量记录可还原实际传入的消息序列。
- Qwen画面证据、前台窗口、截图版本/时间、目标框和坐标、验证条件与结果。
- ESP32在线/ARMED/蓝牙状态、boot_id、动作ID、提交和回执，中文输入的拼音与已提交前缀。
- 规划、观察、定位、验证、输入提交和回执各阶段的起止及耗时。
- 非法决策、未知回执、任务取消、进程重启、异常类型/消息/堆栈及通知失败。

不记录隐藏推理内容、图片base64或二进制；凭证字段和环境中已配置的令牌会脱敏。
业务文字（联系人、消息、页面文字）保留用于诊断。原图不在此日志中，截图版本用于关联观察。

导出某次任务的完整JSON：

```bash
python server/phone_workflow.py --env-file server/.env \
  --task-id TASK_ID --trace --output phone-task-trace.json
```

导出自动翻页，并固定首个响应的`through_seq`快照，运行中产生的新事件留待下次导出，
避免长任务分页重复或无限追随新日志。文件以0600权限新建，不覆盖已有文件。
不指定`--output`时将JSON输出到终端。

直接访问日志接口可用`after_seq`（默认0）、`limit`（1..1000，默认200）、
`through_seq`（首个响应返回）。响应中的`next_after_seq`用于下一页，
`has_more`为false表示这一快照已读完。日志接口使用原有管理员鉴权，普通App设备令牌不可访问。

主服务容器日志同时输出`phone_agent`结构化索引，含task_id、seq、step、span和阶段耗时：

```bash
docker compose logs --since 1h greta-service | rg 'TASK_ID'
```

先定位最后一个`stage_started`对应的成功/失败事件：
`planner`是后台大模型，`vision.*`是Qwen画面处理，`ble.*`是蓝牙执行链路。
若进程崩溃留下未闭合阶段，重启会追加`task_recovered`并标为unknown，不重新执行。
详细证据和完整堆栈以JSON导出为准；阶段回执成功与业务验证结果分别记录。

## 执行记录与边界

任务、Agent每步决策、目标定位、操作意图及回执保存在现有phone-control数据库
的`phone_agent_task`和`phone_agent_event`表中。记录包含联系人和消息等业务数据，
不保存服务令牌或截图base64。每次操作先记录动作ID再提交，遇到未知回执不重放。
同一数据库只允许一条运行中的手机任务。服务重启将未完成任务标为unknown，
不自动恢复；停止也不能撤回已下发到ESP32的动作。

单任务上限80轮决策、160次底层输入动作、30分钟。输入后要求再次观察再决策；
点击和按键后验证模型指定的可见变化。找不到唯一目标、验证失败、ESP32重启、
未知回执或超时都会停止。模型可根据观察选择处理弹窗等分支；执行结果不确定时
首版停止，不尝试自动纠错重做业务动作。

首版支持点击、有限键盘导航、单行基本汉字和ASCII。HOME/BACK通过Android
键盘快捷键映射，仍需目标手机实测；参考[Google键盘快捷键说明](https://support.google.com/pixel/answer/6326992?hl=en)。
暂不支持任意Unicode、候选翻页、复杂手势、受保护截图和自动解锁。
中文候选不可见、多音字读音不适合、英文停留在组合态等情况会停止。
每轮可能包含DeepSeek、Qwen观察、定位和验证，速度仍待实机测量与优化。

## 测试

```bash
PYTHONPATH=server python -m pytest tests/test_assistant_harness.py tests/test_decision.py \
  tests/test_phone_workflow.py tests/test_phone_vision.py tests/test_phone_bluetooth.py \
  tests/test_phone_control.py tests/test_phone_screen.py
node tests/test_phone_vision_ui.cjs
```

模拟覆盖不同页面证据导致模型选择不同下一步、弹窗分支、通用中文输入、
启动幂等、重启不重放、未知回执停止、无观察拒绝输入、完成证据及App聊天入口。
模拟通过不等于真实模型或手机端已经完成微信任务。
