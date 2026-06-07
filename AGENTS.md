# AGENTS.md

> 给后续接手的 AI / 协作者看的项目说明书。先读这个再动代码。

## 这是什么

个人主页 + Zotero 文献库展示 + GitHub 开源项目展示 + PDF 论文翻译 + PPT 生成 + OpenClaw 网页对话。
- 前端：Vue 3 + Vite + Naive UI
- 后端：Spring Boot 3 + Java 17（包名 `com.web.backen`，目录拼写就是 `backen`，别改）
- 数据源：Zotero Web API v3（用户的私有库）+ GitHub API / raw README + LLM 翻译 API

## 一句话流程

浏览器 → Vite 代理（`/api/*` → 后端 8080）→ Spring Boot → 内存缓存 → 返回。
缓存由后端启动时预热 + 每 5 分钟后台刷新维护，**用户请求不直连 Zotero**。

GitHub 开源项目也一样：浏览器只请求 `/api/github-projects*`，由后端 `GithubProjectService` 去请求 GitHub API / raw README。**前端不要直接 fetch `api.github.com` 或 `raw.githubusercontent.com`**。

## 服务器资源基线

当前生产服务器配置为 **2 核 CPU / 4 GB 内存**。以后本地测试、功能迭代和性能评估都要以这个资源上限为基线，不要默认生产环境有更多 CPU、内存或并发余量。

- 新功能优先选择低内存、低线程数、可控并发的实现，避免无上限线程池、无界队列、大对象长期驻留和一次性全量加载。
- 调整 BabelDOC、LLM、OpenClaw、外部 API 请求或后台任务并发时，要考虑 2 核 CPU 的实际吞吐；不要仅按开发机速度提高 worker、QPS 或并行任务数。
- 涉及大文件、PDF、附件、缓存和历史消息时，关注 4 GB 内存限制，优先流式处理、分页、按需加载、及时释放临时对象和 Blob URL。
- 本地做性能测试时，至少补一轮接近 2 核 / 4 GB 环境的验证；Docker 或其他可控环境可使用类似 `--cpus=2 --memory=4g` 的限制。
- 如果某项功能在 2 核 / 4 GB 下存在明显风险，必须在实现说明、测试结果或维护文档中写明，并给出限流、降级或资源扩容建议。

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
BABELDOC_TIMEOUT_SECONDS=21600

# OpenClaw Gateway 后端代理
OPENCLAW_COMMAND=./scripts/openclaw_compat.sh
OPENCLAW_SESSION_KEY=main
OPENCLAW_TIMEOUT_SECONDS=30
OPENCLAW_NO_RESPAWN=1
NODE_COMPILE_CACHE=/var/tmp/openclaw-compile-cache

# PPT 生成（复用 LLM_* mimo 配置）
PPT_GENERATION_STORAGE_DIR=../.run/ppt-generation-tasks
PPT_GENERATION_MAX_HISTORY=5
PPT_GENERATION_QUEUE_CAPACITY=3
PPT_GENERATION_LLM_MAX_TOKENS=16384
PPT_GENERATION_TIMEOUT_SECONDS=900
PPT_GENERATION_NODE_COMMAND=node
PPT_GENERATION_RUNNER_SCRIPT=./scripts/pptx-generator/generate_deck.mjs
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
    config/PptGenerationConfig.java          @ConfigurationProperties 读 ppt-generation.* 环境变量
    config/OpenClawConfig.java               @ConfigurationProperties 读 openclaw.* 环境变量
    openclaw/
      OpenClawService.java                   调本机 openclaw CLI，代理 chat.* / sessions.* / models.list / commands.list
      OpenClawController.java                网页对话 REST 接口，校验 ADMIN_KEY
    translate/
      PdfParseService.java                   PDFBox 页数读取；旧版文本块提取逻辑暂时保留
      PdfRenderService.java                  翻译 PDF 渲染：原页面矢量导入→白色遮罩→可复制中文译文
      BabelDocService.java                   调 BabelDOC CLI 生成保留版式的纯中文 PDF
      LlmService.java                        调 Anthropic Messages API 翻译，含重试
      TranslationSession.java                单次翻译任务轻量元数据（PDF 只保存磁盘路径）
      TranslationService.java                单 worker 有界队列 + 最近任务落盘 + SSE 状态
      TranslateController.java               REST 接口，上传/开始/流式/状态/下载
    ppt/
      PptInputExtractor.java                 PDF/DOCX 文本抽取、PDF 页图渲染、DOCX 图片抽取、PPTX 模板风格扫描
      PptGenerationSession.java              单次 PPT 生成任务轻量元数据
      PptGenerationService.java              单 worker 有界队列 + LLM deck JSON + Node runner 调度
      PptGenerationController.java           REST 接口，提交/流式/状态/最近/下载
  scripts/pptx-generator/                    PptxGenJS 可编辑 PPTX 生成器，带 package-lock
  scripts/openclaw_compat.sh                  兼容新版 OpenClaw gateway call CLI 格式并启用启动缓存
