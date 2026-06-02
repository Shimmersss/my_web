# AGENTS.md

> 给后续接手的 AI / 协作者看的项目说明书。先读这个再动代码。

## 这是什么

个人主页 + Zotero 文献库展示 + GitHub 开源项目展示 + PDF 论文翻译 + OpenClaw 网页对话。
- 前端：Vue 3 + Vite + Naive UI
- 后端：Spring Boot 3 + Java 17（包名 `com.web.backen`，目录拼写就是 `backen`，别改）
- 数据源：Zotero Web API v3（用户的私有库）+ GitHub API / raw README + LLM 翻译 API

## 一句话流程

浏览器 → Vite 代理（`/api/*` → 后端 8080）→ Spring Boot → 内存缓存 → 返回。
缓存由后端启动时预热 + 每 5 分钟后台刷新维护，**用户请求不直连 Zotero**。

GitHub 开源项目也一样：浏览器只请求 `/api/github-projects*`，由后端 `GithubProjectService` 去请求 GitHub API / raw README。**前端不要直接 fetch `api.github.com` 或 `raw.githubusercontent.com`**。

## 启停

```bash
./project.sh start         # 后端 + 前端
./project.sh restart backend
./project.sh stop
./project.sh status
./project.sh logs backend  # tail -f 后端日志
```

PID/日志在 `.run/`（已 gitignore）。

## 凭证

`.env.local`（已 gitignore）：

```
ZOTERO_API_KEY=...
ZOTERO_USER_ID=...
ADMIN_KEY=...  # GitHub 管理后台 + OpenClaw 网页对话登录

# LLM 翻译 API（Anthropic Messages API 格式）
LLM_API_URL=https://token-plan-cn.xiaomimimo.com/anthropic
LLM_API_KEY=...
LLM_MODEL=mimo-v2.5-pro

# 排版版 PDF 翻译（BabelDOC + OpenAI-compatible API）
BABELDOC_COMMAND="uv run --with babeldoc python"
BABELDOC_OPENAI_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1
BABELDOC_OPENAI_API_KEY=...
BABELDOC_OPENAI_MODEL=mimo-v2.5-pro

# OpenClaw Gateway 后端代理
OPENCLAW_COMMAND=openclaw
OPENCLAW_SESSION_KEY=main
OPENCLAW_TIMEOUT_SECONDS=30
```

`project.sh` 启动时自动 source。`.env.local.example` 是模板，可提交。

## 目录

```
backen/                                      Spring Boot 后端
  src/main/java/com/web/backen/
    BackenApplication.java                   入口（@EnableScheduling 别删，缓存定时刷新靠它）
    config/ZoteroConfig.java                 @ConfigurationProperties 读 zotero.* 环境变量
    zotero/
      ZoteroService.java                     调 Zotero API 的薄封装
      ZoteroCache.java                       内存缓存 + 启动预热 + 5min 静默刷新
      ZoteroController.java                  REST 接口，命中缓存
    github/
      GithubProjectStore.java                `.run/github-projects.json` 本地展示配置读写
      GithubProjectService.java              后端请求 GitHub API / raw README 并补全字段
      GithubProjectController.java           REST 接口，前端只调用这里
    config/LlmConfig.java                    @ConfigurationProperties 读 llm.* 环境变量（翻译 API）
    config/BabelDocConfig.java               @ConfigurationProperties 读 babeldoc.* 环境变量
    config/OpenClawConfig.java               @ConfigurationProperties 读 openclaw.* 环境变量
    openclaw/
      OpenClawService.java                   调本机 openclaw CLI，代理 chat.* / sessions.* / models.list / commands.list
      OpenClawController.java                网页对话 REST 接口，校验 ADMIN_KEY
    translate/
      PdfParseService.java                   PDFBox 页数读取；旧版文本块提取逻辑暂时保留
      PdfRenderService.java                  翻译 PDF 渲染：原页面矢量导入→白色遮罩→可复制中文译文
      BabelDocService.java                   调 BabelDOC CLI 生成保留版式的纯中文 PDF
      LlmService.java                        调 Anthropic Messages API 翻译，含重试
      TranslationSession.java                单次翻译任务的内存状态（含页面范围）
      TranslationService.java                编排器：预览→页面范围→BabelDOC→缓存→SSE 状态
      TranslateController.java               REST 接口，上传/开始/流式/状态/下载
front/                                       Vue 3 前端
  src/views/Publications/index.vue           文献库主页面（核心，所有 Zotero 展示逻辑都在这）
  src/views/News/index.vue                   GitHub 开源项目页 + 管理后台 + README 弹窗
  src/views/Translate/index.vue              PDF 论文翻译页（上传/配置/进度/结果四态）
  src/views/Franchise/index.vue              OpenClaw 网页对话页（原招商加盟页）
  src/api/index.js                           getZoteroItems / getZoteroCollections
  src/utils/request.js                       fetch 请求封装
  vite.config.js                             /api 代理到 :8080
project.sh                                   一键启停
```

