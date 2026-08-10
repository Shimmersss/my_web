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

# MySQL. Local project.sh will start the Homebrew `mysql` service when these are set;
# leave them unset for explicit local H2 mode.
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
BABELDOC_MAX_PAGES_PER_CHUNK=5
BABELDOC_RESOURCE_RECOVERY_TIMEOUT_SECONDS=120

TRANSLATION_STORAGE_DIR=../.run/translation-tasks
TRANSLATION_MAX_HISTORY=5
TRANSLATION_MAX_GLOBAL_HISTORY=20

PPT_GENERATION_STORAGE_DIR=../.run/ppt-generation-tasks
PPT_GENERATION_MAX_HISTORY=5
PPT_GENERATION_MAX_GLOBAL_HISTORY=20
PPT_GENERATION_QUEUE_CAPACITY=3
PPT_GENERATION_MAX_ARCHIVE_ENTRIES=2000
PPT_GENERATION_MAX_ARCHIVE_UNCOMPRESSED_BYTES=125829120
PPT_GENERATION_MAX_ARCHIVE_ENTRY_BYTES=33554432
PPT_GENERATION_MAX_ARCHIVE_COMPRESSION_RATIO=120
PPT_GENERATION_AGENT_COMMAND=node
PPT_GENERATION_AGENT_SCRIPT=./scripts/ppt-agent/worker.mjs
PPT_GENERATION_AGENT_PROJECT_ROOT=..
PPT_GENERATION_AGENT_TIMEOUT_SECONDS=1800
PPT_GENERATION_AGENT_NODE_MAX_OLD_SPACE_MB=384
PPT_GENERATION_AGENT_MAX_SOURCES=12
PPT_GENERATION_VISION_MODEL=mimo-v2.5
# 可选：Mimo 原生联网搜索。需开通联网服务插件的按量开放平台 sk- Key；Token Plan 的 tp- Key 不支持此工具。
PPT_GENERATION_MIMO_SEARCH_ENDPOINT=https://api.xiaomimimo.com/v1/chat/completions
# PPT_GENERATION_MIMO_SEARCH_KEY=
PPT_GENERATION_MIMO_SEARCH_MODEL=mimo-v2.5
PPT_GENERATION_SOFFICE_COMMAND=soffice
PPT_GENERATION_PDFTOPPM_COMMAND=pdftoppm
PPT_GENERATION_CHROME_COMMAND=/usr/bin/chromium
# 需要经本机代理访问研究/图片源时（仅回环地址）：
# HTTPS_PROXY=http://127.0.0.1:7890
PPT_GENERATION_PAPER_PARSER_COMMAND="uv run --with docling --with markitdown python"
PPT_GENERATION_PAPER_PARSER_SCRIPT=./scripts/ppt_document_parser.py
PPT_GENERATION_CODEX_API_KEY=...
PPT_GENERATION_CODEX_COMMAND=./node_modules/.bin/codex
PPT_GENERATION_CODEX_VENDOR_ROOT=../vendor/open-kimi-ppt-skill
PPT_GENERATION_CODEX_FINALIZE_SCRIPT=./scripts/ppt-codex/finalize.mjs
PPT_GENERATION_CODEX_TIMEOUT_SECONDS=1800
TAVILY_API_URL=https://api.tavily.com/search
TAVILY_API_KEY=
TAVILY_MAX_SEARCHES=6
SEMANTIC_SCHOLAR_API_KEY=
# 搜图优先级：Tavily（配置 Key 时）→ Wikimedia Commons → Openverse → Unsplash。
# 最后一层仅用于版权角色素材缺失时的主题关联实景视觉。
```

`project.sh` sources `.env.local` automatically for local development and turns locally discovered `soffice`/`pdftoppm` binaries into absolute paths when those variables are not explicitly set. For production systemd, put equivalent values in an environment file or in the service unit; set `PPT_GENERATION_SOFFICE_COMMAND` and `PPT_GENERATION_PDFTOPPM_COMMAND` to absolute paths so the service does not depend on an interactive shell PATH. PPTX/HTML generation requires Node.js 20+, stable LibreOffice, Poppler, Chromium, fontconfig and Noto CJK. The deploy installer treats these as hard preconditions and runs `npm ci --omit=dev` under the backend directory.

The PPT generator accepts `outputFormat=pptx|html` and `researchMode=auto|off`. HTML keeps the existing reveal.js Agent worker. New PPTX tasks are root-only and run the locked `@openai/codex@0.147.0` CLI in a disposable owner-only workspace with a temporary `CODEX_HOME` and `workspace-write` sandbox. Codex writes only a self-contained PPTD v2 project; the server then invokes the fixed vendored exporter, validates closed page/media paths, exports PPTX, renders it with stable LibreOffice/Poppler, and packages the complete PPTD project. The API key is sent to `codex login --with-api-key` over stdin and must not appear in commands, logs or task metadata.

The vendored runtime is fixed under `vendor/open-kimi-ppt-skill/`; source commit and file hashes are recorded there. `front` build runs `prepare:pptd-editor` and copies the upstream editor plus the website overlay into `/pptd-editor/`. The initial production migration creates a readable `ppt-generation-tasks-pre-codex-<timestamp>.tar.gz` before clearing incompatible legacy PPT task files. Do not open Codex PPTX to ordinary users until per-task container isolation, read-only Skill mounts, restricted network egress, and CPU/memory/PID limits have passed a separate review.

The legacy GitHub PPTX template-cache preparation is no longer part of the release path. HTML theme assets remain static; PPTX design choices are supplied by the vendored Skill design systems, while an uploaded custom PPTX is copied into the isolated task input as a visual reference.

When local MySQL is configured, `./project.sh start` starts Homebrew's `mysql` service and waits for port 3306 before launching Spring Boot. `./project.sh stop` stops only the MySQL instance started by that project invocation; use `./project.sh mysql stop` when you explicitly want to stop the service. Set `PROJECT_MYSQL_SERVICE` for a formula such as `mysql@8.4`, or set `PROJECT_DB_MODE=h2` to bypass MySQL locally.

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
npm test
```

