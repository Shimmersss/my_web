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
6. 检查后端健康接口、Nginx 和公网首页

脚本不会上传 `.env.local`，也不会覆盖服务器上的 `/etc/web-homepage/web.env`。

SSH 登录建议使用密钥或 `ssh-agent`。脚本执行远程安装时，`sudo` 可能要求输入服务器密码。

本地部署前测试应使用 Java 17。macOS Homebrew 安装了 `/opt/homebrew/opt/openjdk@17` 时，脚本会在未设置 `JAVA_HOME` 的情况下自动使用它。

需要修改服务器参数时，复制本地配置模板：

```bash
cp .deploy.local.example .deploy.local
```

`.deploy.local` 已被 gitignore，可修改主机、用户、端口、安装目录和公网地址，不要在其中保存密码或 API Key。

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
```

2 核 / 4 GB 服务器默认只运行一个 BabelDOC 翻译任务，其他任务进入后台队列；PDF 结果落盘保存，Spring Boot JVM 堆由 systemd 模板限制为 768 MB。

翻译任务不使用 H2 数据库。后端重启后会从磁盘任务目录恢复最近记录，未完成任务只要原始 PDF 仍在就会重新排队并从头翻译。

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