## 后端接口

| 路径 | 说明 | 性能 |
|---|---|---|
| `GET /api/zotero/items` | 主条目列表（母条目 + 孤立附件，挂上 attachments 子项）| ~2 ms（缓存）|
| `GET /api/zotero/items?refresh=true` | 触发后台刷新，不阻塞当前请求 | ~2 ms |
| `GET /api/zotero/collections` | 文献分组 | ~2 ms（缓存）|
| `GET /api/zotero/file/{key}` | 附件代理。自动跟 S3 302、自动解 ZIP（snapshot 类型）、按文件名推断真实 content-type | 取决于上游 |
| `GET /api/zotero/items/{key}/export?format=bibtex\|ris\|bibliography&style=apa` | 引用导出 | 走上游，~1-3s |
| `GET /api/zotero/items/raw` | 原始 Zotero JSON（调试用，不走缓存）| 慢 |
| `GET /api/github-projects` | GitHub 开源项目列表。后端读取本地配置并补 stars/forks/language/topics/default_branch 等 | 取决于上游 |
| `GET /api/github-projects/{owner}/{repo}/readme` | README.md 代理。后端尝试 default_branch/main/master + README 文件名变体 | 取决于上游 |
| `POST /api/github-projects/login` | 管理员密钥登录 | 校验 `ADMIN_KEY` |
| `PUT /api/github-projects` | 保存展示配置，需 `X-Admin-Key` | 写入 `.run/github-projects.json` |
| `POST /api/openclaw/login` | OpenClaw 网页对话登录 | 校验 `ADMIN_KEY` |
| `GET /api/openclaw/history?sessionKey=...` | 读取指定 OpenClaw 会话历史，需 `X-Admin-Key` | 后端调用 `chat.history` |
| `POST /api/openclaw/send` | 给指定 OpenClaw 会话发消息，需 `X-Admin-Key` | 后端调用 `chat.send` |
| `GET /api/openclaw/sessions` | 对话侧栏列表，含标题、模型和更新时间 | 后端调用 `sessions.list` |
| `POST /api/openclaw/sessions` | 新建独立对话 | 后端调用 `sessions.create` |
| `POST /api/openclaw/sessions/reset` | 清空当前对话并重新开始 | 后端调用 `sessions.reset` |
| `PATCH /api/openclaw/sessions` | 切换指定会话模型、思考级别或标签 | 后端调用 `sessions.patch` |
| `GET /api/openclaw/models` | 读取 Gateway 可用模型 | 后端调用 `models.list` |
| `GET /api/openclaw/commands` | 读取 `/` 指令提示 | 后端调用 `commands.list` |
| `GET /api/openclaw/artifacts?sessionKey=...` | 读取当前会话可下载产物 | 后端调用 `artifacts.list` |
| `GET /api/openclaw/artifacts/{artifactId}/download?sessionKey=...` | 下载指定会话产物 | 后端调用 `artifacts.download`，不开放任意本机路径 |
| `GET /api/openclaw/media/inbound/{filename}` | 恢复历史消息中的图片预览，需 `X-Admin-Key` | 只允许读取 `~/.openclaw/media/inbound/` 下普通文件 |
| `POST /api/translate/upload` | 上传 PDF，返回 taskId + totalPages（不立即翻译）| PDFBox 解析 |
| `POST /api/translate/start/{taskId}?startPage=1&endPage=10&fontFamily=auto&qps=8` | 指定页面范围、译文字体族和并发速度并准备翻译 | 内存写入 |
| `GET /api/translate/stream/{taskId}` | SSE 启动 BabelDOC 并推送状态（layout/progress/done/error）| BabelDOC 单链路 |
| `GET /api/translate/status/{taskId}` | 查询任务状态（断线重连用）| 内存读 |
| `GET /api/translate/download/{taskId}` | 下载纯中文 .txt | 从 BabelDOC PDF 提取 |
| `GET /api/translate/download-pdf/{taskId}?mode=translated\|bilingual` | 下载纯中文或双语 PDF（保留版式、图片和公式）| BabelDOC 缓存 |