Before publishing presentation changes, validate the repository Skills and run the PPTD/HTML checks:

```bash
uv run --with pyyaml python ~/.codex/skills/.system/skill-creator/scripts/quick_validate.py ../.agents/skills/research-presentation
uv run --with pyyaml python ~/.codex/skills/.system/skill-creator/scripts/quick_validate.py ../.agents/skills/create-html-presentation
cd backen
npm test
```

For each selected PPTD design system and HTML theme, generate representative decks, inspect every PNG, and confirm PPTD paths, citations, notes, overflow, PPTX ZIP/package report and LibreOffice render. Run the final smoke with the backend and its child processes constrained to 2 CPU / 4 GB; only one LibreOffice-heavy task may run at a time.

HTML template previews remain generated from reveal theme assets. PPTX design systems use local colour/structure cards; the actual PPTD result is always rendered from the task artifact:

```bash
cd backen
node scripts/generate_html_template_previews.mjs --output-dir ../front/public/html-template-previews
cd ../front
npm run build
```

This build-time HTML preview step needs Chrome/Chromium. PPTX and HTML intentionally use different template families; the frontend filters `/api/ppt-generate/templates` by each template's `formats` field. The frontend build also copies the vendored editor plus its site overlay to `/pptd-editor/`.

## Production Layout

A simple production layout:

```text
/opt/web-homepage/
  backen/
    backen.jar
    package.json
    package-lock.json
    node_modules/
    scripts/
  vendor/open-kimi-ppt-skill/
  front/dist/
  .agents/skills/
  .run/
    ppt-generation-tasks/
/etc/web-homepage/web.env
```

Build and copy the following as one release:

