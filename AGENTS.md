# AGENTS.md

> 给后续接手的 AI / 协作者看的项目说明书。先读这个再动代码。

## 这是什么

个人主页 + Zotero 文献库展示 + GitHub 开源项目展示 + PDF 论文翻译 + PPT 生成。
- 前端：Vue 3 + Vite + Naive UI
- 后端：Spring Boot 3 + Java 17（包名 `com.web.backen`，目录拼写就是 `backen`，别改）
- 数据源：Zotero Web API v3（用户的私有库）+ GitHub API / raw README + LLM 翻译 API

## 一句话流程

浏览器 → Vite 代理（`/api/*` → 后端 8080）→ Spring Boot → 内存缓存 → 返回。
缓存由后端启动时预热 + 每 5 分钟后台刷新维护，**用户请求不直连 Zotero**。

GitHub 开源项目也一样：浏览器只请求 `/api/github-projects*`，由后端 `GithubProjectService` 去请求 GitHub API / raw README。**前端不要直接 fetch `api.github.com` 或 `raw.githubusercontent.com`**。

生产服务器上游出站代理已显式接入 Clash Verge / Mihomo：`verge-mihomo` 使用 `/home/admin/mihomo-local.yaml`，`mixed-port: 7890`，实际监听 `*:7890`；`web-backen.service` 通过 systemd drop-in `/etc/systemd/system/web-backen.service.d/10-outbound-proxy.conf` 设置 `JAVA_TOOL_OPTIONS=-Xms128m -Xmx768m -XX:+UseG1GC -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。这让 Spring Boot 对 Zotero/S3、GitHub API 和 raw README 的 HTTPS 出站请求走本机代理。发布脚本重装 service 模板后要确认 drop-in 仍存在并执行 `systemctl daemon-reload && systemctl restart web-backen`；不要只依赖 shell 里的 `HTTP_PROXY/HTTPS_PROXY`，Java 进程未必读取。

## 服务器资源基线

当前生产服务器配置为 **2 核 CPU / 4 GB 内存**。以后本地测试、功能迭代和性能评估都要以这个资源上限为基线，不要默认生产环境有更多 CPU、内存或并发余量。

- 新功能优先选择低内存、低线程数、可控并发的实现，避免无上限线程池、无界队列、大对象长期驻留和一次性全量加载。
- 调整 BabelDOC、LLM、外部 API 请求或后台任务并发时，要考虑 2 核 CPU 的实际吞吐；不要仅按开发机速度提高 worker、QPS 或并行任务数。
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
ADMIN_KEY=...  # GitHub 管理后台

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


# PPT 生成（复用 LLM_* mimo 配置）
PPT_GENERATION_STORAGE_DIR=../.run/ppt-generation-tasks
PPT_GENERATION_MAX_HISTORY=5
PPT_GENERATION_QUEUE_CAPACITY=3
PPT_GENERATION_LLM_MAX_TOKENS=16384
PPT_GENERATION_VISION_MODEL=mimo-v2.5
PPT_GENERATION_VISION_MAX_TOKENS=4096
PPT_GENERATION_TIMEOUT_SECONDS=900
PPT_GENERATION_RENDERER_COMMAND="uv run --with python-pptx --with pillow python"
PPT_GENERATION_RENDERER_SCRIPT=./scripts/ppt_renderer.py
PPT_GENERATION_TEMPLATE_FILL_COMMAND="uv run --with python-pptx python"
PPT_GENERATION_TEMPLATE_FILL_SCRIPT=./scripts/ppt-template-fill/template_fill_pptx.py
PPT_GENERATION_PAPER_PARSER_COMMAND="uv run --with docling --with markitdown python"
PPT_GENERATION_PAPER_PARSER_SCRIPT=./scripts/ppt_document_parser.py
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
      PptGenerationService.java              单 worker 有界队列 + 上传模板原生填充 / 无模板 Python renderer 直出 PPTX
      PptGenerationController.java           REST 接口，提交/流式/状态/最近/下载
  scripts/ppt_renderer.py                    python-pptx 直出 PPTX renderer
  scripts/ppt-template-fill/                 PPT Master template_fill_pptx 最小化拷贝，分析并原生填充上传 PPTX 模板
  scripts/ppt_document_parser.py              Docling / MarkItDown 论文解析中间层
front/                                       Vue 3 前端
  src/views/Publications/index.vue           文献库主页面（核心，所有 Zotero 展示逻辑都在这）
  src/views/News/index.vue                   GitHub 开源项目页 + 管理后台 + README 弹窗
  src/views/Translate/index.vue              PDF 论文翻译页（上传/配置/进度/结果四态）
  src/views/PptGenerate/index.vue            PPT 生成页，挂在 `/contact` 兼容公网入口
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
| `POST /api/translate/upload` | 上传 PDF，返回 taskId + totalPages（不立即翻译）| PDFBox 解析 |
| `POST /api/translate/start/{taskId}?startPage=1&endPage=10&fontFamily=auto&qps=8` | 指定页面范围、译文字体族和并发速度并准备翻译 | 内存写入 |
| `GET /api/translate/stream/{taskId}` | SSE 启动 BabelDOC 并推送状态（layout/progress/done/error）| BabelDOC 单链路 |
| `GET /api/translate/status/{taskId}` | 查询任务状态（断线重连用）| 内存读 |
| `GET /api/translate/recent` | 最近翻译任务与队列状态 | 轻量元数据 |
| `GET /api/translate/download/{taskId}` | 下载纯中文 .txt | 从 BabelDOC PDF 提取 |
| `GET /api/translate/download-pdf/{taskId}?mode=translated\|bilingual` | 下载纯中文或双语 PDF（保留版式、图片和公式）| BabelDOC 缓存 |
| `GET /api/ppt-generate/templates` | 读取内置通用模板：学术蓝、极简黑白、数据绿、暖色答辩 | 内存读 |
| `POST /api/ppt-generate/tasks` | multipart 上传 `prompt`、`templateKey`、可选 `templateFile`、可选 `paperFile`，提交并直接生成 PPTX | 入队轻量返回 |
| `GET /api/ppt-generate/stream/{taskId}?accessToken=...` | SSE 推送 `queued/progress/done/task-error`，不再输出大纲事件 | 单 worker |
| `GET /api/ppt-generate/status/{taskId}` | 查询 PPT 生成任务状态，需 `X-Ppt-Task-Token` | 内存读 |
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
- **流式转发**：附件代理必须用 `BodyHandlers.ofInputStream()` 边读边传，不能先用 `byte[]` 等完整文件下载完再响应。生产服务器到 Zotero/S3 可能只有十几 KB/s，完整缓冲会触发请求超时，也会让大 PDF 长时间占用 JVM 堆；ZIP 附件同样通过 `ZipInputStream` 流式解出第一个文件
- **附件预览进度**：文献页查看 PDF 时，前端用 `ReadableStream` 读取 `/api/zotero/file/{key}`，根据实际收到的字节、代理保留的 `Content-Length` 显示下载百分比、大小和速度；进度条不得启用与字节进度无关的 `processing` 动画。附件响应带 `X-Accel-Buffering: no`，避免 Nginx 缓冲造成进度跳跃；完成后才创建 Blob URL 交给 iframe。取消、收起或组件卸载时必须中止请求并 `URL.revokeObjectURL()`

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
- 上传预览阶段会用 `PdfParseService.analyzeTextQuality()` 检查前几页文本层；如果英文文本层疑似字体映射/ToUnicode 乱码（例如复制出 `Sgd bnlodshshnm...` 这类偏移字符），前端提示用户重点检查预览，但仍允许启动翻译。`babeldoc_runner.py` 的 `RepairingOpenAITranslator` 会在送模型前对每段文本做英文质量评分，只在偏移修复明显改善时替换输入，避免正常 PDF 被误改；如果自动修复后预览仍不理想，再换可正常复制英文的版本或先 OCR/重新导出
- 翻译前可选译文字体族：`auto / serif / sans-serif / script`，对应 BabelDOC `--primary-font-family`
- BabelDOC 不提供固定字号参数；字号由版面引擎根据原文本框自动适配，不要在前端伪造固定字号选项
- 参考文献排版由 `babeldoc_runner.py` 的兼容层修正：当 BabelDOC 把连续编号参考文献合并成一个段落时，按 `[1]` / `(1)` / `1.` 等连续序号重新拆分；译文中带年份、DOI、URL、期刊等文献特征的编号段落使用两个中文字符宽度的悬挂缩进。普通编号列表不得套用该规则
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
- 下载命名必须以原上传 PDF 文件名为前缀：`原名-翻译版.pdf`、`原名-双语对照版.pdf`、`原名-翻译结果.txt`；后端负责清理路径分隔符、控制字符和 `.pdf` 后缀，前端不要硬编码通用文件名
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

`/contact` URL 保留，但产品语义已替换为公开可用的“PPT 生成”工具页；旧联系我们表单不再作为路由入口展示。页面支持三种输入组合：仅提示词、提示词 + 论文、提示词 + 论文 + PPT 模板。当前流程是直接生成 PPTX：不再把 deck JSON 大纲暴露给前端，不再提供大纲编辑、对话修改或二次渲染接口。

**流程**：
1. 前端读取 `/api/ppt-generate/templates`，提供内置模板：学术蓝、极简黑白、数据绿、暖色答辩；用户也可上传自定义 `.pptx` 模板作为布局底稿
2. 前端提交 multipart：必填 `prompt`，必带 `templateKey`，可选 `.pptx` 模板和 `.pdf/.docx` 论文
3. 后端保存到 `.run/ppt-generation-tasks/{taskId}/`，返回随机 `accessToken`；前端只在当前标签页 `sessionStorage` 保存任务令牌，任务进入单 worker 有界队列
4. `PptInputExtractor` 用 Docling / MarkItDown 优先抽取论文结构，失败回退 PDFBox / DOCX ZIP；PDF 会按提取比例均匀渲染页面图，DOCX 会混合保留原图和表格截图；PPTX 模板会扫描文本样本、配色和槽位信息
5. `LlmService.complete(systemPrompt, userPrompt, maxTokens)` 调 mimo 输出内部 PPT 结构 JSON；该 JSON 只给后端生成器使用，不作为用户可编辑产物
6. 没有上传 PPTX 模板时，`PptGenerationService` 对图片 manifest 做视觉摘要和兜底分配，再调用 `backen/scripts/ppt_renderer.py` 用 `python-pptx` 自由生成 `.pptx`
7. 上传 PPTX 模板时，后端先调用 `backen/scripts/ppt-template-fill/template_fill_pptx.py analyze` 生成 `slide-library.json`，再让 mimo 输出 `template_fill_pptx_plan.v1` fill plan，经过 `check-plan` 检查/必要时修复后调用 `apply --transition keep` clone 原模板页并替换 OOXML 文本，尽量保留模板原布局、字体、图片、表格和装饰
8. SSE 只推送 `queued/progress/done/task-error`；完成后前端进入结果态并下载 PPTX

**资源限制**：
- 公开入口不要求 `ADMIN_KEY`，但每个任务都有独立随机访问令牌；状态、下载和最近任务接口都必须校验令牌
- 普通任务 API 用 `X-Ppt-Task-Token`，最近任务用 `X-Ppt-Task-Tokens`；浏览器原生 `EventSource` 和下载窗口无法设置自定义 header，因此 SSE/下载使用 `accessToken` 查询参数
- 默认提示词最大 8000 字符，论文/模板各最大 30 MB，队列容量 3，最近记录 5 条
- PDF 论文会按 `PPT_GENERATION_MAX_EXTRACTED_IMAGES` 与用户提取比例采样页面，渲染为 96 DPI PNG；当前默认提取/视觉候选上限为 24。采样优先选择包含 `Figure/Fig./图/表/caption`、流程、架构、实验结果、对比、可视化等线索的页面，再用均匀采样补足预算。20-30 页答辩 PPT 即使默认提取比例为 50%，也应在配置上限内尽量保留 15-25 张候选视觉素材；不要提高 DPI 或无上限渲染全 PDF，除非重新按 2 核 / 4 GB 做过内存验证
- DOCX 的论文原图和渲染表格共享 `PPT_GENERATION_MAX_EXTRACTED_IMAGES` 总预算，但必须混合保留；有 `word/media` 时默认约 60% 额度优先给真实图片，其余给表格。Docling / MarkItDown 解析成功后复制出的图片也必须在 Java 端再次按动态预算裁剪，不能全量复制到任务 `images/`。视觉模型不能把后端已成功渲染的 `paper-table-*` / `paper-excel-*` 全部判为不可用，表格最低保持 `useful=true / importance=3 / full-image`
- 图片视觉初筛默认使用 `PPT_GENERATION_VISION_MODEL=mimo-v2.5`，通过 `LlmService.completeWithImages()` 把抽取图片以 base64 image block 发送到 Anthropic Messages 兼容接口；图片超过 8 张时必须分批调用视觉模型并合并 `image-manifest.json`，避免一次性塞入 20+ 图片导致响应截断或超时。单批失败时只对失败批次启发式 fallback，已成功批次要保留；`image-manifest.json` 顶层 `source=vision` 表示模型初筛全部成功，`source=partial` 表示部分批次使用 fallback，`source=fallback` 表示模型调用失败后启发式兜底，`source=generated` 表示只有后端生成的视觉图，`source=none` 表示本次没有图片输入
- fallback manifest 必须保守：普通 `paper-page-*` 整页截图、纯文字页、空白页、截图或缺少 caption/figure/result/workflow/architecture/table 等强证据的图片，默认不能直接越过 `useful=true && importance>=3` 的硬门槛；可信表格图除外。vision 返回缺项时也不能默认高可用，只能按保守 fallback 或低分处理
- 如果 mimo 返回的内部结构没有或只分配了少量 `imageId`，后端会把尚未使用的高价值图片/表格受控分配到方法、数据、实验、结果等相关内容页；不要恢复为按页轮播或无语义随机配图
- 渲染前必须再次校验所有 `imageId`：只有 manifest 中 `useful=true` 且 `importance>=3` 的图片能进入 PPTX；LLM 主结构里误填的低价值、不可用或不存在图片 ID 要清空，避免绕过初筛
- 上传 PPTX 模板时，模板只能作为视觉风格参考：继承背景底板、页面配色、标题/字体风格、整体布局、页眉页脚、校徽和装饰线条；不得复用模板原正文、示例占位文字、原图片、原图表数据或示例表格内容。template-fill apply 默认启用源内容清理，未替换旧文字会清空，非装饰内容图/表/图表会移除，并同步删除对应 slide relationships，确保旧 media/chart 不再作为可达资源打包进输出 PPTX
- template-fill 的 `check-plan` / repair report 不得把模板原 `old_text` 暴露给 LLM；只能给长度、容量、槽位角色、几何和错误信息。strip 模式的装饰图识别必须按 token/明确名称判断，不能让 `Online`、`pipeline`、`baseline` 这类词因为包含 `line` 而误保留旧正文图片
- 上传 PPTX 模板时应尽量使用论文图片/表格素材，但只能填充经过 `template_fill_pptx analyze` 和 Java 后端双重过滤后仍可填的 `image_regions.region_id`。mimo fill plan 的 `image_edits` 必须引用真实 `imageManifest.images[].id` 和现有可填 `region_id`；后端会校验 `image_id`、`region_id`、素材质量和区域角色，并把未使用的高价值图片只受控补到可填内容图框。背景、logo、图标、装饰、小图，以及 cover/toc/chapter/ending 页图片区域都不能作为论文图片填充目标；如果模板没有可填内容图框，系统应只替换文字并记录/说明原因
- 模板图片区域必须保守筛选：`template_fill_pptx analyze` 会给 `image_regions` 标记 `fillable/role/rejectReason`；Java 后端还会二次过滤，不能只信 mimo 的 `image_edits`
- 上传模板 fill plan 不能一次性让 mimo 输出很长的 20+ 页 JSON：后端会先压缩论文文本和模板 slide library，只给主要槽位/可填大图区域；目标页数超过 8 页时按约 6 页一批规划，再合并成最终 `fill-plan.json`，避免超长响应卡住或输出坏 JSON。大页数模板任务会更慢，SSE/progress 应显示当前规划批次和总批次
- 无模板自由生成不能只重复 `image-left/image-right/full-image` 三种图位；prompt 和 renderer 都应保留 `image-top/evidence-strip/metric-hero/comparison/matrix/timeline` 等版式，让相邻内容页有明显节奏变化。论文图片不足时，LLM 可输出 `visualSpec` / `generated_visual`，后端用确定性 Java2D 生成学术风流程图、结构框图、矩阵或对比图 PNG，再作为普通高价值图片进入 manifest 和模板/自由渲染链路
- PPT 结构生成默认 `PPT_GENERATION_LLM_MAX_TOKENS=16384`，后端还会按目标页数动态估算输出 token；如果 mimo 返回的 JSON 疑似被截断或模板 fill plan 出现 `Unexpected character` 等坏 JSON，后端会自动用紧凑 JSON prompt 重试，并把原始坏响应落到任务目录便于诊断
- 同一时间只运行一个 PPT 生成任务；不要在 HTTP 请求线程直接并发调用 LLM、renderer 或 template-fill 脚本
- 任务只在 JVM 保存轻量元数据，上传文件、抽取图片、内部结构 JSON、图片 manifest 和 PPTX 结果都落盘到 `.run/ppt-generation-tasks/`
- 后端重启后 `queued/generating/rendering` 任务会标记为 error，需要用户重新提交；`completed/error` 结果按最近任务清理策略保留
- 从旧版两段式流程遗留的 `outline_ready/outlining/revising` 等非终态任务，后端加载历史时也必须标记为 error，不能让前端继续停在“大纲待确认”
- 论文/模板输入层优先走 `Docling`，失败再回退 `MarkItDown`，最后才回到旧的 PDFBox / ZIP 提取逻辑；不要把文档解析和 PPT 生成重新绑死在一个脚本里
- 论文解析脚本是 `backen/scripts/ppt_document_parser.py`，只负责把 PDF/DOCX 变成结构化 Markdown/JSON，并把 DOCX 原图复制到解析输出目录；Java 后端负责排队、图片审核、提示词组装和 PPTX 生成
- 解析脚本的输出字段必须有消费方；不要保留未使用的中间参数、空字段或调试产物，避免后续把它们误认为稳定接口
- PPTX 模板色彩扫描只能读取真实 OOXML 色值属性，如 `srgbClr val="xxxxxx"` / `color="xxxxxx"`；不要用裸 `[0-9A-Fa-f]{6}` 全文匹配，否则会把坐标、尺寸、编号误识别为色值

**部署依赖**：
- 生产机器需要 Java 17、`uv`，以及可通过 `PPT_GENERATION_RENDERER_COMMAND` 启动的 `python-pptx` renderer；默认命令为 `uv run --with python-pptx --with pillow python`
- 上传模板原生填充链路通过 `PPT_GENERATION_TEMPLATE_FILL_COMMAND` / `PPT_GENERATION_TEMPLATE_FILL_SCRIPT` 启动；默认命令为 `uv run --with python-pptx python`，脚本为 `./scripts/ppt-template-fill/template_fill_pptx.py`
- `project.sh` 后端打包新鲜度会检查 `backen/src` 和 `backen/scripts` 下的 Java/XML/YAML/Python/JSON；改 PPT renderer、文档解析或 template-fill 脚本后必须重新打包/重启，避免线上继续跑旧 jar
- `deploy/install-linux.sh` 不再安装 Node/npm/PptxGenJS runner 依赖，但必须在安装/启动前检查 `uv` 是否存在；`deploy/build-release.sh` 仍会复制 `backen/scripts`
- 部署替换 `backen.jar` 前必须先停止 `web-backen.service`。Spring Boot 可执行 jar 会懒加载嵌套 class，运行中覆盖 jar 可能导致 `NoClassDefFoundError`（例如 `PptInputExtractor$TableCandidate` 或 logback 类）并让 PPT worker 异常退出
- 如果生产环境不能联网安装 Python 包，需要预先准备 `uv` 缓存或把 `PPT_GENERATION_RENDERER_COMMAND` / `PPT_GENERATION_TEMPLATE_FILL_COMMAND` 指向已安装 `python-pptx` 的 Python 解释器

## 前端 markdown 渲染

孤立 md 附件：前端检测 `filename` 以 `.md` 结尾时，不挂 iframe，而是：
1. `fetch('/api/zotero/file/{key}')` 拿文本
2. `marked.parse()` 渲染成 HTML（开 `breaks: true, gfm: true`）
3. `DOMPurify.sanitize()` 防 XSS（**不能省**，上游内容不可信）
4. `v-html` 注入到 `.md-render` 容器，样式见组件内 `<style>` 块

依赖：`marked` + `dompurify`（已加到 package.json）

## OpenClaw 已移除

2026-06-15 起，站内 OpenClaw 网页对话已安全下线：
- 后端删除 `OpenClawConfig`、`openclaw/` controller/service、`/api/openclaw/*` 接口和 `backen/scripts/openclaw_compat.sh`。
- 前端删除 `Franchise/index.vue`、`/franchise` 与 `/openclaw` 路由、OpenClaw API wrapper 和导航入口。
- 部署模板不再检查 Node/OpenClaw Gateway，不再设置 `OPENCLAW_*` 或 `NODE_COMPILE_CACHE`。

后续不要在默认站点恢复 OpenClaw 入口；如果重新引入，必须重新做鉴权、资源占用、子进程隔离和公网暴露风险评审。当前公开工具只保留文献库、PDF 翻译、PPT 生成和 GitHub 项目展示。

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
- 核心工具页统一使用 `common.scss` 的 `.tool-page`、`.tool-page__header` 首屏结构；`Publications`、`Translate`、`PptGenerate` 的标题基线、顶部间距和说明文案应保持一致。每个路由只保留一个稳定 `h1` 和一个页面级 `main`，不要在功能区嵌套第二个 `main`。
- 前端可访问性是正式维护要求：公共页必须保留“跳到主要内容”链接和 `header/nav/main/footer` landmarks；图标按钮必须有随状态变化的可访问名称；表单控件必须有 `label`、`aria-label` 或 `aria-labelledby`，帮助文字用 `aria-describedby` 关联。
- 所有可点击入口优先使用原生 `button`、`a`、`label`、`input`，不要用只绑定 `@click` 的 `div/li/p` 模拟按钮。动态排队、进度和完成状态应提供 `role="status"` / `aria-live="polite"`，紧急错误继续使用 alert。
- 手机端是正式支持场景：公共 `.container` 不允许再设置 1200px 这类桌面 `min-width`；复杂工具页必须在 375px 视口下无页面级横向滚动，头部桌面导航隐藏时必须保留移动抽屉菜单入口且避免同一目标在抽屉里重复出现
- 站点当前固定中文界面：头部不再提供 EN/中文切换，`front/src/i18n/en-US.js` 已删除，`App.vue` 的 Naive UI locale 也固定为中文。
- 案例展示仍在前端入口层折叠；GitHub 开源项目已重新启用，头部、页脚与首页均展示 `/news` 入口，首页通过站内 `/api/github-projects` 读取首个 `featured=true` 项目。`/about` 项目说明和 `/business` 工具模块路由保留，但目前不在公开导航与首页展示。
- 前端视觉方向为 Paper Index Studio：暖白纸张、细边框、轻量硬阴影、索引编号、低饱和红绿和衬线展示标题。核心工具页继续复用 `.tool-page` / `.tool-page__header` / `.tool-page__surface`，新增页面优先沿用这套视觉语言，避免恢复蓝色企业官网卡片风格。
- 前端叙事已从通用公司官网改为个人研究工具台：导航、首页、关于、工具模块、旧样例页和全局悬浮提示都应围绕 Zotero 文献、PDF 翻译、PPT 生成、GitHub 项目和低资源部署展开。不要恢复公司、招商、商务合作、客户案例、获奖新闻、报价客服等模板话术。
- 首页和说明页使用本地生成图片资产：`front/src/assets/images/research-workbench-hero.jpg`、`front/src/assets/images/research-pipeline-panel.jpg` 以及 `home-*.jpg` 工具卡图片。需要补充缺失视觉时优先生成项目相关图片并落到 `front/src/assets/images/`，照片型资产优先压成 JPG/WebP，不要继续引用随机 Picsum/Unsplash 办公图作为产品主视觉。
- 右下角悬浮按钮是邮件联系入口，不再是聊天客服。`CustomerService.vue` 通过 `mailto:` 打开本机邮件客户端，收件人固定为 `1241420431@qq.com`，并提供复制邮箱兜底；没有 SMTP 凭证前不要伪造服务端“已发送成功”。
- 一次性拿全部 47 条 items，**前端做过滤分组**（不重复请求接口）
- 渲染懒加载：默认 20 条，`IntersectionObserver` 滚动到底加载下一批
- PDF 懒加载：每张卡片 "查看 PDF" 按钮点击才挂 iframe（`/api/zotero/file/{key}`），再点收起卸载 iframe
- 导出三种：BibTeX / RIS 直接下载 .bib/.ris 文件；APA 复制到剪贴板（HTML 转纯文本）
- 启动时 `warmedUp=false` 会自动 2s 后重试 load（保险机制，应对极端首次冷启动）
- `News/index.vue` 现在是 GitHub 开源项目页，不是新闻页。它只调用本地 `/api/github-projects`，README 也走 `/api/github-projects/{owner}/{repo}/readme`。
- GitHub 项目后台入口在开源项目页右上角“后台管理”，用 `ADMIN_KEY` 登录。可以添加项目、填写 GitHub 地址、分类、说明、首页展示、上移/下移排序、删除。
- GitHub 项目展示顺序按 `.run/github-projects.json` 保存顺序来，不要在前端按 stars 自动重排。首页取第一个 `featured=true` 的项目作为独立展示卡。
- README 渲染仍然在前端用 `marked` + `DOMPurify`，但 README 文本必须由后端代理返回，不能让浏览器直连 GitHub。
- `PptGenerate/index.vue` 挂在 `/contact`，导航、首页 CTA、页脚工具入口都按“PPT 生成”语义展示；不要恢复旧 Contact 表单。PPT 页面现在是一段式直出体验：提交任务后只显示进度和下载结果，不再显示大纲编辑器。切换最近任务前必须关闭当前 SSE/polling，避免旧任务回调覆盖新任务状态。

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
- **GitHub 项目字段来源**：展示配置在 `.run/github-projects.json`，实时字段由 `GithubProjectService` 补全；保存列表时只保存 repo/highlight/category/featured，不保存 stars 等上游数据。
- **PPT 生成不要前端直连 LLM 或本地脚本**：浏览器只打 `/api/ppt-generate/*`，Python renderer、模板解析、论文抽取和 LLM 调用都在后端完成。
- **PPT 不再暴露大纲流程**：不要恢复 `/deck`、`/revise`、`/render` 接口，也不要把内部 PPT 结构 JSON 返回给前端编辑；用户提交后直接等待 PPTX。
- **PPT renderer 使用 python-pptx**：默认脚本是 `backen/scripts/ppt_renderer.py`，通过 `PPT_GENERATION_RENDERER_COMMAND` 启动；不要再引入 PptxGenJS、Node runner 或 npm 安装链路。
- **PPT 提取比例**：`extractionPercent` 由前端传入，范围 10-100。它控制 PDF 页面图、DOCX 图片和 PPTX 模板扫描数量；不要恢复成固定少量素材，也不要无视 2 核 / 4 GB 基线无上限提取。
- **PPT 上传模板优先原生填充**：技术评估见 `.review/evals/ppt-generation-framework-review.md`。上传 PPTX 模板时，优先采用 PPT Master `template_fill_pptx` 思路：先把模板分析成 slide library（页类型、文本槽、geometry、font metrics、表格/图表），再让 mimo 生成 fill plan，经过容量/槽位检查后 clone 原 slide 并替换 OOXML 文本。不要只把模板当配色/背景参考后用 `python-pptx` 重新画。
- **PPT 模板规划必须 layout-first**：mimo 规划时要根据模板页的 rhetorical affordance 选择源页，可重用、跳过、重排模板页；输出必须包含 source slide、slot_id replacements、layout rationale 和 notes。源模板顺序不能默认等于生成大纲顺序。
- **PPT 模板图片只能填白名单区域**：上传模板时应尽量使用论文图片/表格素材，但 fill plan 的 `image_edits` 只能引用真实 `imageManifest.images[].id` 和经过 analyzer + Java 二次过滤的可填 `region_id`。背景、logo、图标、装饰、小图，以及 cover/toc/chapter/ending 页图片区域不能填；模板没有可填内容图框时只替换文字并记录/说明原因。
- **PPT 模板不覆盖通用配色**：用户选择的通用模板色板仍是成品统一配色来源；上传 PPTX 的扫描色值不要覆盖 `builtInTemplate` palette。
- **PPT 表格素材**：DOCX 正文表格要转成 `paper-table-*.png` 进入图片素材；DOCX 内嵌的 `word/embeddings/*.xlsx` 至少读取第一个 worksheet 和 shared strings，转成 `paper-excel-*.png`。表格 fallback manifest 应标记为 `kind=table`、`useful=true`、`importance=4`。
- **PPT 不要硬塞图片**：普通内容页只有在内部结构明确给出 `imageId`、`imageHint`，或版式是 `image/full-image/image-left/image-right` 时才放论文图；不要因为任务目录里有图片就轮播填充每一页。
- **PPT 自由版式要有节奏**：无上传模板时，LLM 输出和 `ppt_renderer.py` 都支持 `image-top/evidence-strip/metric-hero/comparison/matrix/timeline` 等布局。不要把所有素材页渲染成固定右图加项目符号；连续内容页应在图文比例、指标页、对比页、矩阵页和时间线页之间切换。
- **PPT 图片必须先识图筛选**：视觉摘要 manifest 要包含 `source`、`model`、`useful`、`importance`、`layoutHint`。低价值图、空白图、重复图、纯装饰图、难以阅读的小公式/截图要标记 `useful=false` 或低 importance。只有 `source=vision` 才代表 mimo 视觉初筛成功；fallback 结果只能作为低风险兜底。
- **PPT 禁止文不对图**：LLM 选择 `imageId` 时，页面标题/核心句必须和 manifest 的 `title/summary/bestUse` 直接对应；没有对应关系就留空 `imageId`。
- **PPT 页数限制要压缩不要截断**：如果用户提示词要求 8 页、12 页等固定页数，后端要把 `targetSlideCount` 传给 LLM，让它在完整叙事上合并压缩内容；如果首次结构页数不匹配，后端要再让 LLM 按精确页数纠偏。自由生成链路里，若 LLM 纠偏后仍差少量页数，允许在致谢页前本地插入标记为 `localSlideCountFallback` 的补充页，或从中间内容页裁剪，最终仍必须满足显式页数；renderer 不允许用 `slice()` 偷偷截断前 N 页来满足页数。上传模板 fill plan 仍按模板槽位硬校验页数，不做盲目本地补页。
- **PPT LLM JSON 要容错**：mimo 偶尔会把合法 JSON 字符串边界输出成中文弯引号 `“...”`，导致 Jackson 报 `Unexpected character ('“')`。PPT 结构和视觉摘要的 LLM 返回必须走统一容错解析，先正常 parse，失败后只修复字符串边界上的智能引号再重试；不要把正文里的中文引号无脑全局替换。
- **PPT 页眉不要用论文编号**：内部结构的 `section` 应使用 `BACKGROUND/ROUTE/DATA/FEATURES/MODELS/RESULT/SCALE/CONCLUSION/OUTLOOK` 等语义标签；renderer 遇到 `1.1/2.3` 这类数字 section 要降级为语义页眉，避免成品像论文目录搬运。
- **重启后端**：用 `./project.sh restart backend` 而不是 `mvn spring-boot:run`，前者管 PID 和日志
- **前端改完不用重启**：Vite HMR 自动热更
- **文档同步是上线条件**：每次修改功能、接口、配置、部署流程或线上行为，都必须在同一轮任务中同步更新 `AGENTS.md` 或 `MAINTENANCE.md`。其中长期有效的架构和协作规则写入 `AGENTS.md`，本次上线内容、验证结果和排障记录写入 `MAINTENANCE.md`。不能只改代码后部署。
- **上线收尾**：本地验收完成后运行 `./deploy/deploy-server-improved.sh`。当前公网入口为 `https://shimmer.help/`。脚本保留 `/etc/web-homepage/web.env` 和服务器已有 Nginx 配置，避免覆盖 Certbot SSL；换服务器参数或 SSH 私钥绝对路径写入被忽略的 `.deploy.local`，不要把密码、私钥文件或 API Key 放进项目
- **服务器本机首页检查要带 Host**：生产机上裸 `curl http://127.0.0.1/` 可能命中其它 Nginx server block 并返回 403。检查首页用 `curl -I -H 'Host: shimmer.help' http://127.0.0.1/` 或公网 `curl -I https://shimmer.help/`；后端健康才直接查 `http://127.0.0.1:8080/api/health`。
- **发布包清理**：一键部署完成后，远端 `/home/admin/.web-homepage-releases/` 默认只保留最近 3 个 `web-homepage-*.tar.gz` 发布包和最近 3 个 `web-homepage-backup-*.tar.gz` 备份包；保留数量不能小于 1，不要把这个目录当长期归档用，需要长期保留的备份应移到单独位置
- **本地生成目录不要提交**：`.release/`、`server-upload/`、`outputs/`、`.run/` 都是发布包、部署拷贝、手工对比结果或运行态数据，必须保持在 `.gitignore` 中；如果误加入索引，用 `git rm --cached -r` 从 Git 跟踪中移除，别删除本地调试文件。
- **部署审计文档必须保留**：`AGENTS.md`、`MAINTENANCE.md`、`WORKLOG.md` 和 `release-manifest.txt` 是服务器交接与审计资料，发布包和服务器安装目录都要携带。不要把这些文档 staged 删除；如果要对外发布到 GitHub，只在外发流程中过滤内部文档，不能通过根 `.gitignore` 隐藏它们来规避审查。
- **部署脚本失败语义**：安装或本机健康检查失败时应恢复本次部署前备份；公网 DNS/外部网络检查失败不应自动回滚已经通过本机验证的服务。已有 Certbot/SSL Nginx 配置不能被 `FORCE_NGINX_CONFIG=1` 直接覆盖。
- **外部进程清理**：BabelDOC、PPT 文档解析、Python renderer、template-fill 等由后端启动的外部命令，超时或资源风险时必须终止整个进程树，不能只杀父进程。
- **写文件别用 cat/echo 重定向**，用专门的写工具（保护 UTF-8 中文）

## 已知小坑

- Zotero 偶尔抽风返回 503，缓存层吞掉错误并保留上一次数据，日志会 ERROR
- 火狐 PDF iframe 内嵌可能有 CSP 问题（Chrome OK，没测过 Safari）
- `useMessage` 依赖 `<n-message-provider>` 包裹，已在 `App.vue` 加了，新页面要用就别拆掉
- **Zotero 服务端把部分附件用 ZIP 打包**（md / 网页快照等），但上游 content-type 撒谎写 `text/plain`。后端 `fetchItemFile` 已处理；如果以后看到附件代理乱码，先看上游字节是不是 `PK\003\004`
- **生产服务器到 Zotero/S3 附件存储可能很慢**：2026-06-14 实测约 15 KB/s，4.99 MB PDF 在 90 秒内只能下载 1.33 MB。附件代理必须保持流式转发；不要通过不断提高完整下载超时来掩盖问题
- npm 在某些环境下 `~/.npm/_cacache` 会有权限问题，绕过：`npm install --cache /tmp/npm-cache <pkg>`

## 添加新功能的套路

想加个什么 Zotero 相关功能（比如收藏、笔记、注解）：
1. 看 Zotero API 文档对应字段
2. `ZoteroCache.simplify()` 里加字段提取
3. `ZoteroController` 看是否要新接口
4. 前端 `Publications/index.vue` 模板加渲染
5. 重启后端 → 浏览器刷新

完。