`/items` 返回结构：

```json
{
  "code": 200,
  "data": [{
    "key": "ATE47N35",
    "itemType": "journalArticle",
    "title": "...",
    "creators": [...],
    "date": "2023",
    "publicationTitle": "...",
    "DOI": "10.xxx/xxx",
    "url": "...",
    "abstractNote": "...",
    "tags": [{"tag": "..."}],
    "collections": ["HJW949Y2"],
    "attachments": [{"key": "YDY2Y293", "filename": "...", "contentType": "application/pdf", "isPdf": true}]
  }],
  "updatedAt": 1717228443229,
  "warmedUp": true
}
```

## 主条目的两种来源

`/items` 返回里的"主条目"包含两类，前端统一渲染：

1. **正常文献**（`itemType=journalArticle / book / ...`）：母条目，子附件（PDF 等）通过 `parentItem` 反向挂载到它的 `attachments` 数组
2. **孤立附件**（`itemType=attachment` 且 `parentItem=null`）：用户直接拖进 collection 的 md / 单文件 pdf 等。这类条目的 `attachments` 里只有一个元素——它自己（让前端"查看附件"按钮逻辑零改动复用）

## 附件代理的特殊处理

`GET /api/zotero/file/{key}` 不是单纯透传：

- **跟 302**：Zotero 用 S3 存大文件，会 302 跳转。Spring `RestClient` 默认不跟，所以这里改用 JDK `HttpClient.followRedirects(NORMAL)`
- **解 ZIP**：Zotero 把 markdown / 网页快照 / 部分单文件附件**用 ZIP 打包存**。上游 content-type 可能写 `text/plain` 但字节是 ZIP（前 4 字节 `PK\003\004`）。后端检测到 ZIP 头会解出第一个非目录条目
- **推断 content-type**：解压后按文件名后缀返回正确类型（.md → `text/markdown`，.html → `text/html` 等）。如果上游不是 ZIP 就保留上游 content-type

## PDF 论文翻译功能

**四步流程**：
1. 上传 PDF → 返回 taskId + totalPages
2. 选择页面范围（默认全部）、字体族和速度模式 → 点击"开始翻译"
3. SSE 启动 BabelDOC，实时推送版面分析、正文翻译、重新排版和 PDF 重建进度
4. 预览或下载翻译 PDF，也可下载从最终 PDF 提取的纯中文 TXT

**页面范围选择**：用户可指定翻译第几页到第几页。上传后进入配置态，选择起止页码后点击"开始翻译"。

**翻译 PDF 渲染**（`BabelDocService`）：
- 最终排版版 PDF 改由 BabelDOC CLI 生成，不再使用自研 `PdfRenderService` 作为下载链路
- BabelDOC 通过 OpenAI-compatible API 翻译，当前地址为 `https://token-plan-cn.xiaomimimo.com/v1`
- 只输出用户选择的页面范围；一次生成纯中文和双语对照两份 PDF，结果缓存在 `TranslationSession`
- 翻译前可选译文字体族：`auto / serif / sans-serif / script`，对应 BabelDOC `--primary-font-family`
- BabelDOC 不提供固定字号参数；字号由版面引擎根据原文本框自动适配，不要在前端伪造固定字号选项
- 本机需安装 `uv`；默认通过 `uv run --with babeldoc python backen/scripts/babeldoc_runner.py` 调用结构化 runner，首次运行会自动下载 Python 运行时依赖和模型资源
- runner 在收到 BabelDOC `finish` 后主动退出；macOS 上 ONNX/CoreML 可能保留原生后台线程，不能只等待 Python 自然结束
- API Key 通过子进程环境变量传给 runner，不要放进命令行参数，避免本机 `ps` 暴露密钥
- 配置态提供稳定模式（4 QPS）和加速模式（8 QPS），worker 数与 QPS 对齐；接口校验范围为 `1-12`
- BabelDOC 是唯一主翻译链路，不要在生成 PDF 前再逐段调用 `LlmService`；否则同一篇论文会翻译两次
- 中文 TXT 从 BabelDOC 已生成的 PDF 提取，不会再次调用模型
- `PdfRenderService` 暂时保留，作为旧版实现参考，不要再接回默认下载链路

