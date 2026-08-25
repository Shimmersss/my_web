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
AUTH_COOKIE_SECURE=true
SERVER_ADDRESS=127.0.0.1

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
PPT_GENERATION_AGENT_NODE_MAX_OLD_SPACE_MB=384
PPT_GENERATION_VISUAL_PREFETCH_SCRIPT=./scripts/ppt-agent/prefetch-visual-assets.mjs
PPT_GENERATION_SOFFICE_COMMAND=soffice
PPT_GENERATION_PDFTOPPM_COMMAND=pdftoppm
PPT_GENERATION_CHROME_COMMAND=/usr/bin/chromium
# 需要经本机代理访问研究/图片源时（仅回环地址）：
# HTTPS_PROXY=http://127.0.0.1:7890
PPT_GENERATION_PAPER_PARSER_COMMAND="uv run --with docling --with markitdown python"
PPT_GENERATION_PAPER_PARSER_SCRIPT=./scripts/ppt_document_parser.py
# Optional. If empty in local development, reuse the local logged-in Codex CLI
# through a filtered temporary CODEX_HOME. Production should set this explicitly.
PPT_GENERATION_CODEX_API_KEY=...
PPT_GENERATION_CODEX_COMMAND=./node_modules/.bin/codex
PPT_GENERATION_CODEX_VENDOR_ROOT=../vendor/open-kimi-ppt-skill
PPT_GENERATION_CODEX_FINALIZE_SCRIPT=./scripts/ppt-codex/finalize.mjs
PPT_GENERATION_CODEX_HTML_SKILL_ROOT=../.agents/skills/create-html-presentation
PPT_GENERATION_CODEX_HTML_FINALIZE_SCRIPT=./scripts/ppt-agent/finalize-html-plan.mjs
PPT_GENERATION_CODEX_TIMEOUT_SECONDS=1800
# Optional, for the PPTX “AI 生图” option. Availability follows the runtime
# `Contact / PPT 生成` visibility policy; use an OpenAI-compatible
# Images API endpoint and a dedicated service Key; Codex CLI auth is never sent here.
PPT_GENERATION_IMAGE_GENERATION_ENDPOINT=https://api.openai.com/v1
PPT_GENERATION_IMAGE_GENERATION_KEY=
PPT_GENERATION_IMAGE_GENERATION_MODEL=gpt-image-2
PPT_GENERATION_IMAGE_GENERATION_QUALITY=medium
PPT_GENERATION_IMAGE_GENERATION_MAX_IMAGES=3
TAVILY_API_URL=https://api.tavily.com/search
TAVILY_API_KEY=
TAVILY_MAX_SEARCHES=6
SEMANTIC_SCHOLAR_API_KEY=
# Tavily 索引图片标记为 rightsStatus=unverified；Commons/Openverse 的明确许可信息会保留。
```

`project.sh` sources `.env.local` automatically for local development and turns locally discovered `soffice`/`pdftoppm` binaries into absolute paths when those variables are not explicitly set. For production systemd, put equivalent values in an environment file or in the service unit; set `PPT_GENERATION_SOFFICE_COMMAND` and `PPT_GENERATION_PDFTOPPM_COMMAND` to absolute paths so the service does not depend on an interactive shell PATH. PPTX/HTML generation requires Node.js 20+, stable LibreOffice, Poppler, Chromium, fontconfig and Noto CJK. The deploy installer treats these as hard preconditions and runs `npm ci --omit=dev` under the backend directory. On Linux it also creates and verifies the PATH-local `codex-linux-sandbox` multi-call alias beside the pinned CLI; without it Codex can authenticate but cannot execute the Skill's file reads or write a PPTD project.

The PPT generator accepts `outputFormat=pptx|html`, `researchMode=auto|off`, and—for HTML—`motionMode=auto|subtle|expressive|off`. Both authoring paths run the locked `@openai/codex@0.147.0` CLI in a disposable owner-only workspace with a temporary `CODEX_HOME` and `workspace-write` sandbox. PPTX Codex writes a PPTD v2 project; HTML Codex may only write a bounded `agent-plan.json`, which the server-owned reveal.js renderer validates and turns into a self-contained deck with real-browser boundary QA. Neither format uses the legacy MiMo presentation Worker. Access follows the runtime `Contact / PPT 生成` policy (`USER` permits logged-in users, `ROOT` restricts to root, and `PUBLIC` exposes the page/templates but still requires a logged-in task owner). Before Codex starts, the fixed visual prefetcher may query Tavily, Wikimedia Commons and Openverse outside the sandbox; only validated local images and provenance metadata enter Codex. Search credentials never enter its HOME, prompt or task metadata. A saved Key is sent to `codex login --with-api-key` over stdin and must not appear in commands, logs or task metadata. A CCSwitch Base URL is written only to the temporary home as a fixed `OpenAI / responses` Provider; standard OpenAI Key mode uses `--ignore-user-config`. When no Key is saved, local development may automatically reuse a logged-in local Codex CLI: only `auth.json` and the active CCSwitch-compatible model-provider block (`base_url`, `wire_api`, model catalog) are copied to the owner-only temporary home; MCP servers, rules and plugins are excluded. Production should use an explicit service Key unless the service account has an intentionally managed Codex home. The optional GPT Image 2 setting generates 1–10 server-side PNG assets before Codex authoring; supplement mode remains capped at two. Configure a separate OpenAI-compatible `POST /v1/images/generations` service Key. It accepts only `data[0].b64_json`, never forwards local Codex auth, and a configured option failure aborts/refunds the task rather than silently switching credentials or source provenance.

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
npm run prepare:html-previews
npm run check:html-previews
cd ../front
npm run build
```

