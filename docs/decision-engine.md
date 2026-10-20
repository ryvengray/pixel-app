# Decision Engine（第一版）

Android → 已有 HTTP/HTTPS `POST /v1/chat`（设备 Bearer token）→ Greta gateway → 私有 `POST /decide` → CPU BGE。手机不需要新地址或模型服务密钥，不向公网暴露 18081。手机“助理设置”中的“启用工具快速路由”默认关闭；旧版 App、未启用、未配置服务、超时、模型不可用、低分、歧义均复用原有 DeepSeek 流程和工具。直接路径不调用生成式模型；既有话题异步整理仍可能在后台调用 LLM。

## 路由协议与注册

服务请求：

```json
{"text":"打开打印","available_tools":["open_print","open_sync","list_reminders","create_reminder"]}
```

响应：

```json
{"type":"DIRECT_TOOL","tool":"open_print","score":1.0,"reason":"exact_command"}
```

`server/app/decision_tools.json` 为唯一候选注册表，启动时校验结构和重复名称；修改后重建两个服务。每项有 name、description、examples、target、parameter_policy、direct_phrases。工具的可执行参数 schema 复用 `server/app/main.py` 的 TOOLS / PRINT_TOOL；注册候选并不赋予执行权限。新增功能还必须实现本地固定白名单或服务端执行器，并明确 App 能力声明。

| 策略 | 含义 | 当前工具 |
|---|---|---|
| NONE | 无参数；仅完整命中 direct_phrases 时直接调用。带限定词则回原 LLM，避免忽略条件 | open_sync、open_topics、list_reminders |
| OPTIONAL | 完整无参数指令直接打开；增加任何内容都走 LLM 提取，不用数字正则判断是否有参数 | open_print |
| REQUIRED | 只选工具，复用 DeepSeek 提取参数；缺项应追问，执行前再校验 | create_reminder |

`DIRECT_TOOL`：Android 固定页面动作或服务端只读工具。`TOOL_WITH_LLM`：只向 LLM 暴露命中工具，允许拒绝或追问，不强制生成参数。`LLM_FALLBACK`：继续原有对话。HTTP chat 响应附加 `decision`，UI 动作通过 `client_action` 返回。UI 动作仅在前台、开关开启且当前回复未被新输入替代时执行，依请求 ID 去重。

例：“打开打印”直接打开；“打印这段文字：午饭花了28元”提取 text 到草稿，保留原文；页面仍需用户选择打印机并点击打印。“打印3份某文件”目前无法完整表达文件和份数，模型应追问；未知参数会被拒绝。记账尚未实现，因此没有虚构 add_expense 注册项。

## 保守边界与权限

- BGE 为通用检索模型，相似度不是概率；使用归一化向量内积、默认阈值 0.82、第一二候选差距 0.08。启动一次加载模型与候选向量，单 worker，CPU 4 线程；忙时返回 503，让 gateway 回原 LLM。
- 先检查明确操作意图；“我的打印机坏了”等陈述不会直接路由。短命令完整别名匹配可直接路由；语义匹配主要用于 REQUIRED / OPTIONAL 参数路径。首版保守接受中文输入；疑问、引号、假设、否定、多操作不走直接路径。疑问/说明性输入的 LLM 不提供工具，执行层也拒绝强行返回的调用；取消提醒、多操作与连续补充交还现有上下文 LLM。
- Gateway 会按本地注册表和手机能力重新校验 sidecar 结果。服务端工具使用 JSON Schema 校验必填、类型、枚举和未知字段，提醒继续校验未来时间、时区与标题。一次参数提取最多允许一次成功操作；失败可修正。
- Android 仅允许 open_sync/open_print/open_topics 固定 Activity，校验参数、文本长度，拒绝任意类名、URI、命令。open_sync 只打开同步页面，后续继续复用 Termux 安装和 RUN_COMMAND 权限检查。打印只预填草稿，不提交作业。
- 复用 chat_response 与 chat_checkpoint，重试恢复原计划而不再次路由；路由后的工具结果仍使用现有 operation_id 去重。不改变数据库结构、配对、提醒调度或手机直连 DeepSeek 兜底。
- 相对时间由现有 system_prompt 按 App 提供的 IANA timezone 和当前时间解释，提醒必须是带时区的 ISO 时间。不由 BGE 提取日期，也不依赖服务器本地时区。

## Ubuntu 24.04 部署

在服务器项目根目录执行。需已安装 Docker Engine 与 Compose v2，先按 `server/DEPLOYMENT.md` 配置既有服务的 server/.env、Firebase 文件和 HTTPS/隧道。新增模型服务本身不需要 GPU、CUDA、Redis 或向量库。

### 下载模型（联网一次）

```bash
python3 -m venv .decision-download
.decision-download/bin/pip install 'huggingface_hub==0.28.1'
mkdir -p server/models/bge-small-zh-v1.5
.decision-download/bin/python - <<'PYMODEL'
from huggingface_hub import snapshot_download
snapshot_download('BAAI/bge-small-zh-v1.5', local_dir='server/models/bge-small-zh-v1.5',
                  ignore_patterns=['*.onnx', 'onnx/*', '*.bin', '*.h5', '*.msgpack', 'openvino/*'])
PYMODEL
chmod -R a+rX server/models/bge-small-zh-v1.5
```

下载保留 safetensors、tokenizer 与 Sentence Transformers 配置。离线服务器可在联网电脑下载后复制整个模型目录；镜像构建仍需访问 PyPI 与 PyTorch CPU 仓库。生产环境可把 snapshot_download 的 revision 指定为自己验收过的模型 commit。模型目录已被 Git 与 Docker 构建上下文忽略。