**旧版文本块提取**（`PdfParseService`，默认链路已不再调用）：
- 不要再用纯文本段落数量去猜对应几行，这在双栏论文、标题、图注里容易错位
- `PositionStripper` 会记录每行文本 + bounding box，再按同栏、行距、缩进、标题/图注边界重建 layout block
- 同一行中如果字符之间有明显水平空隙，会拆成多个 `LineBox`，用于保留表格列/多列标签位置，避免把一整行表格揉成一个翻译块
- 页码、数学公式样文本、短数字/单位/变量标签会标记为 `translatable=false`，不送去翻译，但在清理原文字流后由 `PdfRenderService` 原样重绘

**LLM 配置**：排版版 PDF 使用 `BABELDOC_OPENAI_BASE_URL`、`BABELDOC_OPENAI_API_KEY`、`BABELDOC_OPENAI_MODEL`（OpenAI-compatible API）。旧的 `LLM_*` 配置和 `LlmService` 暂时保留，仅作为历史实现参考，不在默认翻译链路中调用。

**翻译保护层**（`TranslationProtector`）：
- 翻译前把 CNN/LSTM/Transformer/YOLO/PointPillars/VoxelNet/KITTI/Waymo/NuScenes/LiDAR/SLAM/mAP/IoU 等缩写替换成占位符
- 保护引用编号 `[12]`、公式编号 `(3)`、变量样式 `x_i`、`x^2`、百分比和常见单位
- 翻译后恢复占位符
- 统一把 Figure/Fig. 规范为“图”，Table 规范为“表”

**公式过滤**：`PdfParseService.looksLikeFormula()` 会粗略跳过数学符号密集、变量/公式样文本，避免送去 LLM 翻译并遮罩公式区域。该规则是启发式，复杂论文仍需继续优化。

**前端四态**：上传态（拖拽/选择）→ 配置态（页面范围 + 字体族 + 速度模式）→ 翻译中态（BabelDOC 实时阶段进度）→ 结果态（纯中文 / 双语 PDF 预览切换、下载中文 TXT、下载当前 PDF）。

**翻译 PDF 预览**：
- 翻译完成进入结果态后，前端调用 `/api/translate/download-pdf/{taskId}?mode=...` 获取 Blob，生成本地 Object URL，内嵌 iframe 预览 PDF
- 结果页可随时切换 `translated / bilingual`，只切换缓存 PDF，不会重新调用 BabelDOC 或模型
- 预览区优先展示，用于检查选中页的版式、页码、图片、表格、公式位置，再下载 PDF
- 重置页面或组件卸载时必须 `URL.revokeObjectURL()` 释放 Blob URL

**SSE 事件**：`layout`（BabelDOC 正在处理）、`progress`（真实阶段、当前项、总项、整体百分比）、`done`（全部完成）、`error`（失败）。断线恢复只轮询 `/status/{taskId}`，不要重新建立 SSE 启动第二个 BabelDOC 子进程。

**内存存储**：翻译结果存在 `ConcurrentHashMap`，不持久化到 H2。重启后端丢失。

**关键依赖**：`org.apache.pdfbox:pdfbox:3.0.3`（上传页数和中文 TXT 提取）+ `uv run --with babeldoc python`（排版版 PDF runner）。

## 前端 markdown 渲染

孤立 md 附件：前端检测 `filename` 以 `.md` 结尾时，不挂 iframe，而是：
1. `fetch('/api/zotero/file/{key}')` 拿文本
2. `marked.parse()` 渲染成 HTML（开 `breaks: true, gfm: true`）
3. `DOMPurify.sanitize()` 防 XSS（**不能省**，上游内容不可信）
4. `v-html` 注入到 `.md-render` 容器，样式见组件内 `<style>` 块

依赖：`marked` + `dompurify`（已加到 package.json）

## OpenClaw 网页对话

原 `/franchise` 招商加盟页已替换为站内 OpenClaw WebChat。浏览器只请求 `/api/openclaw/*`，不直接连 Gateway，也不会拿到 Gateway token。

**为什么不 iframe 嵌入官方控制台**：
- OpenClaw Control UI 返回 `X-Frame-Options: DENY`
- CSP 同时设置 `frame-ancestors 'none'`
- 浏览器会强制拦截 iframe，因此本站实现自己的聊天 UI

