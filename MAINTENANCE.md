# 线上维护与更新交接

## 当前线上状态

- 项目目录：`/home/admin/web-homepage`
- 公网入口：`http://115.28.129.221/`
- 前端：Nginx 监听 `80`，静态目录为 `/home/admin/web-homepage/front/dist`
- 后端：`web-backen.service` 通过 systemd 运行 Spring Boot JAR，监听 `8080`
- 环境变量：`/etc/web-homepage/web.env`
- Nginx 站点配置：`/etc/nginx/sites-available/corporate-site`

真实密钥只保存在服务器环境变量文件中，不要写回仓库。

## 本地一键部署

本地测试和迭代完成后，在项目根目录运行：

```bash
./deploy/deploy-server-improved.sh
```

脚本默认部署到当前线上目录，自动生成发布包、上传、备份旧应用目录、安装并检查后端健康接口与公网首页。它不会上传 `.env.local`，也不会覆盖服务器上的 `/etc/web-homepage/web.env`。

部署参数需要调整时复制 `.deploy.local.example` 为 `.deploy.local`。该文件已被 gitignore，不要在其中保存密码或 API Key。

## 常用维护命令

```bash
sudo systemctl status nginx web-backen.service
sudo journalctl -u web-backen -f
sudo nginx -t
sudo systemctl restart web-backen.service nginx.service
curl -I http://127.0.0.1/
curl -I http://127.0.0.1/api/
ss -lntup
```

更新前建议备份当前部署目录、环境变量和 Nginx 配置：

```bash
cd /home/admin
sudo tar -czf web-homepage-backup-$(date +%Y%m%d-%H%M%S).tar.gz web-homepage
sudo cp /etc/web-homepage/web.env /etc/web-homepage/web.env.bak-$(date +%Y%m%d-%H%M%S)
sudo cp /etc/nginx/sites-available/corporate-site /etc/nginx/sites-available/corporate-site.bak-$(date +%Y%m%d-%H%M%S)
```

## 2026-06-03 已同步的线上修复

### PDF 翻译后台队列与内存控制

生产服务器为 2 核 / 4 GB，PDF 翻译现在采用单 worker 有界队列，同一时间只运行一个 BabelDOC 子进程。输入 PDF、纯中文 PDF、双语 PDF 和最近任务元数据保存在：

```text
/home/admin/web-homepage/.run/translation-tasks/
```

推荐环境变量：

```bash
TRANSLATION_STORAGE_DIR=../.run/translation-tasks
TRANSLATION_MAX_HISTORY=5
TRANSLATION_QUEUE_CAPACITY=5
TRANSLATION_MAX_QPS=4
BABELDOC_QPS=4
```

部署模板将 Spring Boot JVM 堆限制为 `-Xmx768m`，BabelDOC 日志只保留最近 64 KB。升级已有服务器时，需要重新安装 systemd service 模板并执行：

```bash
sudo systemctl daemon-reload
sudo systemctl restart web-backen.service
```

翻译任务不依赖 `jdbc:h2:mem:webdb`。任务元数据和 PDF 文件均保存在上述目录；后端重启后，未完成任务会重新进入单 worker 队列并从头翻译。

### 长篇 PDF 翻译超时

长篇翻译可能超过旧版 30 至 35 分钟限制，导致 SSE 提前断开并出现：

```text
ResponseBodyEmitter has already completed
```

现在源码和部署模板统一使用 6 小时：

- 后端 `SseEmitter`：`21600000ms`
- `BABELDOC_TIMEOUT_SECONDS=21600`
- Nginx `proxy_read_timeout 21600s`

服务器已有 `/etc/web-homepage/web.env` 不会被安装脚本覆盖，升级后需手动确认该值。

### OpenClaw 路由与 CLI 兼容

- OpenClaw 页面同时支持 `/franchise` 和 `/openclaw`
- 新增 `backen/scripts/openclaw_compat.sh`
- 推荐环境变量：

```bash
OPENCLAW_COMMAND=./scripts/openclaw_compat.sh
OPENCLAW_NO_RESPAWN=1
NODE_COMPILE_CACHE=/var/tmp/openclaw-compile-cache
OPENCLAW_MAX_CONCURRENT_CALLS=2
OPENCLAW_HISTORY_CACHE_MILLIS=1000
OPENCLAW_SESSIONS_CACHE_MILLIS=5000
OPENCLAW_MODELS_CACHE_MILLIS=60000
OPENCLAW_COMMANDS_CACHE_MILLIS=300000
```

兼容脚本会把旧参数格式转换为新版 CLI 的：

```bash
openclaw gateway call <method> --json --params '{}'
```

如果页面仍慢或无法发送，检查 Gateway 权限与连接：

```bash
openclaw gateway probe
openclaw doctor
openclaw gateway call sessions.list --json --params '{"includeDerivedTitles":true}'
```

线上已批准本机 OpenClaw device 的写权限，`gateway probe` 从 `read-only` 变为 `write-capable`。缓存未命中的单次接口仍可能需要约 1 秒，主要原因是后端需要启动 CLI 进程；进一步优化需要改为长连接或进程内 Gateway client。

### OpenClaw 页面性能优化

实测每次 `openclaw gateway call` 冷启动约需 1 秒 CPU 时间，历史响应约几十 KB，200 Mbps 峰值带宽不是瓶颈。2 核服务器同时启动多个 Node CLI 会明显拖慢页面。

当前后端采用：

- 最多同时运行 2 个 CLI
- 相同只读请求合并，避免并发启动重复 CLI
- 历史缓存 1 秒、会话缓存 5 秒、模型缓存 60 秒、指令缓存 5 分钟
- Spring 启动后后台预热模型、指令和会话缓存
- 写操作后主动失效相关缓存

本机站内代理实测：

```text
sessions 冷调用约 1.56s，热缓存约 2ms
history  冷调用约 0.94s，热缓存约 2ms
models / commands 预热后约 2-4ms
```

页面空闲历史轮询已从 2 秒调整为 5 秒。若仍需进一步降低首屏冷启动延迟，下一步应改为后端长连接或进程内 Gateway client，而不是增加服务器带宽。
