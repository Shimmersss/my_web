# Linux 一键部署包

本目录提供生产部署流程：本机打包，Linux 服务器解压后一键安装。

## 1. 本机打包

在项目根目录运行：

```bash
./deploy/build-release.sh
```

产物会生成在 `.release/web-homepage-时间戳.tar.gz`。

## 2. 上传到服务器并安装

```bash
tar -xzf web-homepage-*.tar.gz
cd web-homepage
sudo DOMAIN=your.domain.com ./install-linux.sh
```

如果暂时没有域名，可以不传 `DOMAIN`，Nginx 会用默认站点名 `_`。

## 3. 填写环境变量

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

## 4. 常用命令

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
- 如果使用 OpenClaw 网页对话：OpenClaw CLI 和本机 Gateway

Ubuntu/Debian 可先装基础依赖：

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx curl
```
