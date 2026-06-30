# Deployment Guide

This guide is for collaborators who need to run or deploy this project without reading the full maintenance notes first.

## Runtime Baseline

Production is assumed to be a small Linux host with **2 CPU cores / 4 GB RAM**.

Use conservative defaults:

- Run one Spring Boot backend process and one static frontend.
- Keep PDF translation and PPT generation as single-worker queued jobs.
- Do not raise BabelDOC, LLM, or document parsing concurrency without retesting under a 2C/4G limit.
- Store generated PDF/PPTX/task files on disk under `.run/`, not in JVM memory.

## Required Software

Local development and production builds need:

- Java 17
- Maven
- Node.js and npm
- `uv`
- Python dependencies resolved through `uv run --with ...`

Useful checks:

```bash
java -version
mvn -version
node -v
npm -v
uv --version
```

## Environment Variables

Create a root `.env.local` on the machine that runs the backend. Do not commit it.

Minimum shape:

```bash
ZOTERO_API_KEY=...
ZOTERO_USER_ID=...
ADMIN_KEY=...
ROOT_USERNAME=root
ROOT_PASSWORD=replace-with-a-strong-password

# Production MySQL. Local development can omit these and use the default H2 database.
DB_URL=jdbc:mysql://127.0.0.1:3306/web?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_DRIVER=com.mysql.cj.jdbc.Driver
DB_USERNAME=webuser
DB_PASSWORD=...

LLM_API_URL=...
LLM_API_KEY=...
LLM_MODEL=...

BABELDOC_COMMAND="uv run --with babeldoc python"
BABELDOC_OPENAI_BASE_URL=...
BABELDOC_OPENAI_API_KEY=...
BABELDOC_OPENAI_MODEL=...
BABELDOC_TIMEOUT_SECONDS=21600

PPT_GENERATION_STORAGE_DIR=../.run/ppt-generation-tasks
PPT_GENERATION_MAX_HISTORY=5
PPT_GENERATION_QUEUE_CAPACITY=3
PPT_GENERATION_LLM_MAX_TOKENS=16384
PPT_GENERATION_VISION_MODEL=...
PPT_GENERATION_VISION_MAX_TOKENS=4096
PPT_GENERATION_TIMEOUT_SECONDS=900
PPT_GENERATION_RENDERER_COMMAND="uv run --with python-pptx --with pillow python"
PPT_GENERATION_RENDERER_SCRIPT=./scripts/ppt_renderer.py
PPT_GENERATION_TEMPLATE_FILL_COMMAND="uv run --with python-pptx python"
PPT_GENERATION_TEMPLATE_FILL_SCRIPT=./scripts/ppt-template-fill/template_fill_pptx.py
PPT_GENERATION_PAPER_PARSER_COMMAND="uv run --with docling --with markitdown python"
PPT_GENERATION_PAPER_PARSER_SCRIPT=./scripts/ppt_document_parser.py
```

`project.sh` sources `.env.local` automatically for local development. For production systemd, put equivalent values in an environment file or in the service unit.

`ROOT_PASSWORD` is required on first startup when no `ROOT` user exists. The backend intentionally fails fast if it is missing or shorter than 6 characters.

Spring Boot can create the application tables from `schema.sql`, but it does not create the MySQL database or user. Prepare them before starting `web-backen`:

```sql
CREATE DATABASE IF NOT EXISTS web
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'webuser'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT ALL PRIVILEGES ON web.* TO 'webuser'@'localhost';
FLUSH PRIVILEGES;
```

Validate the account before wiring systemd:

```bash
mysql -u webuser -p -h 127.0.0.1 web -e "SELECT 1"
```

## Local Development

From the repository root:

```bash
./project.sh start
./project.sh status
./project.sh logs backend
./project.sh stop
```

Default URLs:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- Backend health: `http://localhost:8080/api/health`

The Vite dev server proxies `/api/*` to the backend on port `8080`.

## Build Artifacts

Frontend:

```bash
cd front
npm install
npm run build
```

The static site is generated in `front/dist/`.

Backend:

```bash
cd backen
mvn -DskipTests package
```

The Spring Boot jar is generated as:

```text
backen/target/backen-0.0.1-SNAPSHOT.jar
```