This build-time HTML preview step needs Chrome/Chromium. The committed preview manifest pins the current theme SHA-256, renderer SHA-256 and reveal.js version; the frontend build fails when any preview is stale or missing. PPTX and HTML intentionally use different template families; the frontend filters `/api/ppt-generate/templates` by each template's `formats` field. The frontend build also copies the vendored editor plus its site overlay to `/pptd-editor/`.

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

The service working directory remains `/opt/web-homepage/backen`; the explicit
`PPT_GENERATION_CODEX_HTML_SKILL_ROOT` and vendor paths are resolved from there.
Production must also set `SERVER_ADDRESS=127.0.0.1` and
`AUTH_COOKIE_SECURE=true`; the tracked
`deploy/web-backen-origin-hardening.conf` can be installed as a systemd drop-in
when those values are not already enforced by the main unit.

Install and restart:

```bash
sudo systemctl daemon-reload
sudo systemctl enable web-backen
sudo systemctl restart web-backen
sudo systemctl status web-backen
```

## Outbound Proxy

If the server needs a local Clash Verge / Mihomo proxy for Zotero, S3, GitHub API, raw README, BabelDOC, Codex or visual-search calls, configure the Java process explicitly. Shell `HTTP_PROXY` / `HTTPS_PROXY` is not enough for all Java clients. The fixed visual prefetcher also receives only the controlled loopback proxy URL derived by the backend.

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

- Keep `/api/translate/stream/`, `/api/ppt-generate/stream/` and `/api/image-generate/stream/` unbuffered for SSE.
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

    location ~ ^/api/(translate/stream|ppt-generate/stream|image-generate/stream|zotero/file)/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        proxy_read_timeout 21600s;
        add_header X-Accel-Buffering no always;
        add_header Cache-Control "no-store" always;
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

## Origin Protection

The public DNS name must terminate at an edge proxy, not at the application
host. DNS proxying alone is insufficient because historical DNS and public Git
history may retain an old origin address.

Use one of these supported topologies:

- For an Alibaba Cloud mainland origin, prefer ESA/WAF origin protection. Put
  every public hostname on the edge service, allow only the current ESA/WAF
  back-to-origin IPv4 and IPv6 ranges on ports 80/443, and deny all other
  Internet sources in the ECS security group.
- For an outbound-only origin, use a named Cloudflare Tunnel and bind the local
  service to loopback/private interfaces. Close public 80/443 completely. Do
  not use a Quick Tunnel in production because this application depends on SSE.
- If using ordinary Cloudflare proxying, combine proxied DNS with origin ACLs,
  Full (strict) TLS and account-specific Authenticated Origin Pulls. An Origin
  CA certificate or a secret header alone does not block direct traffic.

Before tightening an ACL, preserve cloud-console access and a time-limited SSH
path from a trusted address. Add edge allow rules first, verify the public site,
then add the deny rule. Do the same for IPv6. Never trust forwarded client-IP
headers until the TCP peer has already been restricted to the chosen edge's
official back-to-origin ranges.

After the edge and ACL are healthy, rotate or remove the previously exposed
public address. Keep deployment addressing only in the ignored mode-`0600`
`.deploy.local`; prefer a private/VPN SSH alias. Validate the final state from
an external network:

```bash
PUBLIC_HOST=example.com \
ORIGIN_ADDRESS=known-old-origin-address \
./deploy/verify-origin-lockdown.sh
```

The public request must succeed and the pinned origin request using the correct
Host/SNI must fail at the network or origin-authentication layer. A `404` from a
default virtual host is not sufficient.

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

Deploy target missing:

- Copy `.deploy.local.example` to the ignored `.deploy.local`, set a private or
  VPN-only SSH alias as `DEPLOY_HOST`, and run `chmod 600 .deploy.local`.
- The deployment script intentionally has no default production host and will
  refuse an insecurely permissioned `.deploy.local`.

## Routine Production Deployment

Keep `.deploy.local` outside Git with mode `0600`, and leave
`FORCE_NGINX_CONFIG=0` so the existing Certbot/TLS site and retired vhosts are
preserved. From the repository root, deploy a reviewed clean commit with:

```bash
chmod 600 .deploy.local
REQUIRE_CLEAN=1 RUN_TESTS=1 ./deploy/deploy-server-improved.sh
```

The script runs the backend and frontend test/build gates, creates and uploads a
timestamped release, backs up the current server tree, installs the package,
waits for the loopback backend health check, verifies Nginx and the public URL,
and attempts rollback if installation or local verification fails. Do not set
`FORCE_NGINX_CONFIG=1` for a routine release. OpenClaw was retired separately at
the service, firewall, and Nginx layers; normal application deployment must not
re-enable any of them.
