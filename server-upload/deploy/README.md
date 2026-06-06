# Linux 一键部署包

本目录提供生产部署流程：本机打包，Linux 服务器解压后一键安装。

默认策略：安装脚本只更新应用文件和后端 systemd 服务，不主动改动正在运行的 Nginx 站点。已有 Nginx 时，手动把 `/api` 反代和前端静态目录合并进原来的 `server { ... }`，再执行 `nginx -t` 和 `reload`。

## 1. 本机打包

在项目根目录运行：

```bash
./deploy/build-release.sh
```

产物会生成在 `.release/web-homepage-时间戳.tar.gz`。

如果当前机器访问 npm registry 不稳定，但 `front/dist` 已经是最新构建产物，可以复用现有静态文件：

```bash
SKIP_FRONTEND_BUILD=1 ./deploy/build-release.sh
```

## 2. 上传到服务器并安装

```bash
tar -xzf web-homepage-*.tar.gz
cd web-homepage
sudo ./install-linux.sh
```

脚本会安装/更新：

- `/opt/web-homepage/backen/backen.jar`
- `/opt/web-homepage/front/dist`
- `/etc/systemd/system/web-backen.service`
- `/etc/web-homepage/web.env`，仅首次创建，后续不会覆盖

脚本默认不会改 Nginx。它会额外生成一份参考配置：

```bash
/opt/web-homepage/nginx-web-homepage.conf
```

## 3. 配置已有 Nginx

如果服务器上 Nginx 已经在跑，把下面配置合并到原有域名的 `server { ... }` 里。`root` 路径固定为：

```nginx
root /opt/web-homepage/front/dist;
index index.html;

client_max_body_size 60m;

location /api/translate/stream/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}

location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

改完先检查，再平滑生效：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

不要为了切换站点直接 `stop nginx`。配置检查通过后 reload 就能平滑切换。

如果以后你希望脚本自动创建 Nginx 站点，可以显式运行：

```bash
sudo DOMAIN=your.domain.com INSTALL_NGINX=1 ./install-linux.sh
```

有多个域名时：

```bash
sudo DOMAIN="example.com www.example.com" INSTALL_NGINX=1 ./install-linux.sh
```

## 4. 填写环境变量

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

## 5. 后续更新版本

本地重新打包：

```bash
./deploy/build-release.sh
```

上传新的 `.release/web-homepage-时间戳.tar.gz` 到服务器，然后：

```bash
tar -xzf web-homepage-*.tar.gz
cd web-homepage
sudo ./install-linux.sh
sudo nginx -t
sudo systemctl reload nginx
```

更新时脚本会覆盖新的 jar 和前端 dist，但不会覆盖 `/etc/web-homepage/web.env`。

## 6. 常用命令

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
