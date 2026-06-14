# 线上维护与更新交接

## 当前线上状态

- 项目目录：`/home/admin/web-homepage`
- 公网入口：`https://shimmer.help/`
- 域名：`shimmer.help`、`www.shimmer.help`，HTTP 自动跳转 HTTPS
- SSL：Let's Encrypt / Certbot，证书目录 `/etc/letsencrypt/live/shimmer.help/`
- 前端：Nginx 监听 `80/443`，静态目录为 `/home/admin/web-homepage/front/dist`
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

已有 `/etc/nginx/sites-available/corporate-site` 会被部署脚本保留，避免覆盖 Certbot 管理的 SSL 配置。不要在普通发布时传 `FORCE_NGINX_CONFIG=1`。

部署参数需要调整时复制 `.deploy.local.example` 为 `.deploy.local`。该文件已被 gitignore，不要在其中保存密码或 API Key。

## 常用维护命令

```bash
sudo systemctl status nginx web-backen.service
sudo journalctl -u web-backen -f
sudo nginx -t
sudo systemctl restart web-backen.service nginx.service
curl -I -H 'Host: shimmer.help' http://127.0.0.1/
curl -I http://127.0.0.1/api/
curl -I https://shimmer.help/
ss -lntup
```

服务器本机检查首页时必须带 `Host: shimmer.help`。裸 `curl -I http://127.0.0.1/` 可能被 Nginx 匹配到其它默认站点或带访问限制的 server block，返回 403；这不代表公网 `https://shimmer.help/` 异常。后端健康接口仍可直接检查 `http://127.0.0.1:8080/api/health`。

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

## 2026-06-06 已同步的线上修复

### 生产 API 同源配置

前端生产 API 基址已从示例地址 `https://api.example.com` 修正为同源 `/api`。浏览器现在只请求 `https://shimmer.help/api/*`，由 Nginx 转发到后端，避免跨域请求失败。

发布脚本构建前会显式设置 `VITE_API_BASE_URL=/api`，并拒绝打包仍包含 `api.example.com` 的前端产物。

### 最近翻译记录

- `/api/translate/recent` 只返回已经提交翻译的任务；仅上传但尚未点击“开始翻译”的 `preview` 任务不再显示，也不会挤掉完成结果的历史名额。
- 最近翻译和结果文件默认保留 5 条，完成结果在后端重启后仍可恢复。
- 前端最近记录请求禁用缓存；加载失败时保留上一次成功数据并显示错误，不再误显示“暂无翻译记录”。
- 2026-06-06 线上验证：公网接口返回 `200`，列表返回 3 条真实已完成任务。

### PDF 上传配置页即时切换

选择 PDF 后，前端立即进入页数与翻译选项配置页，不再等待整个文件上传和 PDFBox 页数读取完成后才切换。

上传解析期间页面显示“正在上传并读取 PDF 页数”，页码、字体、速度和开始翻译按钮保持禁用；服务器返回 `taskId` 和总页数后自动解锁。这样不会改变后台存储和翻译队列逻辑，只改善上传阶段反馈。

### Markdown 同步检查

项目规定功能、接口、配置、部署流程或线上行为有变化时，必须同步更新 `AGENTS.md` 或 `MAINTENANCE.md`。一键部署脚本默认启用 `REQUIRE_DOCS_SYNC=1`：检测到代码改动但没有 Markdown 改动时会停止部署。

发布包会携带 `AGENTS.md` 和 `MAINTENANCE.md`，服务器安装脚本会将它们同步到 `/home/admin/web-homepage/`。此前安装脚本没有复制 Markdown，导致服务器目录长期停留在 2026-06-03 的旧文档；该遗漏已修复。

### PDF 翻译服务器保护

2026-06-06 排查到一次 `666.pdf` 全 18 页翻译导致服务器在 20:06 左右重启，任务恢复后最终因 `BABELDOC_TIMEOUT_SECONDS=1800` 超时失败。未在内核日志中看到明确 OOM kill 记录，但生产机无 swap，且 systemd 只限制 JVM 堆、不限制 BabelDOC/Python 子进程，是主要风险点。

已采用稳妥优先配置：

```bash
BABELDOC_TIMEOUT_SECONDS=21600
TRANSLATION_MAX_QPS=4
TRANSLATION_STABLE_QPS=2
BABELDOC_QPS=2
BABELDOC_RESOURCE_CGROUP_LIMIT_MIB=2600
BABELDOC_RESOURCE_MIN_AVAILABLE_MIB=400
BABELDOC_RESOURCE_MAX_SWAP_USED_MIB=1200
```

加速模式仍允许提交 4 QPS。后端会在 BabelDOC 运行期间监控 cgroup 内存、系统可用内存和 swap 使用量；如果接近风险阈值，会终止当前 BabelDOC 进程树，并用同一任务自动按 2 QPS 稳定模式重试一次。稳定模式仍触发资源风险时任务失败，用户需要缩小页码范围。

2026-06-06 23:34 线上验证发现原阈值把 `MemoryHigh=2200M` 当作终止点过于保守：1 页翻译在加速模式触发 `服务内存 2200 MiB` 后降级，稳定模式又在 `2199 MiB` 被终止。`MemoryHigh` 是 systemd 软限制/节流点，不是硬 OOM 边界；保护阈值已放宽到 cgroup `2600 MiB`，仍低于 `MemoryMax=2800M`。

`web-backen.service` 已同时限制整个服务 cgroup：

```ini
MemoryAccounting=yes
MemoryHigh=2200M
MemoryMax=2800M
TasksMax=256
```

生产机已启用 2 GB `/swapfile`，`vm.swappiness=10`。验证命令：

```bash
free -h
swapon --show
cat /proc/sys/vm/swappiness
systemctl show web-backen.service -p MemoryHigh -p MemoryMax -p MemoryCurrent -p TasksMax
curl -fsS http://127.0.0.1:8080/api/health
```

Zotero 文献缓存只保存在 JVM 内存，不写入磁盘；附件代理按请求临时拉取，不持久化。硬盘增长重点是：

- `/home/admin/web-homepage/.run/translation-tasks/`：翻译任务文件，`preview` 和 `completed/error` 各默认保留 5 条，超出后删除整个任务目录。
- `/home/admin/.web-homepage-releases/`：部署发布包和备份包，一键部署后默认各保留最近 3 个。

2026-06-06 排查时目录大小：翻译任务约 `130M`，OpenClaw 编译缓存约 `46M`，release/backup 目录约 `3.6G`。按最近 3 个发布包 + 最近 3 个备份包清理后，release/backup 目录约 `1.3G`。
