# 停服维护与更新交接文档

## 当前运行状态

当前项目目录：

```bash
/home/admin/web-homepage
```

当前线上入口：

```text
http://115.28.129.221/
```

运行方式：

- 前端：系统 Nginx 监听 `80`，静态目录为 `/home/admin/web-homepage/front/dist`
- 后端：`web-backen.service` 通过 systemd 运行 Spring Boot JAR，监听 `8080`
- 统一分组：`web-homepage.target` 将 `web-backen.service` 和 `nginx.service` 设为同一组开机启动服务
- 接口代理：Nginx 将 `/api/` 转发到 `http://127.0.0.1:8080`
- 环境变量文件：`/etc/web-homepage/web.env`

Nginx 站点配置：

```bash
/etc/nginx/sites-available/corporate-site
```

旧配置备份：

```bash
/etc/nginx/sites-available/corporate-site.bak-web-homepage
```

## 维护前检查

确认服务和端口：

```bash
ss -lntup
sudo systemctl status nginx
curl -I http://127.0.0.1/
curl -I http://127.0.0.1:8080/
```

备份当前包：

```bash
cd /home/admin
sudo tar -czf web-homepage-backup-$(date +%Y%m%d-%H%M%S).tar.gz web-homepage
sudo cp /etc/web-homepage/web.env /etc/web-homepage/web.env.bak-$(date +%Y%m%d-%H%M%S)
sudo cp /etc/nginx/sites-available/corporate-site /etc/nginx/sites-available/corporate-site.bak-$(date +%Y%m%d-%H%M%S)
```

## 停服步骤

一键停前后端：

```bash
sudo systemctl stop web-homepage.target
```

如果需要分别停止：

```bash
sudo systemctl stop nginx
sudo systemctl stop web-backen.service
```

确认 `80` 和 `8080` 不再监听：

```bash
ss -lntup
```

## 一键重启

如果只改了 `/etc/web-homepage/web.env`，或更新后需要同时重启后端和前端入口，可以执行：

```bash
sudo systemctl restart web-backen.service nginx.service
```

也可以使用仓库内脚本，它会先检查 Nginx 配置再重启两个服务：

```bash
cd /home/admin/web-homepage
sudo ./restart-web-homepage.sh
```

后端日志查看：

```bash
sudo journalctl -u web-backen -f
```

## 更新步骤

替换前端构建产物：

```bash
cd /home/admin/web-homepage
sudo rm -rf front/dist
sudo cp -R /path/to/new/dist front/dist
```

替换后端 JAR：

```bash
sudo cp /path/to/new/backen.jar backen/backen.jar
```

如环境变量有新增字段，先对照模板补齐：

```bash
diff -u web.env.example /etc/web-homepage/web.env
sudo nano /etc/web-homepage/web.env
```

不要把真实密钥写回仓库。

## 启动步骤

启动前后端：

```bash
sudo systemctl start web-homepage.target
```

如果需要分别启动：

```bash
sudo systemctl start web-backen.service
sudo systemctl start nginx
```

修改 Nginx 配置后先检查：

```bash
sudo nginx -t
```

如果 Nginx 已在运行，使用：

```bash
sudo systemctl reload nginx
```

## 验证步骤

本机验证：

```bash
curl -I http://127.0.0.1/
curl -I http://127.0.0.1/api/
ss -lntup
```

公网验证：

```bash
curl --noproxy '*' -I http://115.28.129.221/
```

预期结果：

- `80` 返回 `200 OK`
- `8080` 正在监听
- `/api/` 能被 Nginx 代理到后端，即使根接口返回 `404` 也说明代理已打到后端

## 回滚步骤

如更新失败，恢复备份：