### 启动及验收

```bash
docker compose -f compose.yaml -f compose.decision.yaml config -q
docker compose -f compose.yaml -f compose.decision.yaml up -d --build
docker compose -f compose.yaml -f compose.decision.yaml ps
curl --fail http://127.0.0.1:18081/health
curl --fail http://127.0.0.1:18081/decide -H 'Content-Type: application/json' \
  -d '{"text":"打开打印","available_tools":["open_print"]}'
python3 server/decision/evaluate.py
```

Compose 使用 CPU PyTorch wheel、只读模型挂载、离线加载、非 root 用户、只读文件系统、2 GB 内存限制和回环端口。新服务不作为既有服务的必需依赖，模型加载失败不会阻塞原有对话。首次加载可能超过健康检查启动等待；检查日志和可用内存。

Greta 现有 host 网络通过 `http://127.0.0.1:18081` 访问模型；HTTP 客户端禁用环境代理，避免请求被现有全局代理转发。对外继续使用原有 HTTPS `/gray/v1/chat` 与已配对设备 token；内网开发可沿用 App 现有 HTTP 支持。不要把未经鉴权的 /decide 暴露到公网。公网上的 TLS、配对、反向代理和访问限制沿用既有部署配置。

安装新版 App，助理设置打开快速路由。依次发送“同步一下笔记”“打开打印”“打印这段文字：午饭花了28元”“明天早上八点提醒我交水电费”“如何同步笔记”“为什么天空是蓝色的”。检查页面打开、打印草稿、提醒实际保存、疑问句不执行、普通对话仍正常；断开 sidecar 后确认原有 LLM 回复。手机关闭开关即可立即回原路径。

### 回滚

```bash
docker compose -f compose.yaml -f compose.decision.yaml stop pixel-decision
docker compose -f compose.yaml up -d --build --force-recreate greta-service
```

不删除模型或数据库；不使用 down -v。关闭手机路由开关也可独立停用。

## 测试与准确率校准

### 本地直接测试 BGE（无需 Docker / FastAPI 服务）

在项目根目录执行，使用 Python 3.12。Mac 上按以下步骤安装；模型始终用 CPU：

```bash
python3.12 -m venv .venv-bge
.venv-bge/bin/python -m pip install 'torch==2.6.0' -r server/decision/requirements.txt
.venv-bge/bin/python server/decision/local_test.py
```

首次运行会下载 `BAAI/bge-small-zh-v1.5`，缓存默认放在 Git 忽略的 `server/models/hf-cache`。已有 `server/models/bge-small-zh-v1.5/config.json` 时优先使用该目录。Ubuntu x86_64 安装时，可先使用 CPU 专用索引安装 torch，避免下载 CUDA 依赖：

```bash
.venv-bge/bin/python -m pip install 'torch==2.6.0' --index-url https://download.pytorch.org/whl/cpu
.venv-bge/bin/python -m pip install -r server/decision/requirements.txt
```

逐句输入，例如“打开打印”“打印这段文字：午饭花了28元”“明天早上八点提醒我交水电费”“如何同步笔记”。输入 `/quit` 退出。还可单句、批量或离线运行：

```bash
.venv-bge/bin/python server/decision/local_test.py --text '明天早上八点提醒我交水电费'
.venv-bge/bin/python server/decision/local_test.py --cases
.venv-bge/bin/python server/decision/local_test.py --offline --text '如何同步笔记'
.venv-bge/bin/python server/decision/local_test.py --model /absolute/path/to/bge-small-zh-v1.5 --offline
.venv-bge/bin/python server/decision/local_test.py --cases --threshold 0.80 --margin 0.05
```

输出包括最终 `decision`、原始 BGE 前三候选 `top_candidates`、前两候选差距 `top_margin`。`route_ms` 是实际策略耗时（规则命中可能跳过模型），`diagnostic_embedding_ms` 是另外计算原始分数时的查询向量耗时；这两个值不是端到端服务器延迟。诊断会刻意对疑问句也计算原始分数，以便观察“语义相关但不应执行”的情况。模型加载与工具向量初始化耗时单独显示。

该脚本复用线上 Router，但只打印结果，不连接 DeepSeek、不提取参数、不执行工具。`--cases` 使用已有 20 条语料，出现不符合预期的结果会显示 expected/actual 并以退出码 1 结束；它用来发现真实模型的错误，不保证全部通过。阈值选项只影响本次本地测试，不修改服务配置。

### 原有回归测试与 HTTP 服务验收

```bash
python3 -m venv .test-venv
.test-venv/bin/pip install -r server/requirements.txt
PYTHONPATH=server .test-venv/bin/python -m unittest discover -s tests -p 'test_*.py'
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew :app:compileDevDebugJavaWithJavac
```

`tests/test_decision.py` 使用确定性向量及模拟模型响应验证路由、参数、权限、故障和去重。它不能证明真实 BGE 准确率。`server/decision/cases.json` 与 evaluate.py 是上线前真实模型验收语料：如果参数型请求被过度回退，可按实际正负例调整阈值和样例；不可只降低阈值追求召回率。增加你实际使用的疑问句、非操作意图、相似工具与明确参数样本，观察误执行与错误回退。首版不承诺未经测量的准确率或延迟。

模型使用归一化 CPU 编码，依据 [BGE 模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5) 与 [Sentence Transformers API](https://www.sbert.net/docs/package_reference/sentence_transformer/model.html)。