**调用链**：

```
浏览器 /franchise
  → 输入 ADMIN_KEY（只存 sessionStorage）
  → Vite /api 代理
  → Spring Boot OpenClawController 校验 X-Admin-Key
  → OpenClawService 启动本机 openclaw CLI 子进程
  → openclaw gateway call chat.* / sessions.* / models.list / commands.list / artifacts.*
  → ws://127.0.0.1:18789
```

**安全边界**：
- OpenClaw Gateway token 由本机 CLI 自己读取，不写入前端、不放进 URL、不返回浏览器
- 所有 `/api/openclaw/*` 业务接口都要求 `X-Admin-Key`，只有 `/login` 用于校验密钥
- 前端登录密钥存在 `sessionStorage`，关闭标签页后失效
- assistant markdown 使用 `marked + DOMPurify` 渲染，不能省略 sanitize
- 当前聊天页每 2 秒轮询历史；不是 Gateway WebSocket 直推

**聊天页功能**：
- 左侧对话栏读取 Gateway 的真实 sessions，可新建、搜索和切换会话
- 顶部模型选择器读取 `models.list`，切换后写入当前 session override
- 模型列表每 10 秒随会话侧栏后台刷新一次；顶部刷新按钮也会同时刷新历史、会话和模型
- 站内模型列表不是直接使用 OpenClaw 默认白名单。后端调用 `models.list(view=all)`，再与 Gateway `config.get` 中 `models.providers.*.models` 的显式配置取交集；因此新增 provider/model 后不必再手工维护本站代码或 `agents.defaults.models`
- 顶部思考级别读取当前 session 的 `thinkingOptions`，切换后写入 `thinkingLevel`
- 输入框键入 `/` 会展示 `commands.list` 返回的指令，支持上下键选择和 Tab 补全
- 输入框左侧回形针可上传附件；最多 5 个、单文件最多 20 MB。浏览器读取为 base64 后随 `chat.send.attachments` 发送
- 图片可由 Gateway 内联送给支持图片的模型；非图片会由 Gateway 保存到 `~/.openclaw/media/inbound/` 并以 `media://inbound/...` 引用交给 agent
- 用户按下发送后立即插入本地 optimistic 消息，不再等待下一轮历史刷新；图片会直接显示在消息气泡里
- Gateway 历史会返回 `MediaPath / MediaPaths / MediaType / MediaTypes`。页面刷新后通过受控 media 接口重新获取图片 Blob，再生成 Object URL 预览
- 当前 Spring Boot 通过一次性 CLI 调用 Gateway，拿不到官方 Control UI WebSocket 的 `chat.delta` 事件。发送后会每 420 ms 跟踪历史，并在同一 assistant 气泡内逐字推进，避免等完整回复后一次性跳出
- 后台轮询会先比较历史签名；内容没有变化时不替换 Vue 消息数组。用户滚动查看旧消息时也不会强制滚到底部，只显示“有新消息，回到底部”提示
- 顶部文件按钮读取 `artifacts.list`，可下载 agent 生成的 artifact。下载按 `artifactId` 受控代理，不允许浏览器指定本机路径
- 重置按钮调用 `sessions.reset(reason=new)` 清空当前会话；不会删除其他会话
- 当前选中会话 key 存在 `sessionStorage`，刷新页面后保持

**迁移到新机器**：

1. 安装 Node.js、Java 17、Maven、npm 依赖和 OpenClaw CLI。当前机器使用 npm 全局安装：

```bash
npm install -g openclaw
cd front && npm install && cd ..
```

Gateway 默认 `bind=loopback (127.0.0.1)`，因此 Spring Boot 后端与 OpenClaw Gateway 必须运行在同一台主机。不要为了省事把 Gateway 直接暴露到公网。
2. 执行 `openclaw setup` 或 `openclaw onboard`，确保 `~/.openclaw/openclaw.json` 已生成。
3. 启动 Gateway：

```bash
openclaw gateway start
openclaw gateway status
```

4. 在项目 `.env.local` 设置：

```bash
ADMIN_KEY=换成强密钥
OPENCLAW_COMMAND=openclaw
OPENCLAW_SESSION_KEY=main
OPENCLAW_TIMEOUT_SECONDS=30
```