```bash
sudo systemctl stop web-homepage.target
cd /home/admin
sudo rm -rf web-homepage
sudo tar -xzf web-homepage-backup-YYYYMMDD-HHMMSS.tar.gz
sudo cp /etc/web-homepage/web.env.bak-YYYYMMDD-HHMMSS /etc/web-homepage/web.env
sudo cp /etc/nginx/sites-available/corporate-site.bak-YYYYMMDD-HHMMSS /etc/nginx/sites-available/corporate-site
sudo nginx -t
sudo systemctl start web-homepage.target
```

然后按“验证步骤”检查服务。

## 常见问题

外网访问失败但本机正常时，先检查云服务器安全组是否放行 `80/tcp`。如果改用 `3000` 等端口，也需要同时放行 UFW 和云安全组。

后端无法读取配置时，确认环境变量文件存在且权限正确：

```bash
sudo ls -l /etc/web-homepage/web.env
```

正常权限应为 `-rw------- root root`。


## 2026-06-03 文献翻译长任务 SSE 超时修复

### 问题

文献翻译页数较多、耗时较长时，后端日志可能出现：

```text
ResponseBodyEmitter has already completed
```

表现为前端翻译进度流提前断开，但 BabelDOC 翻译任务可能仍在后台继续运行。

### 原因

后端 `/api/translate/stream/{taskId}` 使用 `SseEmitter` 推送翻译进度。旧版本 jar 中 `SseEmitter` 超时硬编码为 `2100000ms`，约 35 分钟。长篇 PDF 翻译超过该时间后，Spring 会先完成 SSE 响应，后台线程后续继续发送进度时就会触发上述异常。

同时旧默认配置中：

```bash
BABELDOC_TIMEOUT_SECONDS=1800
```

BabelDOC 默认超时为 30 分钟，也不适合长篇文献翻译。

### 已调整

- `backen/backen.jar`
  - `SseEmitter` 超时从 `2100000ms` 调整为 `21600000ms`，即 6 小时。
  - jar 内置 `application.yml` 的 `babeldoc.timeout-seconds` 默认值从 `1800` 调整为 `21600`。
- `web.env.example`
  - `BABELDOC_TIMEOUT_SECONDS=21600`
- `deploy/nginx.conf.template`
  - `/api/translate/stream/` 的 `proxy_read_timeout` 调整为 `21600s`。
  - `/api/` 的 `proxy_read_timeout` 调整为 `21600s`。

### 部署注意

如果服务器已经安装过，`install-linux.sh` 会保留已有环境变量文件：

```text
/etc/web-homepage/web.env
```

因此升级后需要手动确认或补充：

```bash
BABELDOC_TIMEOUT_SECONDS=21600
```

然后重启服务：

```bash
sudo systemctl restart web-backen
sudo nginx -t
sudo systemctl reload nginx
```

### 校验

本次已做离线校验：

- jar 内 `SseEmitter` 6 小时常量存在 1 处。
- jar 内 `application.yml` 已包含 `timeout-seconds: ${BABELDOC_TIMEOUT_SECONDS:21600}`。
- `scripts/babeldoc_runner.py` 通过 `python3 -m py_compile` 语法检查。

## 2026-06-03 OpenClaw 页面路由兼容修复

### 问题

OpenClaw 网页界面实际注册在前端路由 `/franchise`，但运维和使用时容易直接访问 `/openclaw`。旧打包产物没有 `/openclaw` 路由别名，直接打开该路径时 Vue Router 无匹配页面，表现为 OpenClaw 界面无法正常进入。

### 已调整

- 线上前端主入口：`/home/admin/web-homepage/front/dist/assets/index-DN465vRW-v20260603.js`
- 发布包前端主入口：`server-upload/web-homepage/front/dist/assets/index-DN465vRW.js`

将 OpenClaw 页面路由从仅支持：

```js
path: "/franchise"
```

调整为同时支持：

```js
path: "/franchise",
alias: "/openclaw"
```

### 验证

```bash
curl -I http://127.0.0.1/openclaw
curl -I http://127.0.0.1/franchise
```