Run tests when changing behavior:

```bash
cd backen
mvn test
```

## Production Layout

A simple production layout:

```text
/opt/web/
  backen.jar
  front-dist/
  scripts/
  .env.local
  .run/
```

Copy these from the repository after building:

- `backen/target/backen-0.0.1-SNAPSHOT.jar` -> `/opt/web/backen.jar`
- `front/dist/` -> `/opt/web/front-dist/`
- `backen/scripts/` -> `/opt/web/scripts/`
- root `.env.local` -> `/opt/web/.env.local`

Keep `.run/` persistent across restarts if you want recent translation and PPT results to survive backend restarts.

## Example systemd Service

Use Java 17 and keep heap modest for a 4 GB machine.

```ini
[Unit]
Description=web-backen
After=network.target

[Service]
WorkingDirectory=/opt/web
EnvironmentFile=/opt/web/.env.local
Environment="JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -XX:+UseG1GC"
ExecStart=/usr/bin/java -jar /opt/web/backen.jar
Restart=always
RestartSec=5
MemoryAccounting=yes
MemoryHigh=2200M
MemoryMax=2800M
TasksMax=256

[Install]
WantedBy=multi-user.target
```

Install and restart:

```bash
sudo systemctl daemon-reload
sudo systemctl enable web-backen
sudo systemctl restart web-backen
sudo systemctl status web-backen
```

## Outbound Proxy

If the server needs a local Clash Verge / Mihomo proxy for Zotero, S3, GitHub API, raw README, BabelDOC, or LLM calls, configure the Java process explicitly. Shell `HTTP_PROXY` / `HTTPS_PROXY` is not enough for all Java clients.

Example systemd drop-in:

```ini
[Service]
Environment="JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -XX:+UseG1GC -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
```

After changing a drop-in:

```bash
sudo systemctl daemon-reload
sudo systemctl restart web-backen
```

Verify:

```bash
systemctl show web-backen -p Environment
curl -f http://127.0.0.1:8080/api/health
```

## Nginx

Serve `front-dist/` as static files and proxy `/api/` to Spring Boot.

Important points:

- Keep `/api/translate/stream/` and `/api/ppt-generate/stream/` unbuffered for SSE.
- Keep `/api/zotero/file/` unbuffered so PDF attachment progress reflects real bytes.
- Allow large uploads for PDF/PPTX inputs.

Minimal sketch:

```nginx
server {
    listen 80;
    server_name example.com;

    client_max_body_size 64m;

    root /opt/web/front-dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location ~ ^/api/(translate/stream|ppt-generate/stream|zotero/file)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 21600s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Validate and reload:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

## Health Checks

Backend:

```bash
curl -f http://127.0.0.1:8080/api/health
curl -f http://127.0.0.1:8080/api/zotero/items
curl -f http://127.0.0.1:8080/api/github-projects
```

Frontend:

```bash
curl -I http://127.0.0.1/
```

Logs:

```bash
journalctl -u web-backen -f
```

Local development logs live under `.run/logs/`.

## Common Failures

`Zotero` or `GitHub` requests are slow or fail:

- Check whether the server needs the explicit Java proxy properties.
- Test `/api/github-projects` and `/api/zotero/items` from the server.

PDF translation fails before starting:

- Check `uv --version`.
- Run the configured `BABELDOC_COMMAND` once on the server so dependencies can download.
- Keep only one BabelDOC job running on a 2C/4G server.

PPT generation fails:

- Check `PPT_GENERATION_RENDERER_COMMAND`, `PPT_GENERATION_TEMPLATE_FILL_COMMAND`, and `PPT_GENERATION_PAPER_PARSER_COMMAND`.
- Confirm `backen/scripts/` was deployed with the jar.
- Check `.run/ppt-generation-tasks/{taskId}/` for generated JSON and script output.

Frontend works but API calls fail:

- Confirm Nginx proxies `/api/` to `127.0.0.1:8080`.
- Confirm Spring Boot is healthy.
- Confirm Vite-only proxy assumptions were not used in production.

Deploy script missing:

- The current working tree may not include the old one-click deploy scripts. Use the manual build and service steps in this document unless deployment scripts are restored and reviewed.