front/                                       Vue 3 前端
  src/views/Publications/index.vue           文献库主页面（核心，所有 Zotero 展示逻辑都在这）
  src/views/News/index.vue                   GitHub 开源项目页 + 管理后台 + README 弹窗
  src/views/Translate/index.vue              PDF 论文翻译页（上传/配置/进度/结果四态）
  src/views/PptGenerate/index.vue            PPT 生成页，挂在 `/contact` 兼容公网入口
  src/views/Franchise/index.vue              OpenClaw 网页对话页（原招商加盟页）
  src/api/index.js                           getZoteroItems / getZoteroCollections
  src/utils/request.js                       fetch 请求封装
  vite.config.js                             /api 代理到 :8080
project.sh                                   一键启停
deploy/deploy-server-improved.sh             本地测试、打包、上传、服务器安装和健康检查
deploy/deploy-server.sh                      兼容入口，转发到 improved 脚本
deploy/build-release.sh                      仅生成 Linux 发布包
.deploy.local.example                       一键部署参数模板；本地覆盖写到 `.deploy.local`
MAINTENANCE.md                               线上部署状态、维护命令和热修回流记录
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
| `GET /api/translate/recent` | 最近翻译任务与队列状态 | 轻量元数据 |
| `GET /api/translate/download/{taskId}` | 下载纯中文 .txt | 从 BabelDOC PDF 提取 |
| `GET /api/translate/download-pdf/{taskId}?mode=translated\|bilingual` | 下载纯中文或双语 PDF（保留版式、图片和公式）| BabelDOC 缓存 |
| `GET /api/ppt-generate/templates` | 读取内置通用模板：学术蓝、极简黑白、数据绿、暖色答辩 | 内存读 |
| `POST /api/ppt-generate/tasks` | multipart 上传 `prompt`、`templateKey`、可选 `templateFile`、可选 `paperFile`，提交“大纲生成”任务 | 入队轻量返回 |
| `GET /api/ppt-generate/stream/{taskId}?accessToken=...` | SSE 推送 `queued/progress/outline-ready/done/task-error` | 单 worker |
| `GET /api/ppt-generate/status/{taskId}` | 查询 PPT 生成任务状态，需 `X-Ppt-Task-Token` | 内存读 |
| `GET /api/ppt-generate/deck/{taskId}` | 读取生成/编辑后的 deck JSON 大纲，需 `X-Ppt-Task-Token` | 文件读 |
| `PUT /api/ppt-generate/deck/{taskId}` | 保存人工编辑后的 deck JSON 大纲，需 `X-Ppt-Task-Token` | 文件写 |
| `POST /api/ppt-generate/revise/{taskId}` | 根据用户指令修改大纲，需任务令牌并进入单 worker | LLM |
| `POST /api/ppt-generate/render/{taskId}` | 根据确认后的 deck JSON 排队生成 PPTX，需任务令牌 | 单 worker |
| `GET /api/ppt-generate/recent` | 按 `X-Ppt-Task-Tokens` 只返回当前浏览器持有的任务 | 轻量元数据 |
| `GET /api/ppt-generate/download/{taskId}?accessToken=...` | 下载生成的 `.pptx` | 文件流 |

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
1. 选择 PDF 后前端立即进入配置态；后台上传并读取页数 → 返回 taskId + totalPages 后解锁配置
2. 选择页面范围（默认全部）、字体族和速度模式 → 点击"开始翻译"提交后台队列
3. 单 worker 队列依次启动 BabelDOC，SSE 实时推送排队、版面分析、正文翻译、重新排版和 PDF 重建进度
4. 预览或下载翻译 PDF，也可下载从最终 PDF 提取的纯中文 TXT

