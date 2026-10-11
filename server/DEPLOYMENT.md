# Gray 服务端部署指南

Gray 运行在本地电脑的 Docker Compose 中。公网 HTTPS 由阿里云的 Nginx 提供，frp 只负责将 Nginx 的本地上游端口转到本地电脑。对手机公开的所有 Gray 接口都在 `/gray/` context 下。

```text
Pixel App
  └─ HTTPS https://assistant.example.com/gray/
       └─ Aliyun Nginx
            └─ 127.0.0.1:17878（frps 的 Gray 专用端口）
                 └─ frpc 隧道
                      └─ 本地电脑 127.0.0.1:8787（Docker Compose）
```

## 1. 准备私有配置

在仓库根目录执行：

```bash
cp server/.env.example server/.env
```

编辑 `server/.env`：

```dotenv
GRAY_PUBLIC_BASE_URL=https://assistant.example.com/gray
DEEPSEEK_API_KEY=你的DeepSeek密钥
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
DATABASE_PATH=./data/gray.db
FIREBASE_SERVICE_ACCOUNT_FILE=
```

`GRAY_PUBLIC_BASE_URL` 必须和 Nginx 的真实 HTTPS 地址及 context 完全一致，且不以 `/` 结尾。`.env` 不提交 Git。

## 2. 启动本地 Docker 服务

确保 Docker Desktop 正在运行，然后在仓库根目录执行：

```bash
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8787/health
```

期望最后一条命令返回：

```json
{"status":"ok"}
```

服务仅监听本地电脑的 `127.0.0.1:8787`。数据库位于 `server/data/gray.db`，更新镜像或重启容器不会删除它。

## 3. 配置 frpc

在本地电脑的 frpc 配置中创建 Gray 的独立 TCP 代理。以下为示意；字段名称按你的 frp 版本调整：

```toml
[[proxies]]
name = "gray"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8787
remotePort = 17878
```

重启 frpc 后，阿里云机器上的 `127.0.0.1:17878` 应可访问 Gray。不要在云防火墙或安全组中向公网开放 `17878`；公网入口只保留 Nginx 的 `443`。

## 4. 配置阿里云 Nginx

将 [gray.context.conf.template](nginx/gray.context.conf.template) 中的 `FRP_REMOTE_PORT` 替换为实际值，并将内容加入已有的 HTTPS `server {}` 块。这个配置使用 `/gray/` context，并在转发到 FastAPI 前去掉该前缀。

检查并重载 Nginx：

```bash
nginx -t
systemctl reload nginx
curl https://assistant.example.com/gray/health
```

返回 `{"status":"ok"}` 即表示整条公网链路可用。Nginx 证书可以沿用该域名已有的证书；App 不接受 HTTP 或无效证书。

## 5. 扫码配对 Pixel

在本地电脑的项目根目录运行：

```bash
docker compose exec gray-service python -m app.pairing
```

终端会显示一个十分钟有效、只能使用一次的二维码。打开 Gray 的“助理设置”，选择“扫码配对这台设备”后扫描。二维码中含有 `https://assistant.example.com/gray/v1/...`，所以 App 自动保存带 `/gray` 的服务地址。

## 日常维护

```bash
# 查看服务状态和日志
docker compose ps
docker compose logs -f gray-service

# 更新代码后的重建与重启
docker compose up -d --build

# 停止服务；不会删除 server/data/ 中的数据库
docker compose down
```

备份时复制 `server/data/gray.db` 到加密且可信的位置。恢复时先停止容器，再还原该文件并启动容器。