**以后新增 OpenClaw 模型**：
1. 把 provider/model 写入 OpenClaw 配置的 `models.providers.<provider>.models`
2. 运行 `openclaw config validate`
3. 运行 `openclaw gateway restart`
4. 网页最多 10 秒自动出现新模型，也可以点击聊天页顶部刷新按钮立即同步

本站后端只把 OpenClaw 配置里显式声明的模型返回浏览器，不会把 `models.list(view=all)` 内置的 200 多个目录模型全部塞进下拉框，也不会返回 provider API key。

5. 启动项目：

```bash
./project.sh start
```

6. 首次发送消息前确认 CLI 有写权限：

```bash
openclaw gateway status
```

期望看到 `Connectivity probe: ok` 和 `Capability: admin-capable`。如果只显示 `Capability: read-only`，先触发一次发送，再查看待审批设备：

```bash
openclaw devices list --json
openclaw devices approve <pending-request-id>
```

OpenClaw `2026.5.28` 在“只读 CLI 给自己升级权限”时可能出现 request id 不断滚动、`unknown requestId` 的自举问题。若普通 `devices approve` 失败，可在 Gateway 主机本地执行：

```bash
OPENCLAW_PAIRING_MODULE="$(npm root -g)/openclaw/dist/device-pairing-C5JfsLW6.js" \
node --input-type=module - <<'JS'
const { l: listDevicePairing, n: approveDevicePairing } = await import(process.env.OPENCLAW_PAIRING_MODULE);
const list = await listDevicePairing();
const pending = list.pending[0];
if (!pending) throw new Error('No pending device pairing requests');
const result = await approveDevicePairing(pending.requestId, { callerScopes: ['operator.admin'] });
console.log(result?.status === 'forbidden' ? result : 'approved');
JS
```

注意：上面路径随 OpenClaw 版本和 npm 全局安装目录变化。优先使用正常的 `openclaw devices approve`；只有确认遇到同一版本自举问题时，才根据新机器实际安装路径调整内部模块路径。

7. 验证站内代理：

```bash
curl -H "X-Admin-Key: $ADMIN_KEY" http://localhost:8080/api/openclaw/sessions
```

如果个人主页需要公网访问，前面再放 HTTPS 反向代理，并限制 `/api/openclaw/*` 的访问来源或增加独立身份认证。当前 `ADMIN_KEY` 是最小可用保护层，不适合作为多用户系统的完整鉴权方案。

**排障**：
- 页面提示检查 Gateway：先运行 `openclaw gateway status`
- 历史可读但发送失败：看错误是否包含 `scope upgrade pending approval`
- `OpenClaw Gateway 响应超时`：确认 Gateway 正常；后端必须并行读取 CLI stdout，避免大段历史填满子进程管道
- CLI 找不到：设置 `OPENCLAW_COMMAND` 为绝对路径，例如 `/Users/you/.npm-global/bin/openclaw`

**为什么有这玩意**：Zotero 直连 API 慢得离谱（16-30s 是常态），不能让用户每次都等。

**怎么工作**：
- `ZoteroCache` 是 `@Component` 单例
- `@PostConstruct` → 后台线程 `warmAsync()` 拉数据 → 写进 `AtomicReference`（原子切换，并发读零锁）
- `@Scheduled(fixedDelay = 5min)` 定时刷新
- 刷新失败保留旧数据（不会清空），日志打 ERROR
- 用户请求都走 `cache.getItems()`，O(1) 内存读

**新鲜度代价**：Zotero 上改了文献，最坏 5 分钟后才反映。可接受。

**调缓存周期**：改 `ZoteroCache.java` 里的 `@Scheduled(fixedDelay = ...)`。

## 前端要点