**页面范围选择**：用户可指定翻译第几页到第几页。选择文件后立即进入配置态；上传和页数读取期间配置项保持禁用，收到总页数后自动解锁，选择起止页码后点击"开始翻译"。

**翻译 PDF 渲染**（`BabelDocService`）：
- 最终排版版 PDF 改由 BabelDOC CLI 生成，不再使用自研 `PdfRenderService` 作为下载链路
- BabelDOC 通过 OpenAI-compatible API 翻译，当前地址为 `https://token-plan-cn.xiaomimimo.com/v1`
- 只输出用户选择的页面范围；一次生成纯中文和双语对照两份 PDF，结果缓存在 `TranslationSession`
- 翻译前可选译文字体族：`auto / serif / sans-serif / script`，对应 BabelDOC `--primary-font-family`
- BabelDOC 不提供固定字号参数；字号由版面引擎根据原文本框自动适配，不要在前端伪造固定字号选项
- 本机需安装 `uv`；默认通过 `uv run --with babeldoc python backen/scripts/babeldoc_runner.py` 调用结构化 runner，首次运行会自动下载 Python 运行时依赖和模型资源
- runner 在收到 BabelDOC `finish` 后主动退出；macOS 上 ONNX/CoreML 可能保留原生后台线程，不能只等待 Python 自然结束
- API Key 通过子进程环境变量传给 runner，不要放进命令行参数，避免本机 `ps` 暴露密钥
- 生产服务器为 2 核 / 4 GB，配置态提供稳定模式（2 QPS）和加速模式（4 QPS），接口默认最多允许 `4 QPS`
- 加速模式运行时后端每 2 秒监控 cgroup 内存、系统可用内存和 swap 使用量；默认阈值为 cgroup `2600 MiB`、系统可用内存 `400 MiB`、swap 已用 `1200 MiB`。`MemoryHigh=2200M` 是 systemd 软限制/节流点，不作为自动终止阈值；真正接近 `MemoryMax=2800M` 前才终止当前 BabelDOC 进程树，并用同一任务、同一页面范围自动按稳定模式重跑一次
- 自动降级只允许一次；如果稳定模式仍触发资源风险，任务直接失败并提示缩小页码范围
- 同一时间只允许一个 BabelDOC 子进程运行，等待队列默认最多 5 个任务，避免并行翻译触发 OOM
- 上传 PDF、纯中文 PDF、双语 PDF 和任务元数据保存在 `.run/translation-tasks/`，JVM 不长期持有大文件 `byte[]`
- 最近已提交翻译任务默认保留 5 条，完成结果可在页面重新打开；仅上传但未开始的 `preview` 任务不显示在最近翻译中，也不占用结果历史名额；`preview` 和 `completed/error` 各自超过保留数后会删除整个任务目录，避免翻译文件长期堆积；后端重启后已完成记录仍可恢复
- BabelDOC 子进程日志只保留最近 64 KB 用于错误诊断，避免长任务日志无限增长
- 生产 systemd 模板将 Spring Boot JVM 堆限制为 `-Xmx768m`，并通过 `MemoryAccounting=yes`、`MemoryHigh=2200M`、`MemoryMax=2800M`、`TasksMax=256` 限制整个后端 cgroup；BabelDOC/Python 子进程也受该上限保护，避免拖死 2 核 / 4 GB 整机
- 生产环境应启用 2 GB swap，`vm.swappiness=10`，作为 BabelDOC 峰值内存缓冲；swap 不是提速手段，翻译仍优先用较小页面范围和低 QPS
- 生产允许 `TRANSLATION_MAX_QPS=4` 保留加速模式入口，但 `TRANSLATION_STABLE_QPS=2`、`BABELDOC_QPS=2` 作为资源保护降级目标和默认稳妥速度
- 资源保护阈值可用 `BABELDOC_RESOURCE_CGROUP_LIMIT_MIB`、`BABELDOC_RESOURCE_MIN_AVAILABLE_MIB`、`BABELDOC_RESOURCE_MAX_SWAP_USED_MIB` 调整；默认分别为 `2600 / 400 / 1200`
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