- `backen/target/backen-0.0.1-SNAPSHOT.jar` -> `/opt/web-homepage/backen/backen.jar`
- `backen/package.json`, `backen/package-lock.json`, `backen/scripts/` -> `/opt/web-homepage/backen/`
- `vendor/open-kimi-ppt-skill/` -> `/opt/web-homepage/vendor/open-kimi-ppt-skill/`
- repository `.agents/` -> `/opt/web-homepage/.agents/`
- `front/dist/` -> `/opt/web-homepage/front/dist/`

Run `npm ci --omit=dev --ignore-scripts` in `/opt/web-homepage/backen`. Keep `.run/` persistent across normal installs and rollbacks. The first Codex-PPT deployment is the exception: it archives legacy PPT tasks to `ppt-generation-tasks-pre-codex-<timestamp>.tar.gz`, verifies the archive, then clears incompatible task files. If that install fails, restore both the code release and that task archive before retrying.

The installer rejects LibreOfficeDev/alpha/beta/RC builds and renders two different pure-CJK
samples plus a blank baseline before starting Spring Boot. Deployment creates a common lock,
waits for in-flight create requests to persist, scans the resolved translation/PPT task stores,
stops the service, then scans again. Exit code `42` means an active task postponed the release;
the old service is restored and temporary cache backups are removed.

## Example systemd Service

Use Java 17 and keep heap modest for a 4 GB machine.

```ini
[Unit]
Description=web-backen
After=network.target

[Service]
WorkingDirectory=/opt/web-homepage/backen
EnvironmentFile=/etc/web-homepage/web.env
Environment="JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -XX:+UseG1GC"
ExecStart=/usr/bin/java -jar /opt/web-homepage/backen/backen.jar
Restart=always
RestartSec=5
MemoryAccounting=yes
MemoryHigh=2200M
MemoryMax=2800M
TasksMax=256

[Install]
WantedBy=multi-user.target
```

`PPT_GENERATION_AGENT_PROJECT_ROOT=..` is resolved from the `backen/` working directory and points
to the deployed `.agents/skills/`. A different working directory will break Skill discovery.

Install and restart:

```bash
sudo systemctl daemon-reload
sudo systemctl enable web-backen
sudo systemctl restart web-backen
sudo systemctl status web-backen
```

## Outbound Proxy

If the server needs a local Clash Verge / Mihomo proxy for Zotero, S3, GitHub API, raw README, BabelDOC, or LLM calls, configure the Java process explicitly. Shell `HTTP_PROXY` / `HTTPS_PROXY` is not enough for all Java clients. The presentation Agent also runs Node: `PptAgentRunner` converts the JVM `https.proxyHost/https.proxyPort` settings to `PPT_AGENT_PROXY_URL`, and its pinned `undici` `ProxyAgent` applies that proxy to LLM and research requests.

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
- Allow large uploads for PDF/image/PPTX inputs.

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

- Check `PPT_GENERATION_CODEX_COMMAND`, `PPT_GENERATION_CODEX_VENDOR_ROOT`, stable `soffice`, `pdftoppm`, Noto CJK and `PPT_GENERATION_PAPER_PARSER_COMMAND`.
- Confirm `vendor/open-kimi-ppt-skill/`, `backen/scripts/ppt-codex/`, Node dependencies and `front/dist/pptd-editor/` were deployed.
- Check `.run/ppt-generation-tasks/{taskId}/codex-events.jsonl`, `pptd-project/`, `pptd-project.zip`, `preview/` and `quality-report.json`. HTML tasks retain `agent.log`, `sources.json` and `agent-plan.json`.
- If Tavily is not configured, the task should report `researchDegraded=true` but still use open academic indexes.

Frontend works but API calls fail:

- Confirm Nginx proxies `/api/` to `127.0.0.1:8080`.
- Confirm Spring Boot is healthy.
- Confirm Vite-only proxy assumptions were not used in production.

Deploy script missing:

- The current working tree may not include the old one-click deploy scripts. Use the manual build and service steps in this document unless deployment scripts are restored and reviewed.