- `Publications/index.vue` 一个文件 600+ 行，承载所有逻辑（侧栏、过滤、卡片、PDF 展开、导出）。不要拆成多文件除非真的复用
- 一次性拿全部 47 条 items，**前端做过滤分组**（不重复请求接口）
- 渲染懒加载：默认 20 条，`IntersectionObserver` 滚动到底加载下一批
- PDF 懒加载：每张卡片 "查看 PDF" 按钮点击才挂 iframe（`/api/zotero/file/{key}`），再点收起卸载 iframe
- 导出三种：BibTeX / RIS 直接下载 .bib/.ris 文件；APA 复制到剪贴板（HTML 转纯文本）
- 启动时 `warmedUp=false` 会自动 2s 后重试 load（保险机制，应对极端首次冷启动）
- `News/index.vue` 现在是 GitHub 开源项目页，不是新闻页。它只调用本地 `/api/github-projects`，README 也走 `/api/github-projects/{owner}/{repo}/readme`。
- GitHub 项目后台入口在开源项目页右上角“后台管理”，用 `ADMIN_KEY` 登录。可以添加项目、填写 GitHub 地址、分类、说明、首页展示、上移/下移排序、删除。
- GitHub 项目展示顺序按 `.run/github-projects.json` 保存顺序来，不要在前端按 stars 自动重排。首页取前 3 个 `featured=true` 的项目。
- README 渲染仍然在前端用 `marked` + `DOMPurify`，但 README 文本必须由后端代理返回，不能让浏览器直连 GitHub。
- `Franchise/index.vue` 现在是 OpenClaw 网页对话页，不再是招商加盟页。前端不要直连 Gateway WebSocket，也不要接触 Gateway token。

## Git 分支

- `main` → 当前生产版（= zotero-b-custom）
- `zotero-b-custom` → 自建卡片方案（main 同步指向）
- `zotero-a-official` → 官方 `zotero-publications` 嵌入方案（备选保留）

提交风格：`feat: ...` / `feat(B): ...` / `perf: ...`，中文描述。

## 改东西时注意

- **包名拼写 `backen` 不是 `backend`**：建项目时手滑了，现在牵动 Maven、IDE、所有 import，**别改**
- **改了 ZoteroCache 字段处理逻辑**，记得也改前端模板对应字段（前后端字段是手工对齐的，没用 OpenAPI/codegen）
- **加新接口走缓存**：在 `ZoteroCache` 里加 ref 和 refresh 逻辑，不要在 Controller 里直接调 Service（会失去缓存价值）
- **GitHub / Zotero 这类外部数据源，前端一律不直连**：前端只打 `/api/*`，外部 API、raw 文件、重定向、限流降级都放后端处理。
- **OpenClaw 也一样由后端代理**：前端只打 `/api/openclaw/*`，不要把 Gateway token 下发浏览器。
- **OpenClaw 附件不要传本机路径**：浏览器只传文件内容，Gateway 负责落盘和生成安全 media 引用。
- **历史图片恢复必须走受控接口**：只接受 inbound 文件名并校验真实路径，不要新增任意路径文件下载接口。
- **GitHub 项目字段来源**：展示配置在 `.run/github-projects.json`，实时字段由 `GithubProjectService` 补全；保存列表时只保存 repo/highlight/category/featured，不保存 stars 等上游数据。
- **重启后端**：用 `./project.sh restart backend` 而不是 `mvn spring-boot:run`，前者管 PID 和日志
- **前端改完不用重启**：Vite HMR 自动热更
- **写文件别用 cat/echo 重定向**，用专门的写工具（保护 UTF-8 中文）

## 已知小坑

- Zotero 偶尔抽风返回 503，缓存层吞掉错误并保留上一次数据，日志会 ERROR
- 火狐 PDF iframe 内嵌可能有 CSP 问题（Chrome OK，没测过 Safari）
- `useMessage` 依赖 `<n-message-provider>` 包裹，已在 `App.vue` 加了，新页面要用就别拆掉
- **Zotero 服务端把部分附件用 ZIP 打包**（md / 网页快照等），但上游 content-type 撒谎写 `text/plain`。后端 `fetchItemFile` 已处理；如果以后看到附件代理乱码，先看上游字节是不是 `PK\003\004`
- npm 在某些环境下 `~/.npm/_cacache` 会有权限问题，绕过：`npm install --cache /tmp/npm-cache <pkg>`
- OpenClaw 官方 Control UI 禁止 iframe 嵌入；不要再尝试把 `http://127.0.0.1:18789/` 塞进 iframe
- OpenClaw CLI 只读时能读取历史但不能发送；发送报 `scope upgrade pending approval` 时运行 `openclaw devices list --json` 并批准权限升级

## 添加新功能的套路

想加个什么 Zotero 相关功能（比如收藏、笔记、注解）：
1. 看 Zotero API 文档对应字段
2. `ZoteroCache.simplify()` 里加字段提取
3. `ZoteroController` 看是否要新接口
4. 前端 `Publications/index.vue` 模板加渲染
5. 重启后端 → 浏览器刷新

完。