**任务存储**：`ConcurrentHashMap` 只保存轻量任务元数据，PDF 结果落盘到 `.run/translation-tasks/`。翻译任务不使用 H2；后端重启后会从任务目录恢复最近记录，未完成任务只要原始 PDF 仍在就会重新进入单 worker 队列并从头翻译。

**关键依赖**：`org.apache.pdfbox:pdfbox:3.0.3`（上传页数和中文 TXT 提取）+ `uv run --with babeldoc python`（排版版 PDF runner）。

## PPT 生成功能

`/contact` URL 保留，但产品语义已替换为公开可用的“PPT 生成”工具页；旧联系我们表单不再作为路由入口展示。页面支持三种输入组合：仅提示词、提示词 + 论文、提示词 + 论文 + PPT 模板。PPT 生成前必须先生成 deck JSON 大纲，用户可人工编辑或用对话修改确认后，再生成可编辑 `.pptx`。

**流程**：
1. 前端读取 `/api/ppt-generate/templates`，提供内置模板：学术蓝、极简黑白、数据绿、暖色答辩；用户也可上传自定义 `.pptx` 模板
2. 前端提交 multipart：必填 `prompt`，必带 `templateKey`，可选 `.pptx` 模板和 `.pdf/.docx` 论文
3. 后端保存到 `.run/ppt-generation-tasks/{taskId}/`，返回随机 `accessToken`；前端只在当前标签页 `sessionStorage` 保存任务令牌，任务进入单 worker 有界队列，先只生成 deck JSON 大纲
4. `PptInputExtractor` 用 PDFBox 抽 PDF 文本，并按用户选择的提取比例在整篇 PDF 中均匀采样页面，以 96 DPI 渲染成 PNG 页面图；PDF 表格随页面图进入图片 manifest；DOCX 使用两遍 ZIP 读取，第一遍收集正文、表格候选和媒体数量，第二遍抽真实论文图片，再用剩余额度渲染表格，避免表格先占满配额后丢失 `word/media` 图片；PPTX 模板会按比例扫描最多 16 页 framework（页角色、文本/图片/表格密度、文字样本）并复制最多 24 个媒体素材
5. `LlmService.complete(systemPrompt, userPrompt, maxTokens)` 调 mimo 输出结构化 deck JSON，不污染论文翻译专用 prompt
6. 前端显示大纲编辑器和 16:9 幻灯片预览卡，允许人工修改每页类型、标题、核心句、要点和讲稿备注；这不是 PowerPoint 形状级编辑器，但可重新生成可编辑 PPTX
7. 前端“对话修改”调用 `/api/ppt-generate/revise/{taskId}`，让 mimo 基于当前 deck JSON 和用户指令返回新版 deck JSON
8. 用户确认后调用 `/api/ppt-generate/render/{taskId}`，`backen/scripts/pptx-generator/generate_deck.mjs` 用 PptxGenJS 生成可编辑 PPTX；若用户选择“母版填充”且上传了 PPTX 模板，则 runner 直接以模板 PPTX 为底稿替换 slide XML 文本，保留原模板元素尺寸和位置；生成后仍保留大纲预览，可继续编辑/对话修改并重新生成
9. 同一任务允许反复渲染。渲染前先用 `asset-cache.json` 判断论文文件、模板文件、提取比例和模板模式是否变化；未变化时复用已抽取的 `images/`、`template-assets/`、`style.json` 和 `image-manifest.json`，不再重复抽取。发生变化时才刷新论文图片素材和模板素材，重新生成 `image-manifest.json`，再调用 Node runner；重新抽取 DOCX/PDF 图片或 PPTX 模板素材前必须清理旧的 `images/`、`template-assets/` 抽取文件，复制时允许覆盖，避免第二次渲染因 `template-asset-1.png` 已存在而失败，也避免旧任务复用过期图片素材

