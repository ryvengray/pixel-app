# 在新电脑测试识别速度

网关每次识别成功后，会覆盖保存最后一份样本到运行用户的
`~/.local/share/pixel-vision/benchmark/latest.json`。样本包含实际送给模型的 JPEG、
目标提示词、模型参数、原始模型结果、原图尺寸和服务器耗时，不包含服务令牌。
这是模型输出的参考记录，不是人工标注的正确答案。样本含手机屏幕内容，请按私人数据保存。

将 `latest.json` 和 `scripts/benchmark-vision.py` 放在新电脑同一个目录。
安装并启动 Ollama，安装 Python 3，然后运行：

```bash
ollama pull qwen3-vl:2b-instruct
python3 benchmark-vision.py latest.json
```

Windows 可将 `python3` 换成 `python`。测试脚本仅用 Python 标准库，无需安装依赖，
无需手机、ADB 或业务服务。默认单独记录一次预热，然后串行测试五次。
第一条预热记录不一定是真正冷启动；若要测试冷加载，先用 `ollama stop qwen3-vl:2b-instruct`
卸载已加载模型。模型下载时间不计入测试。

```bash
python3 benchmark-vision.py latest.json --runs 10 --output new-machine.json
ollama ps
```

重复同一图片可能复用模型缓存，因此默认结果只代表这份固定样本的热运行性能，
不能直接推断每张新截图的速度。可另跑下面这组测试：每次请求前卸载模型，
避免跨请求缓存，同时会包含重新加载模型的成本。报告分别记录加载耗时。

```bash
python3 benchmark-vision.py latest.json --reload-model --warmup 0 --runs 3 --output reload-report.json
```

终端输出每次请求耗时、结果与均值、中位数、P95（最近秩法）以及低于3秒的次数。
JSON 报告还记录模型加载、提示词处理和生成耗时、Ollama 版本、模型摘要、CPU/GPU
加载信息（来自 `/api/ps`）和旧服务器结果。请确认 `ollama ps` 显示 GPU，
并对照报告中的模型摘要，确保比较的是同一权重。

这里测量的是完整模型 HTTP 请求，不包含手机截图和图片预处理，所以模型耗时低于3秒
不代表网页操作全程低于3秒。网关基准 `elapsed_seconds` 包含截图与预处理，比较时需区分。
`valid_result` 仅检查返回格式和坐标范围，不能证明识别正确。
样本请求保留原来的6线程、4096上下文、180输出token、最长边1280的图片；
可用 `--threads 线程数` 或 `--model 模型名` 做额外实验，报告会记录修改后的参数。

也可通过 `--url http://127.0.0.1:11434` 指定 Ollama 地址。
默认连接本机服务；如果指定远程地址，样本中的截图和提示词会发送到该地址。