两个路径都应返回前端入口 `index.html`。进入页面后仍需使用 `/etc/web-homepage/web.env` 中配置的 `ADMIN_KEY` 登录。

## 2026-06-03 OpenClaw 页面进入慢诊断与兼容脚本

### 现象

OpenClaw 页面可以进入，但初始化很慢。页面进入后会并发调用会话、模型、指令和历史消息接口；后端每个接口都会启动一次 OpenClaw CLI 进程访问 Gateway。

### 诊断

当前 OpenClaw CLI 版本为 `2026.5.7`，`gateway` 命令已经改为子命令形式：

```bash
openclaw gateway call <method> --json --params '{}'
```

旧后端仍按旧形式调用：

```bash
openclaw gateway --json --params '{"method":"sessions.list","params":{...}}'
```

这会导致 CLI 参数不兼容，并放大每次 CLI 启动成本。`openclaw doctor` 还提示：

- `NODE_COMPILE_CACHE` 未设置，重复启动 CLI 会更慢。
- `OPENCLAW_NO_RESPAWN` 未设置，存在额外自重启开销。
- Gateway probe 显示 capability 为 `read-only`，写操作可能受限。
- `gateway call` 仍可能返回 `1006 abnormal closure`，需要继续检查 OpenClaw Gateway 权限/运行状态。

### 已调整

新增兼容脚本：

```text
/home/admin/web-homepage/backen/scripts/openclaw_compat.sh
```

脚本会把旧参数格式转换为新 CLI：

```bash
openclaw gateway call "$method" --json --params "$params"
```

并设置：

```bash
OPENCLAW_NO_RESPAWN=1
NODE_COMPILE_CACHE=/var/tmp/openclaw-compile-cache
```

### 线上生效步骤

当前 `/etc/web-homepage/web.env` 不会被安装脚本自动覆盖。需要手动确认：

```bash
sudo nano /etc/web-homepage/web.env
```

设置或更新：

```bash
OPENCLAW_COMMAND=./scripts/openclaw_compat.sh
OPENCLAW_NO_RESPAWN=1
NODE_COMPILE_CACHE=/var/tmp/openclaw-compile-cache
```

然后重启：

```bash
sudo systemctl restart web-backen.service
```

### 后续排查

如果仍慢或无法发送消息，继续检查 OpenClaw Gateway 权限：

```bash
openclaw gateway probe
openclaw doctor
openclaw gateway call sessions.list --json --params '{"includeDerivedTitles":true}'
```

如果 `Capability: read-only` 或 `1006 abnormal closure` 仍存在，需要修复 OpenClaw Gateway 本身的写权限/连接能力，而不是前端页面问题。

### 2026-06-03 14:45 最终处理结果

已用 sudo 完成线上修复：

- `/etc/web-homepage/web.env` 已设置：
  - `OPENCLAW_COMMAND=./scripts/openclaw_compat.sh`
  - `OPENCLAW_NO_RESPAWN=1`
  - `NODE_COMPILE_CACHE=/var/tmp/openclaw-compile-cache`
- `/home/admin/.config/systemd/user/openclaw-gateway.service` 已加入 CLI 启动优化环境变量。
- 已运行 `openclaw doctor --fix`。
- 已批准本机 OpenClaw device 的 `operator.pairing` 和 `operator.write` scope。
- `openclaw gateway probe` 已从 `Capability: read-only` 变为 `Capability: write-capable`。
- `web-backen.service`、`nginx.service`、`openclaw-gateway.service` 均为 active。

接口验证：

```text
POST /api/openclaw/sessions -> 200，约 3.1s
GET  /api/openclaw/sessions -> 200，约 3.3s
```

说明：OpenClaw 页面现在应能正常进入并新建对话。页面仍可能有数秒初始化时间，主要来自 OpenClaw CLI 进程启动和 Gateway RPC 本身；后续若要进一步优化，应在后端改为长连接/进程内 Gateway client，而不是每个 HTTP 请求都启动一次 CLI。