**资源限制**：
- 公开入口不要求 `ADMIN_KEY`，但每个任务都有独立随机访问令牌；除创建和模板列表外，读取、修改、渲染、下载和最近任务接口都必须校验令牌
- 普通任务 API 用 `X-Ppt-Task-Token`，最近任务用 `X-Ppt-Task-Tokens`；浏览器原生 `EventSource` 和下载窗口无法设置自定义 header，因此 SSE/下载使用 `accessToken` 查询参数
- 默认提示词最大 8000 字符，论文/模板各最大 30 MB，队列容量 3，最近记录 5 条
- PDF 论文会按 `PPT_GENERATION_MAX_EXTRACTED_IMAGES` 与用户提取比例在全文中均匀采样若干页，渲染为 96 DPI PNG，供视觉模型摘要和 PPTX 图文页引用；不要提高 DPI 或无上限渲染全 PDF，除非重新按 2 核 / 4 GB 做过内存验证
- DOCX 的论文原图和渲染表格共享 `PPT_GENERATION_MAX_EXTRACTED_IMAGES` 总预算，但必须混合保留；有 `word/media` 时默认约 60% 额度优先给真实图片，其余给表格。视觉模型不能把后端已成功渲染的 `paper-table-*` / `paper-excel-*` 全部判为不可用，表格最低保持 `useful=true / importance=3 / full-image`
- 如果 mimo 返回的大纲完全没有或只分配了少量 `imageId`，渲染前后端会把尚未使用的高价值图片/表格受控分配到方法、数据、实验、结果等相关内容页；不要恢复为按页轮播或无语义随机配图
- PPT 大纲生成默认 `PPT_GENERATION_LLM_MAX_TOKENS=16384`，后端还会按目标页数动态估算输出 token，上限 32768；如果 mimo 返回的 deck JSON 疑似被截断（如缺少数组/对象闭合），后端会自动用紧凑 JSON prompt 重试，优先保证完整可解析的大纲
- 同一时间只运行一个大纲生成、对话修改或 PPTX 渲染任务；对话修改也必须进入同一个有界单 worker，不能在 HTTP 请求线程直接并发调用 LLM
- 同一任务同时只允许一个写操作；重复渲染、处理中保存或修改会被拒绝。队列拒绝时必须恢复原状态和原 deck，不能留下永久 `queued` 任务
- 任务只在 JVM 保存轻量元数据，上传文件、抽取图片、deck JSON 和 PPTX 结果都落盘到 `.run/ppt-generation-tasks/`
- 后端重启后 `queued/outlining/rendering` 任务会标记为 error，需要用户重新提交；`outline_ready/completed/error` 结果按最近任务清理策略保留
- 内置模板提供通用风格预设并锁定成品配色；自定义 PPTX 模板支持两种模式：`framework` 复用页面骨架、装饰节奏和媒体素材；`template-fill` 直接在模板 PPTX slide XML 上替换文本，尽量保留原元素尺寸位置。两者都不覆盖用户一开始选择的通用模板色板；`template-fill` 是文本替换优先，不保证图片占位符智能替换。母版填充扩页时可以复用共享图片/图表关系，但必须移除 notes/comments 等单页唯一关系，不能让多页引用同一 notesSlide
- PPTX 模板色彩扫描只能读取真实 OOXML 色值属性，如 `srgbClr val="xxxxxx"` / `color="xxxxxx"`；不要用裸 `[0-9A-Fa-f]{6}` 全文匹配，否则会把坐标、尺寸、编号误识别为色值，导致蓝白模板跑偏成棕绿等事故
- Node runner 会把可用图片预读为 data URI；不可读取、格式异常或缺失的图片只记录 warning 并跳过，不能让单张素材拖垮整份 PPTX

