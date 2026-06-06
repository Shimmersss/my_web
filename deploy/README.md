# Linux 一键部署

本目录提供两种生产部署流程：

- 推荐：本机完成测试、打包、上传、服务器备份、安装和健康检查
- 手动：只生成发布包，再自行上传和安装

## 推荐：本机一键部署

在项目根目录运行：

```bash
./deploy/deploy-server-improved.sh
```

默认目标是当前生产服务器：

```text
admin@115.28.129.221
/home/admin/web-homepage
```

脚本会依次执行：

1. 后端测试和前端构建验证
2. 生成 `.release/web-homepage-时间戳.tar.gz`
3. 上传到服务器的 `/home/admin/.web-homepage-releases/`
4. 备份当前应用目录
5. 调用服务器安装脚本，重启 systemd 服务并刷新 Nginx
6. 从服务器本机检查后端健康接口、Nginx 和公网首页

服务器本机 Nginx 验证会显式使用 `Host: shimmer.help`，避免 `127.0.0.1` 命中其他虚拟主机或访问控制规则。

脚本不会上传 `.env.local`，也不会覆盖服务器上的 `/etc/web-homepage/web.env`。

脚本默认启用 `REQUIRE_DOCS_SYNC=1`。如果检测到 `backen/`、`front/`、`deploy/` 或 `project.sh` 有改动，但没有同步修改 Markdown 文档，会停止部署。功能和线上行为更新应记录到根目录 `AGENTS.md` 或 `MAINTENANCE.md`；只有明确不需要记录的特殊发布才临时设置 `REQUIRE_DOCS_SYNC=0`。

服务器已有 Nginx 站点配置时，安装脚本默认保留该文件，避免覆盖 Certbot 写入的 HTTPS、证书路径和 HTTP 跳转配置。只有首次安装或明确传入 `FORCE_NGINX_CONFIG=1` 时才从模板重建 Nginx 配置。

`FORCE_NGINX_CONFIG=1` 会清除现有 Certbot SSL 配置，普通发布不要使用。

SSH 登录建议使用密钥或 `ssh-agent`。脚本执行远程安装时，`sudo` 可能要求输入服务器密码。

本地部署前测试应使用 Java 17。macOS Homebrew 安装了 `/opt/homebrew/opt/openjdk@17` 时，脚本会在未设置 `JAVA_HOME` 的情况下自动使用它。

需要修改服务器参数时，复制本地配置模板：

```bash
cp .deploy.local.example .deploy.local
```

`.deploy.local` 已被 gitignore，可修改主机、用户、端口、安装目录、域名和公网地址。当前公网配置应为：

```bash
DOMAIN=shimmer.help
PUBLIC_URL=https://shimmer.help/
```

不要在其中保存密码或 API Key。

如果服务器要求 SSH 私钥，在 `.deploy.local` 中填写私钥的绝对路径：

```bash
DEPLOY_IDENTITY_FILE=/Users/your-name/.ssh/server-private-key
```

脚本会同时把该私钥用于 `ssh` 和 `scp`。私钥文件本身不要放进项目目录或提交到仓库。

常用选项：

```bash
DRY_RUN=1 ./deploy/deploy-server-improved.sh       # 只打印流程，不连接服务器
RUN_TESTS=0 ./deploy/deploy-server-improved.sh     # 已经完整验收过时跳过重复测试
REQUIRE_CLEAN=1 ./deploy/deploy-server-improved.sh # 只允许从干净工作区部署
```

旧入口 `./deploy/deploy-server.sh` 会转发到增强版脚本，保留兼容性。

## 手动：本机打包

在项目根目录运行：

```bash
./deploy/build-release.sh
```

产物会生成在 `.release/web-homepage-时间戳.tar.gz`。

## 手动：上传到服务器并安装

```bash
tar -xzf web-homepage-*.tar.gz
cd web-homepage
sudo DOMAIN=your.domain.com ./install-linux.sh
```

如果暂时没有域名，可以不传 `DOMAIN`，Nginx 会用默认站点名 `_`。

## 填写环境变量

首次安装会生成：

```bash
/etc/web-homepage/web.env
```

至少填写：

```bash
ZOTERO_API_KEY=
ZOTERO_USER_ID=
ADMIN_KEY=
```

如果需要 PDF 翻译和 OpenClaw，再补齐 BabelDOC/OpenClaw 相关配置。改完后重启后端：

```bash
sudo systemctl restart web-backen
```

长篇 PDF 翻译可能持续数小时，默认部署配置已把 BabelDOC、后端 SSE 和 Nginx 读取超时对齐到 6 小时：

```bash
BABELDOC_TIMEOUT_SECONDS=21600
TRANSLATION_MAX_HISTORY=5
TRANSLATION_QUEUE_CAPACITY=5
TRANSLATION_MAX_QPS=4
TRANSLATION_STABLE_QPS=2
BABELDOC_QPS=2
BABELDOC_RESOURCE_CGROUP_LIMIT_MIB=2600
BABELDOC_RESOURCE_MIN_AVAILABLE_MIB=400
BABELDOC_RESOURCE_MAX_SWAP_USED_MIB=1200
```

2 核 / 4 GB 服务器默认只运行一个 BabelDOC 翻译任务，其他任务进入后台队列；PDF 结果落盘保存。加速模式允许 4 QPS，但后端会监控 cgroup 内存、系统可用内存和 swap 使用量；接近风险阈值时会终止当前 BabelDOC 进程树，并用 2 QPS 稳定模式自动重试一次。Spring Boot JVM 堆由 systemd 模板限制为 768 MB，整个后端服务 cgroup 通过 `MemoryHigh=2200M`、`MemoryMax=2800M`、`TasksMax=256` 限制，BabelDOC/Python 子进程也受该上限保护。`MemoryHigh` 是软限制/节流点，不作为自动终止阈值；默认到 cgroup 2600 MiB 才触发保护，仍低于 2800 MiB 硬上限。生产机应启用 2 GB swap，并设置 `vm.swappiness=10` 作为内存峰值缓冲。

翻译任务不使用 H2 数据库。后端重启后会从磁盘任务目录恢复最近记录，未完成任务只要原始 PDF 仍在就会重新排队并从头翻译。

部署完成后，脚本会清理远端 `/home/admin/.web-homepage-releases/` 的旧发布包，默认只保留最近 3 个 `web-homepage-*.tar.gz` 和最近 3 个 `web-homepage-backup-*.tar.gz`。需要调整保留数量时设置 `REMOTE_RELEASE_KEEP`。

## 常用命令

```bash
sudo systemctl status web-backen
sudo journalctl -u web-backen -f
sudo nginx -t
sudo systemctl reload nginx
```

## 服务器依赖

- Java 17+
- Nginx
- 如果使用 PDF 翻译：`uv`
- 如果使用 OpenClaw 网页对话：OpenClaw CLI、本机 Gateway 和 `python3`

Ubuntu/Debian 可先装基础依赖：

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx curl python3
```