**部署依赖**：
- 生产机器除了 Java 17、Node.js、npm，还要能在安装阶段执行 `npm ci --omit=dev --prefix backen/scripts/pptx-generator`；runner 显式依赖 `pptxgenjs`、`image-size` 和 `jszip`
- `deploy/install-linux.sh` 会安装 runner 依赖；`deploy/build-release.sh` 会复制 `backen/scripts`，但会剔除任何本地 `node_modules`
- `project.sh start backend` 会在本地缺少 `scripts/pptx-generator/node_modules` 时自动安装 runner 依赖
- 如果 npm 缓存权限异常，仍按项目旧规则使用 `/tmp` 或脚本内 `/tmp/web-homepage-npm-cache`

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
- 当前聊天页空闲时每 5 秒轮询历史；不是 Gateway WebSocket 直推

**聊天页功能**：
- 左侧对话栏读取 Gateway 的真实 sessions，可新建、搜索和切换会话
- 顶部模型选择器读取 `models.list`，切换后写入当前 session override
- 会话侧栏约每 15 秒后台刷新，模型列表约每 60 秒后台刷新；顶部刷新按钮也会同时刷新历史、会话和模型
- 站内模型列表不是直接使用 OpenClaw 默认白名单。后端调用 `models.list(view=all)`，再与 Gateway `config.get` 中 `models.providers.*.models` 的显式配置取交集；因此新增 provider/model 后不必再手工维护本站代码或 `agents.defaults.models`
- 顶部思考级别读取当前 session 的 `thinkingOptions`，切换后写入 `thinkingLevel`
- 输入框键入 `/` 会展示 `commands.list` 返回的指令，支持上下键选择和 Tab 补全
- 输入框左侧回形针可上传附件；最多 5 个、单文件最多 20 MB。浏览器读取为 base64 后随 `chat.send.attachments` 发送
- 图片可由 Gateway 内联送给支持图片的模型；非图片会由 Gateway 保存到 `~/.openclaw/media/inbound/` 并以 `media://inbound/...` 引用交给 agent
- 用户按下发送后立即插入本地 optimistic 消息，不再等待下一轮历史刷新；图片会直接显示在消息气泡里
- Gateway 历史会返回 `MediaPath / MediaPaths / MediaType / MediaTypes`。页面刷新后通过受控 media 接口重新获取图片 Blob，再生成 Object URL 预览
- 当前 Spring Boot 通过一次性 CLI 调用 Gateway，拿不到官方 Control UI WebSocket 的 `chat.delta` 事件。发送后会每 750 ms 跟踪历史，并在同一 assistant 气泡内逐字推进，避免等完整回复后一次性跳出
- 后台轮询会先比较历史签名；内容没有变化时不替换 Vue 消息数组。用户滚动查看旧消息时也不会强制滚到底部，只显示“有新消息，回到底部”提示
- 2 核 / 4 GB 服务器上每次 OpenClaw CLI 冷启动约需 1 秒 CPU 时间，200 Mbps 带宽不是主要瓶颈。后端必须限制同时启动的 CLI 数量，并缓存高频只读调用
- 当前默认最多并发 2 个 CLI；历史缓存 1 秒、会话缓存 5 秒、模型缓存 60 秒、指令缓存 5 分钟。相同缓存未命中会合并为一次 CLI 调用，写操作后主动失效相关缓存
- 页面空闲历史轮询为 5 秒，会话约 15 秒、模型约 60 秒刷新；不要恢复为高频空闲轮询，否则会持续抢占服务器 CPU
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
OPENCLAW_COMMAND=./scripts/openclaw_compat.sh
OPENCLAW_SESSION_KEY=main
OPENCLAW_TIMEOUT_SECONDS=30
OPENCLAW_NO_RESPAWN=1
NODE_COMPILE_CACHE=/var/tmp/openclaw-compile-cache
OPENCLAW_MAX_CONCURRENT_CALLS=2
OPENCLAW_HISTORY_CACHE_MILLIS=1000
OPENCLAW_SESSIONS_CACHE_MILLIS=5000
OPENCLAW_MODELS_CACHE_MILLIS=60000
OPENCLAW_COMMANDS_CACHE_MILLIS=300000
```

`openclaw_compat.sh` 会把旧后端使用的 `openclaw gateway --json --params ...` 调用格式转换为新版 CLI 的 `openclaw gateway call ...`，并减少 CLI 重复启动开销。站内聊天页同时支持 `/franchise` 和 `/openclaw`。

**以后新增 OpenClaw 模型**：
1. 把 provider/model 写入 OpenClaw 配置的 `models.providers.<provider>.models`
2. 运行 `openclaw config validate`
3. 运行 `openclaw gateway restart`
4. 网页最多 60 秒自动出现新模型，也可以点击聊天页顶部刷新按钮立即同步

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
- Zotero 文献列表和 collections 缓存只存在 JVM 内存中，不写入 `.run/` 或数据库；附件代理也是按请求临时拉取并直接返回，不持久化到磁盘，因此不会导致硬盘爆满

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
- `PptGenerate/index.vue` 挂在 `/contact`，导航、首页 CTA、页脚工具入口都按“PPT 生成”语义展示；不要恢复旧 Contact 表单。PPT 页面必须保持“大纲确认后再生成 PPTX”的两阶段体验，避免用户无法干预直接生成成品。
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
- **PPT 生成不要前端直连 LLM 或本地脚本**：浏览器只打 `/api/ppt-generate/*`，PptxGenJS runner、模板解析、论文抽取和 LLM 调用都在后端完成。
- **PPT 模板 framework mode**：用户上传 PPTX 时，后端应按 `extractionPercent` 扫描最多 16 页 framework（cover/contents/section/content/image/comparison/thanks 倾向、文本/图片/表格密度、文字样本）并复制最多 24 个媒体素材；runner 应按这些骨架循环套用封面、章节、目录和内容页，尽量复用模板框架感，但生成文本和图形仍保持可编辑。
- **PPT 母版填充模式**：`templateMode=template-fill` 必须要求用户上传 PPTX 模板。runner 直接读取模板 zip，用 deck JSON 替换各 slide XML 的 `<a:t>` 文本，保留原 shape/media/位置/尺寸；页数不足时复制最后一页模板 slide，页数过多时 presentation 只引用所需页。不要在这个模式下把模板元素抽出后重新缩放摆放。
- **PPT 上传模板默认母版填充**：前端一旦选择 `.pptx` 模板，默认把 `templateMode` 切到 `template-fill`；用户仍可手动切回 `framework`。不要让“模板模式”默认走普通 PptxGenJS 重绘，否则用户会误以为系统直接改模板但实际输出不是原模板。
- **PPT 提取比例**：`extractionPercent` 由前端传入，范围 10-100。它控制 PDF 页面图数量、DOCX 图片数量、PPTX framework 页数和模板媒体素材上限；不要恢复成固定少量素材，也不要无视 2 核 / 4 GB 基线无上限提取。
- **PPT 模板不覆盖通用配色**：用户选择的通用模板色板仍是成品统一配色来源；上传 PPTX 的扫描色值不要覆盖 `builtInTemplate` palette，避免用户选了学术蓝又被模板扫描色带跑偏。也不要在文案或接口中承诺高保真母版复制。
- **PPT 人工编辑边界**：当前前端编辑的是 deck JSON（页标题、核心句、要点、备注等）并用预览卡展示效果，不是浏览器内 PowerPoint 形状级编辑；修改后要重新生成 PPTX。
- **PPT 重复渲染必须幂等**：不要把 `Files.copy()` 默认“不覆盖”的行为带回图片/模板抽取链路；同一 taskId 可能多次点击生成 PPTX。
- **PPT 重新生成优先复用素材**：用户在已有大纲上点“重新生成 PPTX”时，后端先比较 `asset-cache.json`。论文文件、模板文件、提取比例和模板模式都没变时，只重跑 runner，不再重新抽取论文/模板素材；变化时才重新抽取论文 PDF/DOCX 图片、重扫模板并更新 `image-manifest.json`。
- **PPT 刷新素材不改大纲**：重新生成 PPTX 阶段的 `refreshing_assets` 只更新 `images/`、`template-assets/`、`style.json` 和 `image-manifest.json`，不修改用户确认过的 `deck.json`。如果 deck 引用了 `imageId`，刷新素材可能影响图片 ID 对应的底层素材，但不会改变页数、标题、正文、要点或备注。
- **PPT 表格素材**：DOCX 正文表格要转成 `paper-table-*.png` 进入图片素材；DOCX 内嵌的 `word/embeddings/*.xlsx` 至少读取第一个 worksheet 和 shared strings，转成 `paper-excel-*.png`。表格 fallback manifest 应标记为 `kind=table`、`useful=true`、`importance=4`，避免视觉模型失败时表格图片被丢弃。
- **PPT 母版填充字号**：`template-fill` 只替换 slide XML 文本，不应把 notes 写入模板形状；当新文本明显长于原占位文本时，runner 要压缩对应 run 的字号，减少模板错位和溢出。
- **PPT 不要硬塞图片**：普通内容页只有在 deck JSON 明确给出 `imageId`、`imageHint`，或版式是 `image/full-image/image-left/image-right` 时才放论文图；不要因为任务目录里有图片就轮播填充每一页。答辩稿需要图文节奏，很多页应该是指标、对比、结论或纯文本 synthesis。
- **PPT 图片必须先识图筛选**：视觉摘要 manifest 要包含 `useful`、`importance`、`layoutHint`。低价值图、空白图、重复图、纯装饰图、难以阅读的小公式/截图要标记 `useful=false` 或低 importance；deck JSON 只有明确引用有用 `imageId` 时才放图。
- **PPT 图片保持原始比例**：Node runner 放图必须用等比 contain 计算位置，居中放入图框；不要拉伸、裁切或强行铺满固定区域。根据 `layoutHint`、图片宽高比和版式选择 `image-left/image-right/full-image`，不要所有图都放同一个右侧位置。
- **PPT 禁止文不对图**：LLM 选择 `imageId` 时，页面标题/核心句必须和 manifest 的 `title/summary/bestUse` 直接对应；没有对应关系就留空 `imageId`。runner 不做自动轮播兜底。
- **PPT 页数限制要压缩不要截断**：如果用户提示词要求 8 页、12 页等固定页数，后端要把 `targetSlideCount` 传给 LLM，让它在完整叙事上合并压缩内容；如果首次大纲页数不匹配，后端要再让 LLM 按精确页数纠偏。runner 不允许再用 `slice()` 偷偷截断前 N 页来满足页数。
- **PPT LLM JSON 要容错**：mimo 偶尔会把合法 JSON 字符串边界输出成中文弯引号 `“...”`，导致 Jackson 报 `Unexpected character ('“')`。PPT 大纲、视觉摘要和对话修改的 LLM 返回必须走统一容错解析，先正常 parse，失败后只修复字符串边界上的智能引号再重试；不要把正文里的中文引号无脑全局替换。
- **PPT 页眉不要用论文编号**：deck JSON 的 `section` 应使用 `BACKGROUND/ROUTE/DATA/FEATURES/MODELS/RESULT/SCALE/CONCLUSION/OUTLOOK` 等语义标签；runner 遇到 `1.1/2.3` 这类数字 section 要降级为语义页眉，避免成品像论文目录搬运。
- **重启后端**：用 `./project.sh restart backend` 而不是 `mvn spring-boot:run`，前者管 PID 和日志
- **前端改完不用重启**：Vite HMR 自动热更
- **文档同步是上线条件**：每次修改功能、接口、配置、部署流程或线上行为，都必须在同一轮任务中同步更新 `AGENTS.md` 或 `MAINTENANCE.md`。其中长期有效的架构和协作规则写入 `AGENTS.md`，本次上线内容、验证结果和排障记录写入 `MAINTENANCE.md`。不能只改代码后部署。
- **上线收尾**：本地验收完成后运行 `./deploy/deploy-server-improved.sh`。当前公网入口为 `https://shimmer.help/`。脚本保留 `/etc/web-homepage/web.env` 和服务器已有 Nginx 配置，避免覆盖 Certbot SSL；换服务器参数或 SSH 私钥绝对路径写入被忽略的 `.deploy.local`，不要把密码、私钥文件或 API Key 放进项目
- **发布包清理**：一键部署完成后，远端 `/home/admin/.web-homepage-releases/` 默认只保留最近 3 个 `web-homepage-*.tar.gz` 发布包和最近 3 个 `web-homepage-backup-*.tar.gz` 备份包；不要把这个目录当长期归档用，需要长期保留的备份应移到单独位置
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
